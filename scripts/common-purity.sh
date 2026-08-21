#!/usr/bin/env bash

FAILURES=0

while IFS= read -r file; do
    TMP1=$(mktemp)
    TMP2=$(mktemp)

    grep -nE "^import java\.|\\bSystem\.|Dispatchers\.IO|Charsets\.|\\.format\(|java\.nio|Selector|SocketChannel" "$file" | grep -v "// purity:allow" > "$TMP1" || true

    if [ -s "$TMP1" ]; then
        echo "Purity violation in $file:"
        cat "$TMP1"
        FAILURES=1
    fi

    if grep -q "@JvmInline" "$file" && ! grep -q "import kotlin\.jvm\.JvmInline" "$file"; then
        grep -n "@JvmInline" "$file" | grep -v "// purity:allow" > "$TMP2" || true
        if [ -s "$TMP2" ]; then
            echo "Purity violation (@JvmInline without import kotlin.jvm.JvmInline) in $file:"
            cat "$TMP2"
            FAILURES=1
        fi
    fi

    rm -f "$TMP1" "$TMP2"
done < <(find src/commonMain -type f -name "*.kt")

if [ "$FAILURES" -gt 0 ]; then
    echo "Purity check failed."
    # Command fails
    sh -c 'exit 1'
else
    echo "Purity check passed."
    # Command passes
    sh -c 'exit 0'
fi
