# İzleme (Prometheus + Alertmanager)

Uygulama yığınından bağımsız bir compose dosyasıdır; backend'e dokunmadan açılıp kapanır.

## Kurulum (sunucuda)
```bash
# 1) Uygulama yığını ayakta olmalı (hashmark-network oluşur)
docker compose up -d
# 2) Alarm kanalı: Telegram botu (@BotFather) -> jetonu dosyaya yaz, chat_id'yi alertmanager.yml'e yaz
echo -n "<BOT_TOKEN>" > monitoring/secrets/telegram_token && chmod 600 monitoring/secrets/telegram_token
$EDITOR monitoring/alertmanager.yml          # chat_id: 1 yer tutucusunu değiştir (ZORUNLU)
# 3) Yapılandırmayı doğrula (promtool + alarm birim testleri + amtool)
./scripts/check-monitoring.sh
# 4) Başlat
docker compose -f monitoring/docker-compose.monitoring.yml up -d
# 5) Yedek metriği için cron (backup-db.sh her çalışmada backups/metrics/hashmark_backup.prom yazar)
```

## Ne nereden gelir
| Veri | Kaynak | Not |
|---|---|---|
| Uygulama metrikleri | `backend:8081/actuator/prometheus` | **Yönetim portu**; nginx ve compose host'a açmaz. `:8080/actuator/*` kimlik ister. |
| Sağlık | `backend:8081/actuator/health` | Docker healthcheck bunu kullanır |
| Uptime / TLS süresi | `blackbox-https` işi | Aynı sunucudan bakar: **sunucu tamamen düşerse bu da düşer** → ayrıca dış servis kullanın (aşağıda) |
| Disk, yedek yaşı | `node-exporter` + textfile | `backups/metrics/hashmark_backup.prom` |

### Uygulama metrikleri
| Metrik | Anlamı |
|---|---|
| `http_server_requests_seconds_*` | Uç bazında süre histogramı (`uri`, `status`, `method`) |
| `hikaricp_connections_{active,pending,max,timeout_total}` , `hikaricp_connections_acquire_seconds_*` | DB havuzu |
| `jvm_memory_*`, `jvm_gc_*`, `jvm_threads_*`, `process_cpu_usage` | JVM |
| `hashmark_scan_queue_pending`, `hashmark_scan_queue_oldest_age_seconds`, `hashmark_scan_running` | Tarama kuyruğu |
| `hashmark_scan_duration_seconds{result=done\|failed}` | Tarama süresi |
| `hashmark_github_ratelimit_remaining` | GitHub'ın bildirdiği son kalan hak (-1: henüz yok) |
| `hashmark_ratelimit_rejected_total{scope=auth\|scan}` | Uygulama hız sınırının 429'ları |
| `hashmark_auth_refresh_reuse_detected_total` | Refresh token yeniden kullanımı |

Etiketlerde kullanıcı kimliği, depo adı, token veya içerik **yoktur** (kardinalite + gizlilik).

## Alarmlar (`alerts.yml`)
`BackendDown`, `PublicEndpointDown`, `TlsCertificateExpiringSoon`, `HighServerErrorRate` (5xx > %2, 5 dk),
`HighLatencyP95` (> 1 sn, 10 dk), `DbPoolWaiting`, `DbPoolTimeouts`, `ScanQueueBacklog` (> 10 dk),
`JvmHeapHigh`, `GithubRateLimitLow`, `RefreshTokenReuseDetected`, `RateLimitRejectionsSpike`,
`DiskUsageHigh` (> %80), `BackupFailed`, `BackupStale` (> 26 sa), `BackupMetricMissing`.
Kural değiştirince `./scripts/check-monitoring.sh` çalıştırın; `alerts.test.yml` birim testlerini de koşar.

## Dış izleme (elle yapılacak)
Sunucu dışından bakan bir uptime servisi (UptimeRobot, Better Stack vb.) ekleyin:
`https://hashmark.yunusemremahir.com.tr/` ve `/api/v1/public/stats`, 1–5 dk aralık, e-posta/Telegram bildirimi.

## Hata toplama (Sentry)
`SENTRY_DSN` boşsa kapalıdır. Doldurulursa yakalanmamış hatalar ve `ERROR` loglar gönderilir;
`send-default-pii=false` (IP, başlık, gövde gönderilmez). Self-host için GlitchTip (Sentry uyumlu) kullanılabilir.

## Log arama
Loglar tek satır JSON'dur; her isteğin `requestId` alanı vardır ve yanıtta `X-Request-Id` başlığı olarak döner.
```bash
docker logs hashmark_backend 2>&1 | grep '"requestId":"<id>"'
```
Yerelde okunaklı çıktı için `SPRING_PROFILES_ACTIVE=text-logs`.
