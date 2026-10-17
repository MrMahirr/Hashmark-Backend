# Kullanıcı onayı ve aydınlatma (plan 5.10, K7)

**Durum:** mekanizma hazır, kapı **kapalı** (`CONSENT_ENFORCED=false`). v1 metni taslak; hukuki inceleme bekliyor.

## Uçlar
| Uç | Kimlik | Ne yapar |
|---|---|---|
| `GET /public/consent-policy?lang=tr\|en` | yok | Geçerli metin, sürüm ve SHA-256 özeti |
| `GET /public/private-repo-notice?lang=` | yok | Özel depo ek uyarısı |
| `GET /user/consent` | var | Durum: `enforced`, `required`, `currentVersion`, `acceptedVersion`, `acceptedAt` |
| `POST /user/consent` `{version, language, hash}` | var | Gösterilen metnin sürüm+özeti eşleşmeli; değilse `409 POLICY_OUTDATED` |
| `DELETE /user/consent` | var | Geri çekme (veri silinmez; hesap silme ayrı) |
| `POST /repos/{id}/private-ack` | var | Özel depo uyarısını onaylar (yalnızca kendi deposu) |
| `GET /user/me` | var | `consentRequired`, `consentVersion`, `consentAcceptedAt` eklendi |

## Kapı açıkken (`consent.enforced=true`)
- Onaysız kullanıcı: `/user/me`, `/user/consent`, `DELETE /user/account`, `DELETE /user/github-connection`,
  `/auth/**`, `/public/**` dışındaki tüm uçlar → `403 {"code":"CONSENT_REQUIRED"}`.
- Tarama işçisi kuyruktaki işi onay ve özel depo onayı için **yeniden denetler** (geri çekilmişse FAILED).
- Haftalık özet onaysız kullanıcıyı atlar.
- Özel depo: `POST /scan/{id}` → `403 PRIVATE_REPO_ACK_REQUIRED` (ek onaya kadar).
- Kayıt: `user_consents` (sürüm, dil, metin özeti, zamanlar). **IP/User-Agent saklanmaz.** Geri çekme satırı
  silmez, `withdrawn_at` yazar (kanıt korunur).
- Karar 30 sn önbelleklenir (tek instance'ta kabul/geri çekme anında etkili).

## Açma sırası
1. Metni hukukçuya inceletin (`src/main/resources/consent/`, README).
2. Frontend `/consent` sayfasını ve 403 yönlendirmesini yayına alın.
3. `.env`: `CONSENT_ENFORCED=true` → `docker compose up -d backend`. Tüm kullanıcılar bir sonraki girişte onay ekranını görür.

**Geri alma:** `CONSENT_ENFORCED=false` → yeniden başlat. Onay kayıtları kalır.

## Metin değişikliği
Yayınlanmış `policy-vN` dosyası değiştirilmez: `policy-v(N+1).{tr,en}.txt` eklenir, `consent.policy-version`
artırılır → herkesten yeniden onay. Yorum metni şifrelemesi (K3) geldiğinde "Nasıl korunur" bölümü değişmek
zorunda (v1 bugünkü durumu dürüstçe "şifrelenmeden saklanır" diye yazar; `ConsentPolicyCatalogTest` denetler).

## Testler
`ConsentGateTest` (kapı açık, gerçek PG): onaysız 403 (`/v1` dahil) · akış uçları açık · yanlış özet/sürüm 409 ·
kabul sonrası erişim · geri çekme kapıyı kapatır ve kuyruktaki işi durdurur · yeniden onay geçmişi korur ·
özel depo ek onayı (başkasının deposu onaylanamaz) · onaysız hesap silme çalışır.
