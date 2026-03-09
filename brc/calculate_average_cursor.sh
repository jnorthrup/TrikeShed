#!/usr/bin/env bash
#
# 1BRC Variant: Cursor (JVM)
#
# Uses TrikeShed's Cursor and Series operators (j, α) to parse
# and aggregate the measurements file.
#
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
BRC_FILE="${BRC_FILE:-${PROJECT_DIR}/measurements.txt}"

CLASSPATH=$("${SCRIPT_DIR}/classpath.sh")

exec java -cp "${CLASSPATH}" borg.trikeshed.brc.BrcCursor "${BRC_FILE}"
