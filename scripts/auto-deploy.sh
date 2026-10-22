#!/usr/bin/env bash
# ============================================================================
# Hashmark otomatik dağıtım (sunucu tarafı)
#
# GitHub Actions (.github/workflows/deploy.yml) master'da CI yeşil olunca SSH ile bağlanır. Bağlandığı anahtar
# ~deploy/.ssh/authorized_keys içinde şöyle kısıtlıdır, yani bu betikten başka hiçbir şey çalıştıramaz:
#
#   restrict,command="/opt/hashmark/bin/hashmark-deploy" ssh-ed25519 AAAA... github-actions-hashmark-deploy
#
# İstenen komut SSH_ORIGINAL_COMMAND'dan okunur ve beyaz listeyle doğrulanır:
#   deploy backend|frontend <40 karakterlik commit>   → o bileşeni origin/master'a getirir
#   status                                            → çalışan commit'ler ve konteyner sağlığı
#
# Akış: kilit → doğrulama → (frontend: bekleyen backend dağıtımını bekle) → yedek (.env, backend'de veritabanı)
#       → git merge --ff-only → docker compose build → up -d → sağlık → başarısızsa önceki commit'e dönüş.
#
# Kurulum / güncelleme (betik depodan değil, kurulu kopyadan çalışır; dağıtım sırasında kendini değiştirmesin):
#   install -m 0755 /opt/hashmark/hashmark-backend/scripts/auto-deploy.sh /opt/hashmark/bin/hashmark-deploy
#
# Migrasyonlar genişletme kuralına uyar (docs/MIGRATIONS.md): geri dönüşte önceki kod yeni şemayla çalışır,
# şema geri alınmaz.
#
# GÜVENLİK: `set -x` KULLANMAYIN — .env değerleri loga düşer.
# ============================================================================
set -euo pipefail
umask 077

ROOT=/opt/hashmark
LOG_DIR="$HOME/deploy-logs"
BACKUP_DIR="$HOME/deploy-backups"
LOCK_FILE="$HOME/.hashmark-deploy.lock"
KEEP_AUTO_BACKUPS=20
HEALTH_TIMEOUT=300
# Frontend, aynı anda birleşen backend değişikliği dağıtılmadan yayına çıkmasın (yeni uçları kullanabilir)
BACKEND_WAIT_TIMEOUT=1500

die() { echo "HATA: $*" >&2; exit 1; }

REQUEST="${SSH_ORIGINAL_COMMAND:-$*}"
read -r ACTION COMPONENT SHA EXTRA <<<"$REQUEST" || true

status() {
  for c in backend frontend; do
    echo "$c: $(git -C "$ROOT/hashmark-$c" log --oneline -1) (health: $(docker inspect -f '{{.State.Health.Status}}' "hashmark_$c" 2>/dev/null || echo yok))"
  done
}

case "${ACTION:-}" in
  status) [ -z "${COMPONENT:-}" ] || die "status argüman almaz"; status; exit 0 ;;
  deploy) ;;
  *) die "bilinmeyen komut (izinli: 'deploy backend|frontend <sha>', 'status')" ;;
esac
[[ "${COMPONENT:-}" =~ ^(backend|frontend)$ ]] || die "bileşen backend ya da frontend olmalı"
[[ "${SHA:-}" =~ ^[0-9a-f]{40}$ ]] || die "commit 40 karakterlik SHA olmalı"
[ -z "${EXTRA:-}" ] || die "fazla argüman"

DIR="$ROOT/hashmark-$COMPONENT"
CONTAINER="hashmark_$COMPONENT"
TS=$(date +%Y%m%d-%H%M%S)
mkdir -p "$LOG_DIR" "$BACKUP_DIR"
LOG="$LOG_DIR/$TS-$COMPONENT.log"
exec > >(tee -a "$LOG") 2>&1
echo "== $(date -Is) dağıtım isteği: $COMPONENT $SHA"

wait_healthy() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT)) s
  while [ $SECONDS -lt $deadline ]; do
    s=$(docker inspect -f '{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null || echo yok)
    [ "$s" = healthy ] && return 0
    sleep 5
  done
  echo "sağlık: $s"
  return 1
}

# Frontend: backend'de dağıtılmamış yeni bir master commit'i varsa (CI'ı sürüyor) kilit ALINMADAN beklenir
if [ "$COMPONENT" = frontend ]; then
  deadline=$((SECONDS + BACKEND_WAIT_TIMEOUT))
  while :; do
    # Aynı anda süren backend dağıtımının fetch'iyle ref kilidi çakışırsa bir sonraki turda denenir
    git -C "$ROOT/hashmark-backend" fetch -q origin master || true
    [ "$(git -C "$ROOT/hashmark-backend" rev-parse HEAD)" = "$(git -C "$ROOT/hashmark-backend" rev-parse origin/master)" ] && break
    [ $SECONDS -lt $deadline ] || die "backend master'ı $((BACKEND_WAIT_TIMEOUT / 60)) dk içinde dağıtılmadı (CI kırık olabilir); frontend dağıtımı durduruldu"
    echo "backend'de dağıtılmamış commit var, bekleniyor..."
    sleep 30
  done
fi

exec 9>"$LOCK_FILE"
flock -w 1800 9 || die "başka bir dağıtım 30 dk'dır sürüyor"

cd "$DIR"
[ -z "$(git status --porcelain)" ] || die "$DIR çalışma ağacında commit'lenmemiş değişiklik var; elle bakılmalı"
[ "$(git branch --show-current)" = master ] || die "$DIR master dalında değil"
git fetch -q origin master
TARGET=$(git rev-parse origin/master)
PREV=$(git rev-parse HEAD)

# Yalnızca master'daki commit'ler: istenen commit origin/master'ın atası (ya da kendisi) olmalı
git merge-base --is-ancestor "$SHA" "$TARGET" 2>/dev/null || die "istenen commit origin/master'da değil"
if git merge-base --is-ancestor "$TARGET" "$PREV"; then
  echo "zaten güncel: $(git log --oneline -1)"
  exit 0
fi
[ "$SHA" = "$TARGET" ] || echo "not: master ilerlemiş, en son commit dağıtılıyor ($TARGET)"

# Yedek: .env + önceki commit; backend'de veritabanı dökümü
B="$BACKUP_DIR/$TS-auto-$COMPONENT"
mkdir -p "$B"
cp .env "$B/$COMPONENT.env"
echo "$PREV" > "$B/previous-commit"
if [ "$COMPONENT" = backend ]; then
  bash scripts/backup-db.sh > "$B/backup.log" 2>&1 || die "veritabanı yedeği alınamadı (bkz. $B/backup.log)"
  latest=$(ls -t backups/*.sql.gz 2>/dev/null | head -1 || true)
  [ -n "$latest" ] && cp "$latest" "$B/"
fi
# Eski otomatik yedekleri buda (elle alınanlara dokunma)
ls -dt "$BACKUP_DIR"/*-auto-* 2>/dev/null | tail -n +$((KEEP_AUTO_BACKUPS + 1)) | xargs -r rm -rf
echo "yedek: $B"

echo "commit'ler:"
git log --oneline "$PREV..$TARGET"
git merge --ff-only -q "$TARGET"

deploy_current() {
  docker compose build "$COMPONENT" && docker compose up -d "$COMPONENT" && wait_healthy
}

if deploy_current; then
  echo "== BAŞARILI: $COMPONENT $(git log --oneline -1)"
  exit 0
fi

echo "== Yeni sürüm sağlıklı açılmadı; son loglar:"
docker logs --tail 40 "$CONTAINER" 2>&1 | grep -v -iE 'password|secret|token' || true
echo "== Geri dönülüyor: $PREV"
git reset -q --hard "$PREV"
if deploy_current; then
  die "dağıtım başarısız, önceki sürüme dönüldü ($(git log --oneline -1))"
fi
die "dağıtım başarısız VE geri dönüş sağlıklı açılmadı — elle müdahale gerekli (yedek: $B)"
