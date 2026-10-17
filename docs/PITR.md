# PITR — belirli bir ana geri dönme (pgBackRest)

Gece `pg_dump` yedeği (`scripts/backup-db.sh`) en fazla **24 saatlik** veri kaybına izin verir. PITR ile
WAL sürekli arşivlenir: veritabanı son ~5 dakikadaki herhangi bir **ana** geri döndürülebilir (yanlış bir
`DELETE`, bozuk migrasyon, sunucu kaybı). İkisi birlikte çalışır; `pg_dump` taşınabilir mantıksal kopya olarak kalır.

| | Değer | Nereden |
|---|---|---|
| RPO (en çok veri kaybı) | ≈ 5 dk (`archive_timeout`) | compose `PG_ARCHIVE_TIMEOUT` |
| Saklama (PITR penceresi) | 14 gün (tam yedekler zamana göre silinir, WAL'lar onlarla) | `PITR_RETENTION_DAYS` |
| Depo | S3 uyumlu, **sunucudan ayrı sağlayıcı/bölge** | `pitr.env` |
| Şifreleme | İstemci tarafı AES-256 (`repo1-cipher-type`); sağlayıcı düz veri görmez | `PGBACKREST_REPO1_CIPHER_PASS` |
| Sıkıştırma | zstd | compose |
| Yedek planı | Pazar tam, diğer günler fark (diff) | cron |
| RTO ölçümü (yerel, küçük veri) | ≈ 7 sn geri yükleme + açılış | `pitr-e2e-test.sh` |

Saklama süresi gizlilik politikasıyla bağlantılıdır: silinen veri, PITR penceresi (14 gün) boyunca şifreli
yedeklerde kalır (Hashmark-Vault → Veri Saklama Politikası). Süreyi uzatmak bu metni de değiştirmeyi gerektirir.

## 1. Mimari
```
postgres (hashmark-postgres:18 = postgres:18-alpine + pgBackRest)
  archive_command → pgbackrest-archive-push.sh → pgbackrest archive-push (async, kuyruk ≤ 2 GiB)
                                                        │  zstd + AES-256
                                                        ▼
                                          S3 bucket /hashmark/{archive,backup}
cron → scripts/pitr-backup.sh full|diff → pgbackrest backup → backups/metrics/hashmark_pitr.prom
cron → scripts/pitr-restore-test.sh (3 ayda bir) → geçici konteyner + geçici volume → doğrula → sil
```
- Sırlar imaja girmez; `pitr.env` (git'e girmez) + compose ortamından `PGBACKREST_*` olarak okunur.
- `pitr.env` yoksa sarmalayıcı WAL'ı kabul edip atar: PITR kapalıdır ama WAL birikip diski **doldurmaz**.
- Depo erişilemezse WAL en fazla 2 GiB birikir, sonra atılır (DB'yi korur; o aralık için PITR boşluğu oluşur ve
  **WalArchivingFailing** alarmı çalar).
- Postgres konteyneri `init: true` ile çalışır. **Gerekli:** pgBackRest'in asenkron arşivleyicisi arka plana
  çatallanır; init olmazsa yetim süreç postmaster'a (PID 1) kalır ve hata koduyla çıktığında PostgreSQL bunu
  çökme sayıp tüm bağlantıları düşürür (yerel testte yeniden üretildi).

## 2. Kurulum (üretim, tek seferlik)
Önce depo: sunucudan **ayrı** bir sağlayıcıda bucket (ör. Cloudflare R2, Backblaze B2, AWS S3, Hetzner Object
Storage) ve yalnızca o bucket'a okuma/yazma/silme yetkili bir anahtar. Bucket'ta sürümleme (versioning) ya da
nesne kilidi açmak, ele geçirilmiş bir sunucunun yedekleri silmesini zorlaştırır (önerilir).

```bash
cd /opt/hashmark/hashmark-backend
cp postgres/pitr.env.example pitr.env && chmod 600 pitr.env
#   → bucket, endpoint, anahtar ve şifreleme parolasını doldurun (openssl rand -base64 48)
#   → ŞİFRELEME PAROLASINI PAROLA YÖNETİCİSİNE KAYDEDİN: kaybolursa yedekler açılamaz.
docker compose build postgres
docker compose up -d postgres          # imaj + init + archive_mode değişir → kısa yeniden başlatma
./scripts/pitr-backup.sh setup         # stanza + uçtan uca arşiv kontrolü (bir WAL'ı depoya yazar)
./scripts/pitr-backup.sh full          # ilk tam yedek
./scripts/pitr-restore-test.sh         # ilk tatbikat (geçici konteynerde; üretime dokunmaz)
```
Cron (`crontab -e`):
```
15 3 * * 0    /opt/hashmark/hashmark-backend/scripts/pitr-backup.sh full >> /opt/hashmark/hashmark-backend/backups/pitr.log 2>&1
15 3 * * 1-6  /opt/hashmark/hashmark-backend/scripts/pitr-backup.sh diff >> /opt/hashmark/hashmark-backend/backups/pitr.log 2>&1
0 5 1 1,4,7,10 * /opt/hashmark/hashmark-backend/scripts/pitr-restore-test.sh >> /opt/hashmark/hashmark-backend/backups/restore-drills.log 2>&1
```
Gereksinim: Docker Compose **2.24+** (`env_file: required: false`).

**Dikkat:** `pitr.env` eklendikten sonra `setup` hemen çalıştırılmalı; stanza yokken arşiv başarısız olur ve WAL
kuyrukta birikir (alarm 15 dk sonra çalar).

## 3. Geri yükleme (gerçek olay)
Önce **tatbikat betiğiyle** hedef anı geçici konteynerde deneyin (üretime dokunmaz):
```bash
./scripts/pitr-restore-test.sh "2026-10-17 14:30:00+03"    # olaydan hemen önceki an
```
Doğruysa üretimde:
```bash
docker compose stop backend
./scripts/backup-db.sh                                       # bugünkü durumun mantıksal kopyası (geri dönüş yolu)
docker compose stop postgres
docker compose run --rm --no-deps --user postgres --entrypoint sh postgres -c \
  'pgbackrest restore --delta --type=time --target="2026-10-17 14:30:00+03" --target-action=promote'
docker compose up -d postgres
docker compose logs postgres | grep -i -E "recovery|promot"   # "database system is ready to accept connections"
docker compose up -d backend
./scripts/pitr-backup.sh full                                # yeni zaman çizelgesinden temiz tam yedek
```
Aynı adımlar (durdur → mevcut volume'a `--delta` ile hedef ana → aç) `pitr-e2e-test.sh`'ta `docker run` ile
deneniyor; `docker compose run` biçimi üretim sunucusunda ilk kez kullanılacak. `--delta` yalnızca farklı
dosyaları yazar (hızlı). Hedef verilmezse (`--type` yok) son arşivlenmiş WAL'a kadar
geri yüklenir (sunucu/disk kaybı senaryosu: yeni sunucuda aynı `pitr.env` ile `restore`).

## 4. Doğrulama (bu depoda yapılanlar)
| Kontrol | Sonuç |
|---|---|
| `scripts/pitr-e2e-test.sh` (yerel: TLS'li MinIO + hashmark-postgres) | stanza + `check` ✓ · tam yedek 19–20 sn ✓ · **T1 anına geri yükleme**: T1 öncesi 10 kayıt var, sonrası 5 kayıt yok ✓ · latest: 15 kayıt ✓ · **yerinde `--delta` geri yükleme** (olay prosedürü) T1'e döndü ✓ · depodaki 1335/1335 dosya `Salted__` başlıklı (istemci tarafı şifreli) ✓ |
| Alarm kuralları | `WalArchivingFailing`, `PitrBackupFailed`, `PitrBackupStale`, `RestoreDrillOverdue` + promtool testleri ✓ |
| Üretim deposu / gerçek veri boyutu | **yapılmadı** — depo seçimi ve hesabı sizde; ilk tatbikat süresi Hashmark-Vault'a yazılmalı |

Yerel test notu: MinIO, Ekim 2025'te Docker Hub topluluk imajlarını kaldırdı; test önbellekteki imajı kullanır
(`PITR_E2E_MINIO_IMAGE` ile değiştirilebilir).

## 5. Sorun giderme
| Belirti | Bak | Müdahale |
|---|---|---|
| WalArchivingFailing | `docker logs hashmark_postgres 2>&1 \| grep -A3 archive-push` | Anahtar/endpoint/bucket; stanza yoksa `pitr-backup.sh setup` |
| `[039] HTTP 400` | endpoint adında `_` var mı, `URI_STYLE` | path/host stilini sağlayıcıya göre seçin |
| `[068] archive_command must contain pgbackrest` | compose `archive_command` | sarmalayıcının adı `pgbackrest-*` olmalı |
| Tatbikat `unknown key id`/şifre hatası | `PGBACKREST_REPO1_CIPHER_PASS` | Parola yöneticisindeki parolayla `pitr.env` |
| Geri yüklenen DB'de uygulama rolü yetkisi yok | (yalnızca `pg_dump` geri yüklemesinde) | `scripts/create-app-role.sh` yeniden çalıştırın; PITR roller dahil tüm kümeyi getirir |
