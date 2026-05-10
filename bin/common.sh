#!/usr/bin/env bash
# TrikeShed — shared shell functions
set -euo pipefail

TRIKESHED_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$TRIKESHED_ROOT/.gradle-home}"

# Resolve the Gradle wrapper or system gradle
find_gradle() {
    if command -v gradle &>/dev/null; then
        echo "gradle"
    elif [ -f "$TRIKESHED_ROOT/gradlew" ]; then
        echo "$TRIKESHED_ROOT/gradlew"
    else
        echo "ERROR: gradle not found. Install gradle or run from TrikeShed root." >&2
        exit 1
    fi
}

# Resolve JVM classpath for a given gradle module
classpath_for() {
    local module="$1"
    "$(find_gradle)" -p "$TRIKESHED_ROOT" --no-daemon \
        -q dependencies --configuration jvmRuntimeClasspath 2>/dev/null \
        | grep -o '[^ ]\+\.jar' | tr '\n' ':' || true
}

# Run a Kotlin main class via Gradle
run_main() {
    local module="$1"
    local mainclass="$2"
    shift 2
    GRADLE_USER_HOME="$GRADLE_USER_HOME" \
        "$(find_gradle)" -p "$TRIKESHED_ROOT" --no-daemon \
        "$module:run" \
        -DmainClass="$mainclass" \
        --args="$*" 2>&1
}
