#!/bin/bash
# Runs the unit tests plus the S3 integration test against a local `rclone serve s3` (checks SigV4 signatures).
# Usage: scripts/test-s3.sh   (needs rclone and gradle on PATH)
set -euo pipefail
cd "$(dirname "$0")/.."
DATA="$PWD/s3data"; PORT=${BB_S3_PORT:-19555}
rm -rf "$DATA"; mkdir -p "$DATA/bb-it"
export BB_IT_ACCESS=testaccess BB_IT_SECRET=testsecret-0123456789 BB_IT_BUCKET=bb-it
rclone serve s3 --auth-key "$BB_IT_ACCESS,$BB_IT_SECRET" --addr "127.0.0.1:$PORT" "$DATA" >s3data.log 2>&1 &
RC=$!
trap 'kill $RC 2>/dev/null || true; wait $RC 2>/dev/null || true; rm -rf "$DATA" s3data.log' EXIT
for i in $(seq 1 50); do curl -s -o /dev/null "http://127.0.0.1:$PORT/" && break; sleep 0.2; done
BB_IT_ENDPOINT="http://127.0.0.1:$PORT" gradle --no-daemon -q test jar "$@"
echo "tests ok"
