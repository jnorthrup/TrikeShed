#!/usr/bin/env bash
#
# 1BRC Variant: ISAM/Native (macOS ARM64)
#
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
BRC_FILE="${BRC_FILE:-${PROJECT_DIR}/measurements.txt}"

BINARY="${PROJECT_DIR}/build/bin/macos/brcIsamNativeReleaseExecutable/brcIsamNative.kexe"

if [[ ! -f "${BINARY}" ]]; then
    echo "ERROR: native binary not found: ${BINARY}" >&2
    echo "Run: ./gradlew linkBrcIsamNativeReleaseExecutableMacos" >&2
    exit 1
fi

exec "${BINARY}" "${BRC_FILE}"
