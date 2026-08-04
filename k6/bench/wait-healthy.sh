#!/usr/bin/env bash
set -euo pipefail

URL="${1:?usage: wait-healthy.sh <health-url> [timeout-seconds]}"
TIMEOUT="${2:-180}"
START=$(date +%s)
DEADLINE=$((START + TIMEOUT))

until curl -sf "$URL" 2>/dev/null | grep -q '"status":"UP"'; do
    if [ "$(date +%s)" -ge "$DEADLINE" ]; then
        echo "[health] TIMEOUT after ${TIMEOUT}s waiting for $URL" >&2
        exit 1
    fi
    sleep 2
done

echo "[health] UP after $(($(date +%s) - START))s" >&2
