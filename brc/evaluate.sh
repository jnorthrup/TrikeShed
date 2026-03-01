#!/usr/bin/env bash
#
# Evaluate all 1BRC TrikeShed variants using hyperfine.
#
# Usage:
#   ./brc/evaluate.sh                    # benchmark all variants
#   ./brc/evaluate.sh baseline mmap      # benchmark specific variants
#
# Prerequisites:
#   - measurements.txt must exist (run create_measurements.sh first)
#   - hyperfine must be installed (brew install hyperfine)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
BRC_FILE="${BRC_FILE:-${PROJECT_DIR}/measurements.txt}"
RUNS="${BRC_RUNS:-5}"

if [[ ! -f "${BRC_FILE}" ]]; then
    echo "ERROR: ${BRC_FILE} not found."
    echo "Run: ./brc/create_measurements.sh 1000000000"
    exit 1
fi

VARIANTS=(baseline cursor mmap parallel fixedpoint)
if [[ $# -gt 0 ]]; then
    VARIANTS=("$@")
fi

# Build
echo "Building..."
bash "${SCRIPT_DIR}/prepare.sh"
echo ""

# Check for hyperfine
if command -v hyperfine &>/dev/null; then
    echo "Using hyperfine (${RUNS} runs each, warmup 1)..."
    echo "File: ${BRC_FILE} ($(wc -c < "${BRC_FILE}" | tr -d ' ') bytes)"
    echo ""

    CMDS=()
    NAMES=()
    for variant in "${VARIANTS[@]}"; do
        script="${SCRIPT_DIR}/calculate_average_${variant}.sh"
        if [[ -f "${script}" ]]; then
            CMDS+=("BRC_FILE=${BRC_FILE} bash ${script}")
            NAMES+=("${variant}")
        fi
    done

    # Build hyperfine command
    HYPERFINE_ARGS=(--warmup 1 --runs "${RUNS}" --export-markdown "${PROJECT_DIR}/brc/results.md")
    for i in "${!CMDS[@]}"; do
        HYPERFINE_ARGS+=(--command-name "${NAMES[$i]}" "${CMDS[$i]}")
    done

    hyperfine "${HYPERFINE_ARGS[@]}"

    echo ""
    echo "Results saved to brc/results.md"
else
    # Fallback: manual timing
    echo "hyperfine not found — using manual timing (single run each)."
    echo "Install hyperfine for proper benchmarking: brew install hyperfine"
    echo "File: ${BRC_FILE} ($(wc -c < "${BRC_FILE}" | tr -d ' ') bytes)"
    echo ""

    printf "%-15s %10s\n" "Variant" "Time (s)"
    printf "%-15s %10s\n" "-------" "--------"

    for variant in "${VARIANTS[@]}"; do
        script="${SCRIPT_DIR}/calculate_average_${variant}.sh"
        if [[ ! -f "${script}" ]]; then
            printf "%-15s %10s\n" "${variant}" "SKIP"
            continue
        fi

        START=$(date +%s%N 2>/dev/null || python3 -c 'import time; print(int(time.time()*1e9))')
        BRC_FILE="${BRC_FILE}" bash "${script}" > /dev/null 2>&1
        END=$(date +%s%N 2>/dev/null || python3 -c 'import time; print(int(time.time()*1e9))')

        ELAPSED=$(echo "scale=3; (${END} - ${START}) / 1000000000" | bc)
        printf "%-15s %10s\n" "${variant}" "${ELAPSED}"
    done
fi
