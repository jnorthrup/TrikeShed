#!/usr/bin/env bash
#
# Prints the JVM runtime classpath for TrikeShed.
# Caches the result in build/.brc_classpath.
#
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
CACHE="${PROJECT_DIR}/build/.brc_classpath"

if [[ ! -f "${CACHE}" ]] || [[ "${PROJECT_DIR}/build.gradle.kts" -nt "${CACHE}" ]]; then
    cd "${PROJECT_DIR}"
    # Use --console=plain and grep to extract just the classpath line
    CP=$(./gradlew printJvmClasspath --console=plain -q 2>/dev/null | grep '\.jar' | tail -1)
    if [[ -z "${CP}" ]]; then
        echo "ERROR: Failed to resolve classpath" >&2
        exit 1
    fi
    echo "${CP}" > "${CACHE}"
fi

cat "${CACHE}"
