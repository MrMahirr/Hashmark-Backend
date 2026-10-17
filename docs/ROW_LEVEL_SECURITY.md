# Row-Level Security (kiracı izolasyonu, plan 5.5 / K4)

Üç katmanlı koruma: **uygulama** (her sorgu `user_id` ile kapsamlı) → **veritabanı** (bu belge) → kriptografi
(kullanıcı başına anahtar, plan 5.4 — henüz yok). RLS, uygulama kodunda bir sorgu kullanıcı filtresini unutsa
bile başka kullanıcının satırının **okunmasını, değiştirilmesini ve yazılmasını** engeller.

## 1. Model

| Parça | Ne yapar |
|---|---|
| `V11__row_level_security.sql` | `repos`, `notifications`, `user_settings` (doğrudan `user_id`), `debts`, `scan_jobs`, `scan_snapshots` (depo sahipliği üzerinden) için `USING` + `WITH CHECK` politikası |
| `app.user_id` / `app.bypass_rls` | Oturum ayarları; politika bunlara bakar. İkisi de boşsa **hiçbir satır görünmez** (kapalı başarısızlık) |
| `TenantAwareDataSource` | Havuzdan alınan **her** bağlantıya o anki kapsamı yazar (alım başına tek kısa sorgu) |
| `TenantContext` | Kapsam: açık `asUser`/`asSystem` → HTTP isteğindeki JWT kimliği → yok |
| Uygulama rolü | `hashmark_app`: süper kullanıcı değil, `BYPASSRLS` yok, tablo sahibi değil, yalnızca DML |
| Sahip rol | `POSTGRES_USER` (hashmark): migrasyonlar, yedek, yönetim. Politikayı **atlar** (ENABLE, FORCE değil) |

`users`, `refresh_tokens`, `oauth_states` RLS'siz: giriş öncesi akış (kimlik henüz yok) bunlara erişir ve bu
tablolarda başka kullanıcının verisine ulaştıran bir uç yok (çapraz kiracı testleri).

### Sistem kapsamı (kullanıcılar arası, bilinçli)
| Yol | Kapsam |
|---|---|
| `ScanJobDispatcher.tick` (kurtarma, sahiplenme, geri bırakma) | sistem |
| Tarama işçisi (`ScanJobProcessor`) | **işin sahibi** (`asUser`); sahibi olmayan eski iş yalnızca FAILED işaretlenir |
| `DataRetentionJob` | sistem |
| `WeeklyReportScheduler` alıcı listesi / her rapor | sistem / **o kullanıcı** |
| `ScanQueueMetrics` | sistem |
| `GET /public/stats` | sistem (yalnızca toplam sayı döner) |

**Yeni kod kuralı:** HTTP isteği dışında (zamanlanmış iş, executor, olay dinleyici) RLS'li tabloya dokunan her
yol `TenantContext.asUser(...)` ya da gerekçeli bir yorumla `TenantContext.asSystem(...)` içinde çalışır.
Kapsam transaction'ı **sarmalı**, içinde açılmamalıdır (bağlantı transaction başında bir kez alınır).
İşaretsiz yol veri göremez — bu bilinçli: unutulan kapsam sızıntı değil, görünür bir hata üretir.

## 2. Etkinleştirme (üretim, tek seferlik)

Kod ve migrasyon dağıtılınca **davranış değişmez**: uygulama hâlâ sahip rolle bağlanır ve politika atlanır
(`hashmark_db_rls_enforced = 0`). Etkinleştirme ayrı ve geri alınabilir bir adımdır.

```bash
cd /opt/hashmark/hashmark-backend
./scripts/backup-db.sh
# 1) Rolü oluştur (idempotent; parolayı parola yöneticisine kaydedin)
DB_APP_PASSWORD="$(openssl rand -base64 24)"; echo "$DB_APP_PASSWORD"
DB_APP_PASSWORD="$DB_APP_PASSWORD" ./scripts/create-app-role.sh
# 2) .env
#    DB_APP_USER=hashmark_app
#    DB_APP_PASSWORD=<yukarıdaki>
# 3) Yeniden başlat ve doğrula
docker compose up -d backend
docker compose logs backend | grep -i -E "error|rls" | tail
curl -s http://localhost:8081/actuator/prometheus | grep hashmark_db_rls_enforced   # 1.0 olmalı (konteyner içinden)
```
Ardından arayüzde: giriş, depo listesi, tarama başlatma ve bitmesi, borç listesi/arama, rapor, bildirimler.

**Geri alma:** `.env`'den `DB_APP_USER`/`DB_APP_PASSWORD` satırlarını sil → `docker compose up -d backend`
(sahip role döner, politika atlanır). Veri değişmediği için başka işlem gerekmez. Politikaları tamamen kaldırmak
için V11 başlığındaki SQL.

**Parola dönüşü:** aynı betiği yeni `DB_APP_PASSWORD` ile çalıştır → `.env` → yeniden başlat.

## 3. Doğrulama ve maliyet

| Test | Ne kanıtlar |
|---|---|
| `RowLevelSecurityTest` (uygulama ayrı rolle, gerçek PG) | kapsamsız bağlantı 0 satır; kullanıcı kapsamında WHERE'siz sorgu/UPDATE yalnızca kendi satırı; başkasının deposuna INSERT `42501`; HTTP uçları çalışır; **tarama uçtan uca** (sahte GitHub) RLS altında DONE; saklama, kuyruk metriği, herkese açık sayaç, haftalık rapor tüm kullanıcıları doğru kapsamla işler |
| Mutasyon denemesi | `DataRetentionJob`'dan `asSystem` kaldırılınca test düşüyor (hiçbir şey silinemiyor) |
| `CrossTenantHttpTest` / `CrossTenantIsolationTest` | uygulama katmanı (RLS'den bağımsız) |
| `scripts/create-app-role.sh` (PG 18'de denendi) | iki kez çalıştırılabilir; uygulama rolü tablo oluşturamaz, Flyway geçmişini okuyamaz, RLS'i kapatamaz; sonraki migrasyon tabloları yetkiyi otomatik alır |

**Ölçülen ek maliyet** (yerel PG 18, kullanıcı başına 20.000 borç, 200 kullanıcı; 5 ölçüm ortalaması):

| Sorgu | RLS yok | RLS | Fark |
|---|---|---|---|
| Borç listesi (50 satır, sıralı) | 10,4 ms | 13,4 ms | +3,0 ms |
| Sayım | 5,0 ms | 7,8 ms | +2,8 ms |
| Arama (`ILIKE`) | 16,0 ms | 18,2 ms | +2,2 ms |

Bunlara bağlantı alımı başına bir `set_config` gidiş-dönüşü eklenir (ayrıca ölçülmedi). Faz 1 yük testinin
RLS etkin hâlde yeniden koşulması önerilir (henüz yapılmadı).

## 4. Bilinen sınırlar
- Sahip rol ve süper kullanıcı politikayı atlar: DB'ye sahip rolle erişen operatör her şeyi görür (plan: "operatör
  erişimi yalnızca DB'den, denetim kaydıyla" — denetim kaydı henüz yok).
- `app.user_id` uygulama tarafından yazılır; SQL enjeksiyonu olan bir sorgu `set_config` çağırabilir. Bu yüzden
  RLS, parametreli sorgu kuralının **yerine geçmez**, onun arkasındaki ikinci katmandır.
- FK `ON DELETE CASCADE` (hesap silme) RLS'den bağımsız çalışır (PostgreSQL bütünlük eylemleri politikayı atlar).
