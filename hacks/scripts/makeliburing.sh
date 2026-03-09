#!/usr/bin/env bash
set -euo pipefail

if ! command -v pkg-config >/dev/null 2>&1; then
    echo "pkg-config is required to locate system liburing" >&2
    exit 1
fi

if ! pkg-config --exists liburing; then
    echo "system liburing not found; install the liburing development package first" >&2
    exit 1
fi

pkg-config --cflags --libs liburing
