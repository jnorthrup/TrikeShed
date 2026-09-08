#!/usr/bin/env bash
# Build only against an existing Linux JDK and system liburing. No installation or downloads.
set -euo pipefail
if [[ "$(uname -s)" != Linux ]]; then
    echo 'The JNI io_uring adapter must be built on Linux.' >&2
    exit 1
fi
uring_root="$(cd "$(dirname "$0")/.." && pwd)"
uring_jdk="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
uring_output="${1:-$uring_root/build/uring}"
mkdir -p "$uring_output"
"${CC:-cc}" -O2 -fPIC -shared -Wall -Wextra -Werror \
    -I"$uring_jdk/include" -I"$uring_jdk/include/linux" \
    "$uring_root/src/jvmMain/c/uring_jni.c" -luring \
    -o "$uring_output/libtrikeshed_uring.so"
echo "Built $uring_output/libtrikeshed_uring.so; run Java with -Djava.library.path=$uring_output"
