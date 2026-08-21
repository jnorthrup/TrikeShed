#!/bin/bash
# commonMain purity check — detects JVM-specific imports and patterns
# Usage: scripts/common-purity.sh [--summary]

set -e

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VIOLATIONS_FILE=$(mktemp)
trap "rm -f $VIOLATIONS_FILE" EXIT

# Patterns to check (regex) and their descriptions
declare -a PATTERNS=(
    "^import java\.|JVM stdlib imports"
    "\bSystem\.|JVM System class"
    "Dispatchers\.IO|JVM-only dispatcher"
    "Charsets\.|JVM Charsets"
    "\.format\(|JVM String.format"
    "java\.nio|JVM NIO"
    "\bSelector\b|NIO Selector"
    "SocketChannel|NIO SocketChannel"
)

TOTAL_VIOLATIONS=0

# Check each Kotlin file in src/commonMain
find "$REPO_ROOT/src/commonMain" -name "*.kt" -type f | sort | while read -r file; do
    LINE_NUM=0
    FILE_VIOLATIONS=0

    while IFS= read -r line; do
        LINE_NUM=$((LINE_NUM + 1))

        # Skip lines with purity:allow comment
        if [[ "$line" =~ purity:allow ]]; then
            continue
        fi

        # Check each pattern
        for i in "${!PATTERNS[@]}"; do
            PATTERN="${PATTERNS[$i]%|*}"

            if [[ "$line" =~ $PATTERN ]]; then
                echo "$file:$LINE_NUM: $line" >> "$VIOLATIONS_FILE"
                FILE_VIOLATIONS=$((FILE_VIOLATIONS + 1))
                break
            fi
        done
    done < "$file"

    # Extra guard from fbc9f2: @JvmInline without proper import
    if grep -q "@JvmInline" "$file" 2>/dev/null && ! grep -q "import kotlin\.jvm\.JvmInline" "$file" 2>/dev/null; then
        TMP2=$(mktemp)
        grep -n "@JvmInline" "$file" | grep -v "// purity:allow" > "$TMP2" || true
        if [ -s "$TMP2" ]; then
            echo "Purity violation (@JvmInline without import kotlin.jvm.JvmInline) in $file:" >> "$VIOLATIONS_FILE"
            cat "$TMP2" >> "$VIOLATIONS_FILE"
            FILE_VIOLATIONS=$((FILE_VIOLATIONS + 1))
        fi
        rm -f "$TMP2"
    fi

    if [ $FILE_VIOLATIONS -gt 0 ]; then
        TOTAL_VIOLATIONS=$((TOTAL_VIOLATIONS + FILE_VIOLATIONS))
    fi
done

# Output results
if [ -s "$VIOLATIONS_FILE" ]; then
    if [ "$1" = "--summary" ]; then
        echo "commonMain purity: $TOTAL_VIOLATIONS violations found"
        exit 1
    else
        cat "$VIOLATIONS_FILE"
        echo ""
        echo "commonMain purity: $TOTAL_VIOLATIONS violations found"
        exit 1
    fi
else
    echo "commonMain purity: OK (0 violations)"
    exit 0
fi
