#!/usr/bin/env bash
# Build the JNI binding against a supplied prefix or the checksum-pinned liburing 2.15 release.
set -euo pipefail
if [[ "$(uname -s)" != Linux ]]; then
    echo 'The JNI io_uring adapter must be built on Linux.' >&2
    exit 1
fi
uring_root="$(cd "$(dirname "$0")/.." && pwd)"
uring_jdk="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
uring_output="${1:-$uring_root/build/uring}"
mkdir -p "$uring_output"
uring_output="$(cd "$uring_output" && pwd)"
uring_prefix="${LIBURING_PREFIX:-}"
if [[ -z "$uring_prefix" ]]; then
    uring_dependency="${LIBURING_BUILD_ROOT:-$uring_root/build/liburing-2.15-$(uname -m)}"
    mkdir -p "$uring_dependency"
    uring_dependency="$(cd "$uring_dependency" && pwd)"
    uring_prefix="$uring_dependency/install"
    if [[ ! -f "$uring_prefix/include/liburing.h" || ! -f "$uring_prefix/lib/liburing.so" ]]; then
        uring_archive="$uring_dependency/liburing-2.15.tar.gz"
        if [[ ! -f "$uring_archive" ]]; then
            curl --fail --location --max-time 60 --retry 2 \
                https://codeload.github.com/axboe/liburing/tar.gz/refs/tags/liburing-2.15 \
                -o "$uring_archive"
        fi
        printf '%s  %s\n' \
            '8d052f2622dcb3678cbaee5ff582a87572672a6c0a56533cdda5b65cb636120a' \
            "$uring_archive" | sha256sum --check -
        tar -xzf "$uring_archive" -C "$uring_dependency"
        uring_source="$uring_dependency/liburing-liburing-2.15"
        (cd "$uring_source" && ./configure --prefix="$uring_prefix")
        make -C "$uring_source" -j"${LIBURING_BUILD_JOBS:-2}" library
        make -C "$uring_source" install
    fi
fi
uring_prefix="$(cd "$uring_prefix" && pwd)"
"${CC:-cc}" -O2 -fPIC -shared -Wall -Wextra -Werror \
    -I"$uring_jdk/include" -I"$uring_jdk/include/linux" \
    -I"$uring_prefix/include" "$uring_root/src/jvmMain/c/uring_jni.c" \
    -L"$uring_prefix/lib" -Wl,-rpath,"$uring_prefix/lib" -luring \
    -o "$uring_output/libtrikeshed_uring.so"
echo "Built $uring_output/libtrikeshed_uring.so against $uring_prefix; JNI ABI 2"
