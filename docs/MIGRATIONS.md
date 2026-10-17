# Veritabanı migrasyon kuralları

Flyway migrasyonları backend açılışında otomatik çalışır (`src/main/resources/db/migration`).
Kurallar **V11 ve sonrası** için zorunludur ve CI'da `MigrationConventionsTest` ile denetlenir.
V1–V10 kurallardan önce yazıldı; Flyway checksum'ı değişmesin diye **dokunulmaz**.

## 1. Temel kurallar

| # | Kural | Neden | Denetim |
|---|---|---|---|
| 1 | Dosya adı `V<n>__kucuk_harf_alt_cizgi.sql`, sürümler ardışık | `out-of-order` kapalı; atlanan sürüm dağıtımda karışıklık yaratır | test |
| 2 | Başlıkta **`Geri alma:`** notu (SQL ya da "geri alınamaz, çünkü …") | Her değişikliğin geri dönüş yolu önceden yazılır (plan ilke 4) | test |
| 3 | Yıkıcı değişiklik (`DROP TABLE/COLUMN`, `RENAME`, tip değişimi, `TRUNCATE`) yalnızca **contract** adımında, `-- contract:` notuyla | Eski sürüm kodu hâlâ çalışırken kolon kaybolursa üretim düşer | test |
| 4 | Var olan tabloda indeks **`CREATE INDEX CONCURRENTLY`** (aynı dosyada yaratılan tablo hariç); kısa olduğu kanıtlıysa `-- lock-ok: <neden>` | Normal `CREATE INDEX` tabloya yazmayı indeks bitene kadar kilitler | test |
| 5 | `CONCURRENTLY` içeren dosyada **yalnızca** indeks ifadeleri; `SET` de yazılmaz (Flyway onu transaction'lı sayıp "mixed" hatası verir; `lock_timeout` zaten Flyway bağlantısında 5 sn) | Flyway böyle dosyayı transaction'sız çalıştırır; başka DDL yarıda kalırsa geri alınamaz | test + Flyway |
| 6 | Bir migrasyon yayınlandıktan sonra **değiştirilmez**; düzeltme yeni sürümle yapılır | Flyway checksum doğrulaması açılışı durdurur | Flyway |
| 7 | Uzun veri dönüşümü (milyonlarca satır `UPDATE`) migrasyonda **yapılmaz**; partili uygulama işi olarak yazılır | Tek dev transaction: uzun kilit, şişen WAL, vakum engeli | inceleme |

## 2. Kilit ve zaman aşımı (otomatik)

Flyway uygulama havuzundan **ayrı** bir bağlantıyla çalışır ve her bağlantıda şunu ayarlar
(`spring.flyway.init-sqls`, `application.yml`):

```sql
SET statement_timeout = 0;      -- uzun DDL'e (CONCURRENTLY) izin ver
SET lock_timeout = '5s';        -- kilit alamıyorsan hızlı düş (FLYWAY_LOCK_TIMEOUT)
```

`lock_timeout` neden önemli: `ALTER TABLE` gibi bir ifade ACCESS EXCLUSIVE kilidi **sıraya girerek** bekler;
beklerken arkasındaki **tüm** okuma/yazmalar da bekler. 5 sn sonra migrasyon düşer, backend açılmaz, eski
sürüm (rolling dağıtımda) ya da yeniden deneme devreye girer. Bu, sitenin dakikalarca donmasından iyidir.

Ayrıca `spring.flyway.postgresql.transactional-lock=false`: Flyway'in varsayılan transaction-düzeyi
danışma kilidi açık bir transaction tutar ve `CREATE INDEX CONCURRENTLY` bu transaction'ın bitmesini
bekleyerek **kendi kendini kilitler** (gerçek PG 18'de yeniden üretildi; `MigrationConventionsTest`).

Uygulama havuzu ise ayrı sınırlar taşır: `statement_timeout=30s`, `lock_timeout=10s`,
`idle_in_transaction_session_timeout=60s` (`docs/POSTGRES_TUNING.md`).

## 3. Expand → migrate → contract

Yıkıcı her değişiklik en az **iki dağıtıma** bölünür; aradaki her an eski ve yeni kod birlikte çalışabilir.

| Adım | Migrasyon | Kod |
|---|---|---|
| **1. Expand** | Yeni kolon/tablo **nullable** ya da varsayılanlı eklenir; yeni indeks `CONCURRENTLY` | Yeni koda çift yazma: eski + yeni kolon |
| **2. Migrate** | (Migrasyon değil) partili geri doldurma işi; ilerleme loglanır, idempotent | Okuma yeni kolondan, yoksa eskiden |
| **3. Doğrula** | Sayım/örneklem sorgusu; **yedek alınır** | — |
| **4. Contract** | `-- contract: V<expand sürümü> ile genişletildi; <tarih>'ten beri kod eskiyi okumuyor` + `DROP COLUMN` | Eski kolona hiçbir başvuru yok |

Örnek (kolon yeniden adlandırma `author` → `author_login`):
```sql
-- V12__debts_author_login_expand.sql
-- Geri alma: ALTER TABLE debts DROP COLUMN IF EXISTS author_login;
ALTER TABLE debts ADD COLUMN IF NOT EXISTS author_login VARCHAR(100);
```
```sql
-- V13__debts_author_login_index.sql   (yalnızca indeks; transaction'sız çalışır)
-- Geri alma: DROP INDEX CONCURRENTLY IF EXISTS idx_debts_author_login;
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_debts_author_login ON debts (author_login);
```
```sql
-- V15__debts_drop_author.sql
-- contract: V12'de genişletildi; 2026-11-01 sürümünden beri kod `author` okumuyor/yazmıyor.
-- Geri alma: geri alınamaz (veri silinir); gerekirse V12–V14 öncesi yedekten kolon geri yüklenir.
ALTER TABLE debts DROP COLUMN IF EXISTS author;
```

**Güvenli kabul edilenler (tek adım):** yeni tablo; nullable kolon; sabit varsayılanlı kolon (PG 11+ tabloyu
yeniden yazmaz); `CONCURRENTLY` indeks; `NOT VALID` ile eklenen `CHECK`/`FOREIGN KEY` + ayrı migrasyonda
`VALIDATE CONSTRAINT` (doğrulama yazmayı kilitlemez).

**Tehlikeli (adımlara böl):** `SET NOT NULL` (tam tablo taraması, kilitli) → önce `CHECK (x IS NOT NULL) NOT VALID`,
sonra `VALIDATE`, sonra `SET NOT NULL` (PG 12+ geçerli check'i kullanır); kolon tipi değişimi; `RENAME`;
uçucu varsayılanlı kolon (`DEFAULT now()` tabloyu yeniden yazmaz ama `DEFAULT random()` yazar).

## 4. Uygulama ve geri alma

```bash
# Dağıtımdan ÖNCE (migrasyon içeren her sürümde):
./scripts/backup-db.sh

# Yerel/CI'da yeni migrasyon gerçek PG'de denenir:
HASHMARK_TEST_DB_URL=... ./mvnw test        # tüm migrasyonlar her testte boş şemaya uygulanır

# Üretimde durum:
docker exec hashmark_postgres psql -U hashmark -d hashmark -c \
  "SELECT version, description, success, installed_on FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5"
```

**Migrasyon düştüyse** (`success = false` ya da backend açılmıyor):
1. Log: `docker compose logs backend | grep -i -A5 flyway`. `55P03` / *lock timeout* → kilidi tutan oturumu bul:
   `SELECT pid, state, xact_start, left(query,80) FROM pg_stat_activity WHERE state <> 'idle' ORDER BY xact_start;`
2. Transaction'lı migrasyon düştüyse PG her şeyi geri almıştır; nedeni gider, yeniden başlat.
3. `CONCURRENTLY` yarıda kaldıysa geçersiz (`INVALID`) indeks kalabilir:
   `SELECT indexrelid::regclass FROM pg_index WHERE NOT indisvalid;` → `DROP INDEX CONCURRENTLY <ad>;` → yeniden başlat.
4. Başarısız satırı Flyway geçmişinden temizlemek gerekirse (yalnızca durum anlaşıldıktan sonra):
   `DELETE FROM flyway_schema_history WHERE success = false;`
5. Kod geri alınacaksa: önceki imaja dön (`DEPLOYMENT.md` → Rollback). Expand adımları geriye uyumlu olduğu için
   eski kod yeni şemayla çalışır; contract adımı geri alınamaz, bu yüzden en son ve yedekten sonra yapılır.

## 5. Ayrı migrasyon rolü (hazırlık)

`FLYWAY_USER` / `FLYWAY_PASSWORD` verilirse Flyway bu kullanıcıyla, uygulama `DATABASE_USERNAME` ile bağlanır.
Boşsa ikisi de aynı kullanıcıdır (bugünkü durum). Row-Level Security (plan 5.5) için uygulama kullanıcısının
tablo sahibi **olmaması** gerekir; geçiş o dilimde yapılacak.
