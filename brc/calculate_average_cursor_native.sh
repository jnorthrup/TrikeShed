#!/usr/bin/env bash
#
# 1BRC Variant: Cursor Native (macOS/Linux)
#
# Uses TrikeShed's Series j operator for lazy construction.
#
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
BRC_FILE="${BRC_FILE:-${PROJECT_DIR}/measurements.txt}"

# Build native binary if needed
BUILD_DIR="${PROJECT_DIR}/build/bin"
if [[ "$(uname)" == "Darwin" ]]; then
    if [[ "$(uname -m)" == "arm64" ]]; then
        NATIVE_BIN="${BUILD_DIR}/macos/arm64/releaseExecutable/brcCursorNative.kexe"
    else
        NATIVE_BIN="${BUILD_DIR}/macos/x64/releaseExecutable/brcCursorNative.kexe"
    fi
else
    NATIVE_BIN="${BUILD_DIR}/linux/x64/releaseExecutable/brcCursorNative.kexe"
fi

if [[ ! -f "${NATIVE_BIN}" ]]; then
    echo "Building native cursor binary..." >&2
    cd "${PROJECT_DIR}"
    ./gradlew brcCursorNativeReleaseExecutableMacos --quiet 2>/dev/null || \
    ./gradlew brcCursorNativeReleaseExecutableLinux --quiet 2>/dev/null || true
fi

if [[ -f "${NATIVE_BIN}" ]]; then
    "${NATIVE_BIN}" "${BRC_FILE}"
else
    echo "ERROR: Native binary not found at ${NATIVE_BIN}" >&2
    exit 1
fi
