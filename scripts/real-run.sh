#!/bin/bash
# Real-run test: official Paper jar + this plugin + a local S3 server (rclone serve s3, checks SigV4).
# Starts a headless server, runs the commands, waits for the scheduled startup backup, stops, checks the zips,
# then deletes the worlds. Usage: scripts/real-run.sh <paper-version> <build> <sha256>   (needs JDK 25, rclone)
set -euo pipefail
cd "$(dirname "$0")/.."
VER=${1:-26.3}; BUILD=${2:-244}; SHA=${3:-9601f9a26a2a52a0f2c807cf1abf691f96ee4fb287878313b601cebc2c51eabf}
JAVA=${JAVA:-/opt/homebrew/opt/openjdk@25/bin/java}
RUN="$PWD/run"; S3="$RUN/s3data"; LOG="$RUN/console.log"; PORT=19556
rm -rf "$S3" "$RUN"/world* "$LOG" "$RUN/commands.log"
mkdir -p "$RUN/plugins/BucketBackup" "$S3/mc-backups"
JAR="$RUN/paper-$VER-$BUILD.jar"
if [ ! -f "$JAR" ]; then
  curl -fsSL -A "zovo-bucketbackup-test/1.0" -o "$JAR" \
    "https://fill-data.papermc.io/v1/objects/$SHA/paper-$VER-$BUILD.jar"
fi
echo "$SHA  $JAR" | shasum -a 256 -c -
cp build/libs/BucketBackup-*.jar "$RUN/plugins/"
cat > "$RUN/server.properties" <<P
server-port=25599
online-mode=false
level-seed=20261106
view-distance=4
simulation-distance=4
motd=BucketBackup test
P
cat > "$RUN/plugins/BucketBackup/config.yml" <<C
storage:
  endpoint: http://127.0.0.1:$PORT
  region: us-east-1
  bucket: mc-backups
  access-key: ''
  secret-key: ''
  path-style: auto
backup:
  interval-minutes: 5
  on-startup: true
  worlds: ['*']
  extra-paths: [server.properties, whitelist.json, ops.json]
  exclude: ['**/*.tmp']
  compression-level: 6
  prefix: minecraft/test-server
  keep-local-copies: 0
retention:
  keep-last: 1
  max-age-days: 0
upload:
  part-size-mb: 5
notify:
  discord-webhook: ''
  when: failure
C
export BUCKETBACKUP_ACCESS_KEY=runaccess BUCKETBACKUP_SECRET_KEY=runsecret-0123456789
rclone serve s3 --auth-key "$BUCKETBACKUP_ACCESS_KEY,$BUCKETBACKUP_SECRET_KEY" --addr "127.0.0.1:$PORT" "$S3" >"$RUN/s3.log" 2>&1 &
RC=$!
FIFO="$RUN/stdin"; rm -f "$FIFO"; mkfifo "$FIFO"
( cd "$RUN" && exec "$JAVA" -Xms512M -Xmx1536M -Dcom.mojang.eula.agree=true -jar "$JAR" --nogui <"$FIFO" >"$LOG" 2>&1 ) &
SV=$!
exec 3>"$FIFO"
cleanup() { kill $SV 2>/dev/null || true; kill $RC 2>/dev/null || true; exec 3>&- 2>/dev/null || true; }
trap cleanup EXIT
waitfor() { for i in $(seq 1 "$2"); do grep -q "$1" "$LOG" 2>/dev/null && return 0; sleep 1; done; echo "TIMEOUT waiting for $1"; tail -30 "$LOG"; exit 1; }
cmd() { echo ">>> $1" >>"$RUN/commands.log"; echo "$1" >&3; }
waitfor 'Done (' 300
cmd "bucketbackup status"; sleep 2
cmd "bucketbackup test"; waitfor 'Connection OK' 30
cmd "bucketbackup now"; waitfor 'Backup done' 120
# Two commands in the same tick: the second must be refused while the first runs.
echo "bucketbackup now" >&3; echo "bucketbackup now" >&3
waitfor 'Retention removed' 120
sleep 3
grep -q 'already running' "$LOG" && echo "concurrency guard: refused the second backup" || echo "concurrency guard: not triggered (first backup finished within one tick)"
cmd "bucketbackup list"; sleep 3
waitfor 'Backup started (schedule)' 90
for i in $(seq 1 120); do [ "$(grep -c 'Backup done' "$LOG")" -ge 3 ] && break; sleep 1; done
cmd "bucketbackup status"; sleep 2
cmd "stop"
for i in $(seq 1 60); do kill -0 $SV 2>/dev/null || break; sleep 1; done
echo "server stopped"
python3 -I scripts/check-zips.py "$S3/mc-backups/minecraft/test-server"
if [ -n "${EVIDENCE:-}" ]; then mkdir -p "$EVIDENCE"; cp "$LOG" "$RUN/commands.log" "$EVIDENCE/"; fi
if [ -z "${KEEP:-}" ]; then rm -rf "$RUN"/world* "$S3" "$FIFO"; echo "worlds and S3 data deleted"; fi
