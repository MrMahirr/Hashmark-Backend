# Yük testi

```bash
./loadtest/run.sh dashboard            # 200 kullanıcı dashboard gezinmesi
./loadtest/run.sh login                # 500 eşzamanlı giriş patlaması (sahte GitHub, 150 ms gecikme)
./loadtest/run.sh scan                 # 50 kullanıcı x 3 depo POST /scan (yalnızca kuyruğa alma)
./loadtest/run.sh explain              # 300.000 satırlık veriyle EXPLAIN (ANALYZE, BUFFERS)
BACKEND_CPUS=2 VUS=100 DURATION=90s ./loadtest/run.sh dashboard
```
Docker ve internet (imajlar için) gerekir. Her koşu kendi ağını/konteynerlerini kurar ve siler; mevcut konteynerlere dokunmaz.
Sonuçlar `loadtest/results/` altına yazılır (git'e girmez; özetler Hashmark-Vault'ta tutulur).

## Neyi ölçer, neyi ölçmez
- Backend 1–2 CPU / 1 GB, Postgres 1 CPU / 512 MB sınırlarıyla **küçük bir VDS'i taklit eder**; sayılar gerçek VDS değildir.
- Giriş: sahte GitHub (gerçek GitHub'a yük bindirilmez). Hız sınırları testte yükseltilir (gerçek patlama çok sayıda IP'den gelir).
- `scan`: tarama işçileri kapalıdır (`SCAN_MAX_CONCURRENT=0`); tarball indirme/ayrıştırma maliyeti **ölçülmez**.
- Nginx yolda değil; nginx `limit_req` ve TLS maliyeti hariçtir.
- **Üretimde çalıştırmayın.**

## Dosyalar
`run.sh` (orkestrasyon) · `k6.js` (senaryolar) · `mock-github.js` · `seed.sql` · `explain.sql`
