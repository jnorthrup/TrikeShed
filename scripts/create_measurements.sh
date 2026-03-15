#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."

MEASUREMENTS_FILE="${MEASUREMENTS_FILE:-/tmp/measurements.txt}"
TARGET_SIZE_GB=${TARGET_SIZE_GB:-14}

echo "Creating $TARGET_SIZE_GB GB measurements file at $MEASUREMENTS_FILE"
TARGET_LINES=$((TARGET_SIZE_GB * 1000000000 / 21))

SECONDS=0

awk -v target="$TARGET_LINES" 'BEGIN {
    srand(42)
    for (i = 0; i < target; i++) {
        station_num = int(rand() * 413) + 1
        temp_int = int(rand() * 1000) - 500
        temp_dec = int(rand() * 10)
        printf "Station_%03d;%d.%d\n", station_num, temp_int, temp_dec
    }
}' > "$MEASUREMENTS_FILE"

final_size=$(stat -f %z "$MEASUREMENTS_FILE" 2>/dev/null || stat -c %s "$MEASUREMENTS_FILE" 2>/dev/null || echo 0)
lines=$(wc -l < "$MEASUREMENTS_FILE" 2>/dev/null || echo '?')
printf "Created %s (%.2f GB, %s lines) in %d seconds\n" \
    "$MEASUREMENTS_FILE" \
    "$(echo "scale=2; $final_size / 1000000000" | bc 2>/dev/null || awk "BEGIN {printf \"%.2f\", $final_size / 1000000000}")" \
    "$lines" \
    "$SECONDS"