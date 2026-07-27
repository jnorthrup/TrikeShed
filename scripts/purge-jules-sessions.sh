#!/usr/bin/env bash
# Purge all Jules sessions for this repo's source.
# Usage: scripts/purge-jules-sessions.sh
set -euo pipefail

: "${JULES_API_KEY:?JULES_API_KEY required}"
SOURCE="sources/github/jnorthrup/TrikeShed"
BASE="https://jules.googleapis.com/v1alpha"

# Collect session names (full path) matching this source.
# Jules API uses x-goog-api-key header, not OAuth Bearer.
SESSIONS=$(curl -sf -H "x-goog-api-key: ${JULES_API_KEY}" \
  "${BASE}/sessions?pageSize=100" | \
  jq -r --arg src "$SOURCE" \
  '.sessions[] | select((.sourceContext.source // "") == $src) | .name')

if [ -z "$SESSIONS" ]; then
  echo "No sessions found for $SOURCE"
  exit 0
fi

COUNT=$(echo "$SESSIONS" | wc -l | tr -d ' ')
echo "Purging $COUNT sessions for $SOURCE"

echo "$SESSIONS" | while read -r name; do
  SID=$(basename "$name")
  CODE=$(curl -s -o /dev/null -w '%{http_code}' \
    -X DELETE \
    -H "x-goog-api-key: ${JULES_API_KEY}" \
    "${BASE}/sessions/${SID}")
  if [ "$CODE" = "200" ] || [ "$CODE" = "204" ]; then
    echo "  deleted $SID"
  else
    echo "  FAIL $CODE on $SID"
  fi
done

echo "Done."
