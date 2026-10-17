# Anahtar dönüşü (JWT_SECRET, ENCRYPTION_SECRET)

Her iki anahtar da **kid + çift anahtar** destekler: yeni anahtar geçerli olur, eskisi bir süre yalnızca
**doğrulama/çözme** için tutulur. Kesinti yoktur, kullanıcılar oturumdan düşmez.

| | JWT_SECRET | ENCRYPTION_SECRET |
|---|---|---|
| Ne korur | Access (15 dk) ve refresh (7 gün) token imzası | Veritabanındaki GitHub erişim token'ları |
| Önceki anahtar(lar) | `JWT_PREVIOUS_SECRETS` (virgülle) | `ENCRYPTION_PREVIOUS_SECRETS` (virgülle) |
| Kimlik | Token başlığında `kid` | Değer biçimi `v2:<kid>:<base64>` |
| `kid` | Anahtarın SHA-256 özetinin ilk 4 baytı (hex). Yapılandırmadan çıkar; ayrıca tutulmaz. | aynı |
| Eski biçim (dönüş desteğinden önce) | `kid`'siz token → geçerli, sonra önceki anahtarlarla denenir | Öneksiz değer → geçerli, sonra önceki anahtarlarla denenir (GCM etiketi yanlış anahtarı reddeder) |
| Eskiyi ne zaman silebilirim | Refresh süresi (7 gün) dolunca | Yeniden şifreleme işi `failed=0` raporlayınca |

## Ne zaman
- **Planlı:** yılda bir (plan 5.7).
- **Olay anında (hemen):** `.env`, yedek, CI sırrı ya da sunucu erişimi sızdıysa. Sızıntıda JWT için önceki
  anahtar **tutulmaz** (aşağıda "Acil dönüş").

## Yeni anahtar üretme
```bash
openssl rand -base64 48                 # JWT_SECRET (en az 32 bayt)
echo "base64:$(openssl rand -base64 32)" # ENCRYPTION_SECRET (tam 32 bayt; eski 32 karakterlik metin biçimi de çalışır)
```
Anahtarları parola yöneticisinde/secret manager'da saklayın; `.env` dışında hiçbir yere (sohbet, ticket, log) yazmayın.

## Planlı dönüş — ENCRYPTION_SECRET
1. Yedek al: `./scripts/backup-db.sh`
2. `.env`:
   ```
   ENCRYPTION_PREVIOUS_SECRETS=<eski ENCRYPTION_SECRET>
   ENCRYPTION_SECRET=<yeni>
   ```
3. `docker compose up -d backend` (yeniden oluşturma; env yeniden okunur).
4. `GithubTokenReencryptionJob` açılıştan ~1 dk sonra çalışır ve eski değerleri partiler halinde yeni anahtarla yazar.
   Log: `Anahtar dönüşü: N token yeniden şifrelendi, 0 çözülemedi (kid=...)`.
   Metrik: `hashmark_crypto_reencrypted_total{result="ok|failed"}`.
5. Doğrula (hepsi yeni kid'le başlamalı; kid logdaki değerdir):
   ```bash
   docker exec hashmark_postgres psql -U hashmark -d hashmark -tAc \
     "SELECT left(github_token, 12) AS prefix, count(*) FROM users WHERE github_token IS NOT NULL GROUP BY 1"
   ```
6. `failed=0` ise `ENCRYPTION_PREVIOUS_SECRETS`'i boşalt → `docker compose up -d backend`.
   `failed>0`: o değerler listede olmayan daha eski bir anahtarla şifrelenmiş; eski anahtarı da listeye ekleyip
   tekrar çalıştırın ya da kabul edin (kullanıcı yeniden giriş yapınca token yenilenir; o zamana kadar tarama
   "GitHub token" hatası verir).

**Geri alma:** iki değeri ters çevirin (eski geçerli, yeni önceki). Yeni anahtarla yazılmış değerler çözülmeye
devam eder ve iş onları tekrar eskiye taşır.

**Dikkat:** önceki anahtarı iş bitmeden silmek → o token'lar çözülemez ("unknown key id"). Yedekler eski anahtarla
şifreli değer içerir; yedekten geri yüklerken o tarihteki anahtar da `ENCRYPTION_PREVIOUS_SECRETS`'te olmalı.
Bu yüzden eski anahtar, **PITR/yedek saklama süresi** (14–30 gün) boyunca parola yöneticisinde tutulur.

## Planlı dönüş — JWT_SECRET
1. `.env`: `JWT_PREVIOUS_SECRETS=<eski>`, `JWT_SECRET=<yeni>` → `docker compose up -d backend`.
2. Yeni token'lar yeni kid'le imzalanır; eski access/refresh token'lar geçerliliklerini korur.
3. **7 gün** (refresh süresi) sonra `JWT_PREVIOUS_SECRETS`'i boşalt → yeniden başlat.

## Acil dönüş (anahtar sızdı)
- **JWT:** `JWT_SECRET=<yeni>`, `JWT_PREVIOUS_SECRETS` **boş** → yeniden başlat. Tüm oturumlar düşer (bilinçli);
  ayrıca `DELETE FROM refresh_tokens;` ile kalıcı refresh kayıtlarını da iptal edin.
- **ENCRYPTION:** sızan anahtar DB dökümüyle birlikte sızdıysa GitHub token'ları açığa çıkmış sayılır:
  1. Yeni anahtara planlı dönüşteki gibi geçin (eskiyi önceki listesine koyarak).
  2. Token'ları geçersiz kılın: GitHub OAuth uygulamasında *Revoke all user tokens* (Settings → Developer settings →
     OAuth Apps → uygulama) ya da `UPDATE users SET github_token = NULL;` (kullanıcılar yeniden giriş yapar).
  3. Plan 5.9 (KVKK 72 saat bildirim) sürecini başlatın.

## Kapsam dışı / sonraki adım
- Yorum metni şifrelemesi (K3/K5, Tink + KMS + kullanıcı başına DEK) ayrı tasarımdır; geldiğinde GitHub token'ı da
  o yapıya taşınır ve bu belge güncellenir. Bugünkü şemada şifreli değer kullanıcıya bağlı değildir (AAD yok);
  bu, Tink geçişinde eklenecek.
- KEK'in bir KMS/secret manager'da tutulması (plan 5.7 "Sır yönetimi") bu belgenin dışında.
