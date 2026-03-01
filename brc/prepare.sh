#!/usr/bin/env bash
#
# Prepare step: build the JVM jar containing all variant implementations.
# Called once before running any variant.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${PROJECT_DIR}"

echo "Building TrikeShed JVM jar..."
./gradlew jvmJar --quiet 2>&1

echo "Build complete."
