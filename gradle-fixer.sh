#!/usr/bin/env bash
set -euo pipefail
shopt -s globstar || true
LOG="gradle-fixer.log"
MAX_CYCLES=1000
COUNT=0
printf "=== gradle-fixer start %s ===\n" "$(date '+%Y-%m-%d %H:%M:%S%z')" >> "$LOG"

while [ $COUNT -lt $MAX_CYCLES ]; do
  COUNT=$((COUNT+1))
  printf "=== Cycle %d %s ===\n" "$COUNT" "$(date '+%Y-%m-%d %H:%M:%S%z')" >> "$LOG"
  echo "Running ./gradlew test (cycle $COUNT)" >> "$LOG"
  if ./gradlew test --no-daemon --console=plain >> "$LOG" 2>&1; then
    echo "BUILD SUCCESS" >> "$LOG"
    sleep 30
    continue
  else
    echo "BUILD FAILURE" >> "$LOG"
    PARSED=".gradle-fixer-parsed"
    rm -f "$PARSED"
    for f in build/test-results/**/TEST-*.xml; do
      [ -f "$f" ] || continue
      awk 'BEGIN{RS="</testcase>"} /<failure/ {
        classname=""; name="";
        if (match($0, /<testcase[^>]*classname=\"([^\"]+)\"/,m)) classname=m[1];
        if (match($0, /<testcase[^>]*name=\"([^\"]+)\"/,m)) name=m[1];
        print FILENAME "|" classname "|" name;
      }' "$f" >> "$PARSED" || true
    done

    if [ -f "$PARSED" ] && [ -s "$PARSED" ]; then
      LAST=$(tail -n 1 "$PARSED")
      echo "Failing test: $LAST" >> "$LOG"
      IFS='|' read -r xmlfile failing_class failing_method <<< "$LAST"
      echo "Running single test to gather details: ${failing_class}.${failing_method}" >> "$LOG"
      ./gradlew test --no-daemon --console=plain --tests "${failing_class}.${failing_method}" --info --stacktrace >> "$LOG" 2>&1 || true
    else
      echo "No XML failure entries found; dumped console output above." >> "$LOG"
    fi

    echo "Stopping monitor for human review" >> "$LOG"
    exit 0
  fi
done

echo "Reached max cycles ($MAX_CYCLES); exiting" >> "$LOG"
