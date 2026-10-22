# Otomatik üretim dağıtımı

`master`'a gelen her birleştirme, o depodaki `ci` iş akışı yeşil biterse üretime dağıtılır.

```
PR → master ──▶ ci (test, lint, build) ──yeşil──▶ deploy.yml ──ssh (kısıtlı anahtar)──▶ /opt/hashmark/bin/hashmark-deploy
                                                                                          kilit → yedek → ff-merge →
                                                                                          build → up → sağlık
                                                                                          → başarısızsa önceki commit'e dönüş
```

- Backend ve frontend ayrı depolar: her biri kendi `deploy.yml`'iyle yalnızca kendi bileşenini dağıtır.
- Aynı anda ikisi birleşirse sunucu frontend'i, backend `master`'ı dağıtılana kadar bekletir (en çok 25 dk; backend
  CI kırıksa frontend dağıtımı durur ve iş kırmızı biter).
- Backend dağıtımı öncesinde veritabanı dökümü alınır (`~/deploy-backups/<zaman>-auto-backend/`, son 20 tutulur).
- Yeni sürüm 5 dk içinde `healthy` olmazsa önceki commit'e dönülür. Migrasyonlar genişletme kuralına uyduğu için
  (`docs/MIGRATIONS.md`) eski kod yeni şemayla çalışır; şema geri alınmaz.
- Loglar: GitHub Actions çıktısı + sunucuda `~/deploy-logs/`.

## Bir kerelik kurulum

1. **Sunucu** (yapıldı, 2026-10-22):
   ```bash
   install -m 0755 /opt/hashmark/hashmark-backend/scripts/auto-deploy.sh /opt/hashmark/bin/hashmark-deploy
   # ~deploy/.ssh/authorized_keys — anahtar YALNIZCA bu betiği çalıştırabilir:
   restrict,command="/opt/hashmark/bin/hashmark-deploy" ssh-ed25519 AAAA... github-actions-hashmark-deploy
   ```
2. **GitHub** — iki depoda da (Settings → Environments → `production` → Environment secrets):
   | Secret | Değer |
   |---|---|
   | `PROD_SSH_KEY` | kısıtlı anahtarın özel kısmı (tamamı, `-----BEGIN ...` dahil) |
   | `PROD_KNOWN_HOSTS` | sunucunun host anahtarı satırları (`45.88.139.30 ssh-ed25519 ...`) |
   | `PROD_HOST` | `deploy@45.88.139.30` |
   İsteğe bağlı: `production` ortamına "Required reviewers" eklenirse her dağıtım onay bekler.

## Günlük kullanım

- Elle dağıtım / yeniden deneme: Actions → `deploy` → **Run workflow** (`master` seçili).
- Durum: `ssh -i <kısıtlı anahtar> deploy@45.88.139.30 status`.
- Betik değişince sunucudaki kopya **elle** güncellenir (dağıtım kendini değiştirmesin diye kurulu kopya çalışır):
  `install -m 0755 /opt/hashmark/hashmark-backend/scripts/auto-deploy.sh /opt/hashmark/bin/hashmark-deploy`.

## Elle geri dönüş

```bash
cd /opt/hashmark/hashmark-backend           # ya da hashmark-frontend
cat ~/deploy-backups/<zaman>-auto-backend/previous-commit
git reset --hard <önceki commit> && docker compose build backend && docker compose up -d backend
```
Sonraki `master` birleştirmesi sunucuyu yeniden `origin/master`'a getirir.

## Durdurma

- Geçici: GitHub'da Actions → `deploy` → **Disable workflow** (iki depoda).
- Kalıcı: `authorized_keys`'ten `github-actions-hashmark-deploy` satırını silin ve secret'ları kaldırın.
