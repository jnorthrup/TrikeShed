#!/usr/bin/env bash
#
# Creates measurements.txt with the specified number of rows.
# Usage: ./brc/create_measurements.sh [num_rows]
#
# Mirrors the behavior of gunnarmorling/1brc create_measurements.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
NUM_ROWS="${1:-1000000}"
OUTPUT_FILE="${BRC_FILE:-${PROJECT_DIR}/measurements.txt}"
STATIONS_FILE="${SCRIPT_DIR}/data/weather_stations.csv"

echo "Creating ${NUM_ROWS} measurements in ${OUTPUT_FILE}..."

# Read station names into array
mapfile -t STATIONS < "${STATIONS_FILE}"
NUM_STATIONS=${#STATIONS[@]}

# Use awk for fast generation — much faster than bash loops
awk -v n="${NUM_ROWS}" -v ns="${NUM_STATIONS}" '
BEGIN {
    srand()
    # Read station names
}
{
    stations[NR] = $0
}
END {
    for (i = 0; i < n; i++) {
        idx = int(rand() * ns) + 1
        # Temperature: -99.9 to 99.9 with one decimal
        temp = int(rand() * 1999) - 999
        if (temp < 0) {
            sign = "-"
            temp = -temp
        } else {
            sign = ""
        }
        intpart = int(temp / 10)
        decpart = temp % 10
        printf "%s;%s%d.%d\n", stations[idx], sign, intpart, decpart
    }
}
' "${STATIONS_FILE}"  > "${OUTPUT_FILE}"

SIZE=$(wc -c < "${OUTPUT_FILE}" | tr -d ' ')
echo "Done. ${OUTPUT_FILE}: ${SIZE} bytes ($(echo "scale=2; ${SIZE} / 1073741824" | bc) GiB)"
