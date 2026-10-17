# Yoğunluğa dayanıklılık (plan Faz 3)

Amaç: ani yoğunlukta ya da GitHub kesintisinde sistem **çökmeden yavaşlasın**. Giriş, okuma ve veri bütünlüğü
ayakta kalır; pahalı işler (tarama, export, senkron) önce bekler ya da `429/503 + Retry-After` alır.

| Katman | Ne yapar | Nerede |
|---|---|---|
| nginx `limit_req` | IP başına ilk savunma | `nginx/nginx.conf` |
| Hız sınırı matrisi | Uç + kullanıcı + IP kuralları (429) | `RateLimitFilter` |
| Hesap bazlı sınır | Refresh ve giriş, hesap başına (429) | `AccountRateLimiter` |
| Yük atma | Baskı altında yalnızca pahalı uçlar 503 | `LoadSheddingFilter` |
| Kotalar + kuyruk üst sınırı | Kullanıcı başına aktif/günlük tarama, depo sayısı/boyutu; toplam kuyruk | `ScanQuotaService` |
| Adil kuyruk | Kullanıcı başına round-robin sahiplenme | `ScanJobRepository.claimPending` |
| Bulkhead + circuit breaker | GitHub çağrıları iki ayrı şeritte, kesintide hızlı hata | `GitHubResilience` |
| Erteleme | Geçici hatada iş FAILED değil, üstel geri çekilmeyle ertelenir | `ScanJobProcessor` |
| Fencing + düzgün kapanış | Devredilen işin eski çalıştırması yazamaz; kapanışta iş hemen devredilir | `claim_token`, `ScanJobDispatcher.stop()` |
| Dağıtık kilit | Zamanlanmış işler 2+ instance'ta tek çalışır | ShedLock (`shedlock` tablosu) |

## 1. GitHub: bulkhead + circuit breaker

- **İki şerit (bulkhead):** `interactive` (giriş, repo listesi, canlı bağlam; varsayılan 100 eşzamanlı, 200 ms
  bekleme; yük testiyle seçildi, bkz. §8) ve `scan` (tarball, blame; 8 eşzamanlı, 2 sn bekleme). Tarama işçileri kullanıcı girişinin kapasitesini
  tüketemez. Şerit doluysa `503 GITHUB_BUSY` (Retry-After 2).
- **Tek devre:** son 20 çağrının ≥ %50'si hata (ağ hatası, zaman aşımı, 5xx) ya da ≥ %80'i 8 sn'den yavaşsa
  (en az 10 çağrı) devre açılır. Açıkken çağrı GitHub'a hiç gitmez: `503 GITHUB_UNAVAILABLE` (Retry-After 15).
  15 sn sonra yarı açık: 3 deneme çağrısı başarılıysa kapanır. GitHub döndükten sonra en kötü toparlanma
  gecikmesi ≈ okuma zaman aşımı (10 sn) + 15 sn (deneme çağrısı kesintinin son anına denk gelirse).
- **4xx hata sayılmaz** (yetki, bulunamadı, rate-limit GitHub'ın ayakta olduğunu gösterir).
- RestTemplate yolundaki ağ hataları artık `500` yerine `502` döner.
- Şerit izni yanıt **başlıkları** gelince bırakılır; tarball gövde akışı tarama süresi sınırıyla korunur.

**Yeniden deneme kuralları**

| Yol | Deneme | Bekleme |
|---|---|---|
| Tarama şeridi, GET/HEAD ve blame GraphQL sorgusu | 4 | 1/2/4 sn taban, %50–150 jitter |
| Kullanıcı şeridi (GET) | 2 | aynı; rate-limit için **beklemez**, 429 + Retry-After |
| Diğer POST/PUT/DELETE, OAuth kod değişimi | 1 | — (idempotent değil; kod tek kullanımlık) |
| Rate-limit (tarama şeridi) | `Retry-After` / sıfırlanma ≤ 60 sn ise beklenir (+ 0–1 sn jitter) | |

**Metrikler:** `resilience4j_circuitbreaker_state{name="github"}`, `resilience4j_circuitbreaker_calls_seconds_*`,
`resilience4j_bulkhead_available_concurrent_calls{name="github-interactive|github-scan"}`,
`hashmark_github_rejected_total{lane,reason}`. Alarm: `GithubCircuitOpen`.

## 2. Tarama işi: erteleme, fencing, kapanış

**Erteleme.** İşçide geçici hata (`503`/`429`: devre açık, şerit dolu, GitHub rate-limit, kesinti) olursa iş
`FAILED` olmaz; `PENDING` + `run_after = şimdi + gecikme` olur. Gecikme `30 sn × 2^n` (en fazla 15 dk) + %0–25 jitter;
GitHub'ın önerdiği bekleme daha uzunsa o. Deneme hakkı yakılmaz; 8 ertelemeden sonra `FAILED`. Kullanıcıya
bildirim yalnızca kesin sonuçta gider. Metrik: `hashmark_scan_deferred_total`. Kuyruk yaşı metriği ertelenmiş işi
erteleme bitene kadar "bekliyor" saymaz (`ScanQueueBacklog` yanlış alarm vermez).

**Fencing (`scan_jobs.claim_token`).** Her sahiplenme yeni bir UUID üretir. Sonuç yazımı, ilerleme, erteleme ve
geri bırakma bu jetonla kapsamlanır. Kalp atışı kesilip başkasına devredilen ya da kapanışta geri bırakılan işin
eski çalıştırması geç gelse de **hiçbir şey yazamaz** (borç, snapshot, durum, bildirim).

**Düzgün kapanış** (`server.shutdown=graceful`, compose `stop_grace_period: 60s`):
1. `ScanJobDispatcher.stop()` (en yüksek faz, ilk çalışır): yeni iş alınmaz; çalışan taramalar
   `SCAN_SHUTDOWN_GRACE_SECONDS` (15) kadar beklenir.
2. Bitmeyenler jetonla `PENDING`'e geri bırakılır ve işçileri kesilir → başka instance ya da yeniden açılış
   **hemen** devralır (eskiden 5 dk kalp atışı zaman aşımı beklenirdi).
3. Web sunucusu yeni bağlantı almaz, süren istekleri en fazla 20 sn bitirir; executor 5 sn içinde kapanır.

## 3. Kotalar ve kuyruk üst sınırı

| Kota | Varsayılan | Aşılınca | Ortam değişkeni |
|---|---|---|---|
| Kullanıcının aktif (kuyrukta + çalışan) taraması | 3 | `429 SCAN_ACTIVE_LIMIT` (Retry-After 30) | `SCAN_QUOTA_ACTIVE_PER_USER` |
| Son 24 saatte başlatılan tarama | 50 | `429 SCAN_DAILY_LIMIT` (Retry-After 3600) | `SCAN_QUOTA_PER_DAY` |
| İzlenen depo | 50 | `403 REPO_LIMIT_REACHED` | `SCAN_QUOTA_TRACKED_REPOS` |
| Depo boyutu (GitHub `size`) | 1024 MB | `403 REPO_TOO_LARGE` (izleme ve tarama) | `SCAN_QUOTA_REPO_SIZE_MB` |
| Toplam kuyruk (tüm kullanıcılar) | 500 | `503 SCAN_QUEUE_FULL` (Retry-After 60) | `SCAN_MAX_QUEUE_DEPTH` |

`0` kotayı kapatır. Sayım ile ekleme arasında küçük bir yarış payı vardır (koruma amaçlı; faturalama değil).
Depo boyutu senkronda (`POST /repos/sync`) güncellenir; bilinmiyorsa (eski kayıt) boyut kotası uygulanmaz.
Tek dosya/dosya sayısı/süre sınırları (`scan.max-*`) ayrıca geçerlidir. Metrik: `hashmark_scan_quota_rejected_total{kind}`.

**Adil kuyruk:** her turda kullanıcı başına yalnızca en eski hazır iş adaydır; adaylar kullanıcının çalışan iş
sayısına göre sıralanır. Tek kullanıcı 3 işini kuyruğa koysa da yeni gelen başka bir kullanıcının işi arkasında
beklemez. Boş kapasite kalmışsa aynı kullanıcı sonraki turda devam eder (iş koruyucu; kapasite boşa harcanmaz).

## 4. Yük atma (load shedding)

Pahalı uçlar: `POST /scan/**`, `GET /debts/export`, `POST /repos/sync`, `GET /debts/{id}/context`,
`POST /report/send-test`. Şunlardan biri doğruysa bu uçlar `503 OVERLOADED` (Retry-After 10) alır:
- `hikaricp_connections_pending ≥ LOAD_SHEDDING_DB_PENDING` (1): havuzda bağlantı bekleyen var;
- bu instance'ta eşzamanlı istek > `LOAD_SHEDDING_MAX_INFLIGHT` (100; Tomcat 200 iş parçacığı).

Giriş (`/auth/**`) ve okuma uçları **asla** burada reddedilmez. Metrikler: `hashmark_loadshed_rejected_total{class,reason}`,
`hashmark_http_inflight`. Alarm: `LoadSheddingActive` (10 dk sürekli).

## 5. Hız sınırı matrisi

İstek/dakika; `0` kapatır. Her istek eşleştiği tüm kurallardan geçmelidir.

| Kapsam | Eşleşme | Anahtar | Varsayılan | Ortam değişkeni |
|---|---|---|---|---|
| auth | `/auth/**` | IP | 30 | `RATE_LIMIT_AUTH_PER_MINUTE` |
| scan | `POST /scan/**` | kullanıcı | 10 | `RATE_LIMIT_SCAN_PER_MINUTE` |
| export | `GET /debts/export` | kullanıcı | 10 | `RATE_LIMIT_EXPORT_PER_MINUTE` |
| sync | `POST /repos/sync` | kullanıcı | 6 | `RATE_LIMIT_SYNC_PER_MINUTE` |
| context | `GET /debts/{id}/context` | kullanıcı | 60 | `RATE_LIMIT_CONTEXT_PER_MINUTE` |
| public | `/public/**` | IP | 120 | `RATE_LIMIT_PUBLIC_PER_MINUTE` |
| user | kimlikli her istek | kullanıcı | 600 | `RATE_LIMIT_USER_PER_MINUTE` |
| ip | her istek | IP | 1200 | `RATE_LIMIT_IP_PER_MINUTE` |
| account_refresh | `POST /auth/refresh` | kullanıcı (token'dan) | 20 | `RATE_LIMIT_REFRESH_PER_ACCOUNT` |
| account_login | `/auth/callback` | GitHub hesabı | 10 | `RATE_LIMIT_LOGIN_PER_ACCOUNT` |

Refresh sınırı token **tüketilmeden önce** denetlenir: 429 alan istemcinin token'ı yanmaz.

**Güvenilir proxy:** IP, `X-Forwarded-For`'dan yalnızca `TRUSTED_PROXIES` (regex; varsayılan loopback + özel ağlar,
yani Docker köprüsündeki nginx) adreslerinden gelen isteklerde çözülür. nginx gerçek IP'yi sona ekler
(`$proxy_add_x_forwarded_for`), Tomcat sağdan ilk güvenilmeyen adresi alır; istemcinin yazdığı sahte başlık işe yaramaz.
Ters vekil farklı bir ağdaysa `TRUSTED_PROXIES` onun adresine daraltılmalıdır.

Sınırlar instance başınadır (bellek içi). 2+ instance'ta ortak depo (Redis) Faz 4'tedir.

## 6. Zamanlanmış işler: dağıtık kilit

ShedLock + `shedlock` tablosu (V13), zaman veritabanından. Kilitli işler: `weekly-report` (en az 5 dk tutulur;
saat kayması olan ikinci instance da göndermez), `data-retention`, `auth-token-cleanup`, `github-token-reencrypt`.
Tarama dağıtıcısı **kilitlenmez** (her instance `SKIP LOCKED` ile kendi işini alır). Kilit tutan instance çökerse
kilit `lockAtMostFor` sonunda serbest kalır. Elle bakmak:

```sql
SELECT name, locked_by, locked_at, lock_until FROM shedlock ORDER BY name;
-- takılı kilidi serbest bırakmak (yalnızca o iş hiçbir instance'ta çalışmıyorsa):
UPDATE shedlock SET lock_until = NOW() WHERE name = 'data-retention';
```

## 7. Önbellek (bilinçli olarak yapılmadı)

Plan "yalnızca ölçüm gerektirirse" diyor. Faz 1 yük testinde (kararlı durumda 1 CPU ≈ 430 istek/sn) darboğaz
CPU/JIT ısınmasıydı, sorgu değil; sorgu/önbellek yatırımı **gerekmedi**. Süreç içi kısa TTL önbellek (Caffeine) ve
`ETag` tetikleyicisi: dashboard p95 > 500 ms ve indeksle düzelmiyor (plan Bölüm 7). Önbellek eklenirse triyaj ve
tarama sonrası kullanıcı bazlı geçersizleştirme şarttır (yoksa kullanıcı az önce değiştirdiğini eski görür).

## 8. Yük testi ölçümleri (2026-10-17, yerel Docker: backend 1 CPU / 1 GB, PG 1 CPU / 512 MB)

`./loadtest/run.sh login|outage|shed` (sahte GitHub, 150 ms gecikme). Sonuçlar gerçek VDS değil, bu sınırlı ortamdır.

| Senaryo | Sonuç | Karar |
|---|---|---|
| 500 eşzamanlı giriş, kullanıcı şeridi **20** | callback'lerin **%41'i** `503 GITHUB_BUSY` (8.017 red); devre kapalı, 5xx yok | Şerit darboğaz → **100** |
| Aynı, şerit **100** | %97,2 başarı; kalan redler şerit dolu (226) + DB havuzu (89), hepsi 503; CPU %90 | Varsayılan 100 |
| GitHub kesintisi (60 sn, yanıt yok) + 100 giriş + 100 okuyucu | Kesintide giriş **hızlı 503, p95 6,7 ms** (devre açılana kadar 63–73 istek 10 sn sonra 502); okumalar **%100, p95 37–47 ms** | Devre amacını yerine getiriyor |
| Aynı, toparlanma (açık bekleme 30 sn → 15 sn) | GitHub dönünce 30 sn'lik pencerede başarılı giriş 670 → **1.035**, 503: 2.018 → 1.526 | Varsayılan 15 sn |
| Stres: dashboard 200 kullanıcı + 20 sürekli export, havuz 5 | Export'ların 2.310/2.312'si `503 OVERLOADED` (db_pool 1.223, inflight 1.087); dashboard %99,85; 5xx yok | Yük atma doğru |
| Normal: dashboard 50 kullanıcı + 20 export, havuz 15 (ısınmalı) | Export **2.360/2.360 başarılı**, dashboard %100, p95 10 ms; ölçüm penceresinde yük atma yok | Yanlış pozitif yok |

k6 tek IP'den geldiği için koşucu IP/kullanıcı/hesap hız sınırlarını kapatır (`run.sh`). Soğuk JVM'in ilk ~20 sn'si
(JIT ısınması) tüm senaryolarda yavaştır (Faz 1 bulgusu, karar bekliyor).

## Runbook: GitHub kesintisi

**Belirti:** `GithubCircuitOpen` alarmı; girişte `503 GITHUB_UNAVAILABLE`; taramalar "Retrying in …" mesajıyla bekliyor.
1. **Teşhis:** https://www.githubstatus.com · `curl -s backend:8081/actuator/prometheus | grep -E 'circuitbreaker_state|github_rejected'`
   · `docker compose logs backend | grep "GitHub circuit breaker"` (durum geçişleri loglanır).
2. **Müdahale:** genellikle gerekmez. Devre 15 sn'de bir yarı açılıp dener, GitHub dönünce kendiliğinden kapanır;
   ertelenen taramalar `run_after` dolunca sırayla devam eder (en fazla 8 erteleme ≈ 1 saat). Kesinti uzunsa
   `FAILED` işler kullanıcı tarafından yeniden başlatılabilir.
3. **Yanlış alarm şüphesi** (GitHub ayakta ama devre açık): ağ/DNS/egress'i kontrol edin
   (`docker exec hashmark_backend curl -sI https://api.github.com`). Zaman aşımları çoksa `HTTP_READ_TIMEOUT_MS` ve
   yavaş çağrı eşiği (`resilience.github.slow-call-duration-ms`) gözden geçirilir.
4. **Doğrulama:** `resilience4j_circuitbreaker_state{state="closed"} == 1`; `hashmark_scan_deferred_total` artışı durur.
5. **Bilgilendirme:** uzun kesintide durum sayfası/duyuru: "GitHub kaynaklı; veriler güvende, taramalar sırada".
