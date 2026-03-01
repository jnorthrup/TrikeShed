#!/usr/bin/env bash
#
# Clone and test a gunnarmorling/1brc fork.
#
# Usage:
#   ./brc/test_fork.sh <github_user>              # clone & test their entry
#   ./brc/test_fork.sh <github_user> [entry_name]  # test specific entry
#   ./brc/test_fork.sh --local /path/to/fork       # test a local clone
#
# This script:
#   1. Clones the fork (or uses a local directory)
#   2. Runs its prepare script (if any)
#   3. Tests against the 1BRC test suite
#   4. Optionally benchmarks with hyperfine
#
# Env vars:
#   BRC_FILE          Override measurement file for benchmarking
#   BRC_BENCHMARK     Set to 1 to run benchmark after tests (needs measurements.txt)
#   FORK_CACHE_DIR    Where to cache cloned forks (default: /tmp/brc_forks)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m'

FORK_CACHE="${FORK_CACHE_DIR:-/tmp/brc_forks}"
BENCHMARK="${BRC_BENCHMARK:-0}"

usage() {
    echo "Usage: $0 <github_user> [entry_name]"
    echo "       $0 --local /path/to/fork [entry_name]"
    echo ""
    echo "Examples:"
    echo "  $0 gunnarmorling                    # test baseline from original repo"
    echo "  $0 gunnarmorling baseline           # test baseline explicitly"
    echo "  $0 thomaswue                        # test thomaswue's fork"
    echo "  $0 --local ~/forks/1brc royvanrijn  # test local fork, specific entry"
    exit 1
}

if [[ $# -lt 1 ]]; then
    usage
fi

# Parse args
FORK_DIR=""
GH_USER=""
ENTRY_NAME=""

if [[ "$1" == "--local" ]]; then
    FORK_DIR="$2"
    ENTRY_NAME="${3:-}"
else
    GH_USER="$1"
    ENTRY_NAME="${2:-${GH_USER}}"
fi

# ── Clone / locate fork ─────────────────────────────────────────────────
if [[ -z "${FORK_DIR}" ]]; then
    mkdir -p "${FORK_CACHE}"
    FORK_DIR="${FORK_CACHE}/${GH_USER}"

    if [[ -d "${FORK_DIR}" ]]; then
        echo -e "${BLUE}Using cached fork at ${FORK_DIR}${NC}"
        cd "${FORK_DIR}" && git pull --quiet 2>/dev/null || true
    else
        echo -e "${BLUE}Cloning https://github.com/${GH_USER}/1brc.git ...${NC}"
        git clone --depth 1 "https://github.com/${GH_USER}/1brc.git" "${FORK_DIR}" 2>&1 | tail -1
    fi
fi

if [[ ! -d "${FORK_DIR}" ]]; then
    echo -e "${RED}ERROR: Fork directory not found: ${FORK_DIR}${NC}"
    exit 1
fi

echo -e "${BOLD}Fork: ${FORK_DIR}${NC}"
echo ""

# ── Discover entries ─────────────────────────────────────────────────────
CALC_SCRIPT=""
PREPARE_SCRIPT=""

if [[ -n "${ENTRY_NAME}" ]]; then
    CALC_SCRIPT="${FORK_DIR}/calculate_average_${ENTRY_NAME}.sh"
    PREPARE_SCRIPT="${FORK_DIR}/prepare_${ENTRY_NAME}.sh"

    if [[ ! -f "${CALC_SCRIPT}" ]]; then
        echo -e "${RED}ERROR: ${CALC_SCRIPT} not found${NC}"
        echo ""
        echo "Available entries:"
        ls "${FORK_DIR}"/calculate_average_*.sh 2>/dev/null | \
            sed 's|.*/calculate_average_||; s|\.sh$||' | \
            sort | sed 's/^/  /'
        exit 1
    fi
else
    # List all available entries
    echo "Available entries in fork:"
    ls "${FORK_DIR}"/calculate_average_*.sh 2>/dev/null | \
        sed 's|.*/calculate_average_||; s|\.sh$||' | \
        sort | sed 's/^/  /'
    echo ""
    echo "Specify an entry name to test: $0 ${GH_USER:-$1} <entry_name>"
    exit 0
fi

echo -e "Entry: ${BOLD}${ENTRY_NAME}${NC}"
echo "Script: ${CALC_SCRIPT}"
echo ""

# ── Build (prepare) ─────────────────────────────────────────────────────
echo -e "${BLUE}Building...${NC}"

# Check if the fork uses Maven (standard 1brc)
if [[ -f "${FORK_DIR}/pom.xml" ]]; then
    cd "${FORK_DIR}"
    if [[ -f "${PREPARE_SCRIPT}" ]]; then
        echo "  Running prepare_${ENTRY_NAME}.sh..."
        bash "${PREPARE_SCRIPT}" 2>&1 | tail -3
    else
        echo "  Running mvnw clean verify..."
        ./mvnw clean verify -q -DskipTests 2>&1 | tail -3 || {
            echo -e "${YELLOW}Maven build failed, trying without clean...${NC}"
            ./mvnw verify -q -DskipTests 2>&1 | tail -3 || true
        }
    fi
elif [[ -f "${PREPARE_SCRIPT}" ]]; then
    echo "  Running prepare_${ENTRY_NAME}.sh..."
    cd "${FORK_DIR}"
    bash "${PREPARE_SCRIPT}" 2>&1 | tail -3
else
    echo "  No build step found"
fi

cd "${PROJECT_DIR}"
echo ""

# ── Test data sets ───────────────────────────────────────────────────────
# Use the fork's own test data if available, else fall back to ours
if [[ -f "${FORK_DIR}/src/test/resources/samples/measurements-short.txt" ]]; then
    FORK_TEST_DIR="${FORK_DIR}/src/test/resources/samples"
else
    FORK_TEST_DIR=""
fi

# We always test with our canonical data + the fork's test data
PASS=0
FAIL=0

echo -e "${BLUE}Running correctness tests...${NC}"
echo ""

run_test() {
    local name="$1" file="$2" expected="$3"

    if [[ ! -f "${file}" ]]; then
        echo -e "  ${YELLOW}SKIP${NC} ${name}: file not found"
        return
    fi

    local actual
    actual=$(cd "${FORK_DIR}" && BRC_FILE="${file}" timeout 120 bash "${CALC_SCRIPT}" 2>/dev/null || echo "__ERROR__")
    local actual_trimmed
    actual_trimmed=$(echo "${actual}" | tr -d '\n' | sed 's/[[:space:]]*$//')

    if [[ "${actual_trimmed}" == "${expected}" ]]; then
        echo -e "  ${GREEN}PASS${NC} ${name}"
        PASS=$((PASS+1))
    else
        echo -e "  ${RED}FAIL${NC} ${name}"
        echo "    Expected: ${expected}"
        echo "    Actual:   ${actual_trimmed}"
        FAIL=$((FAIL+1))
    fi
}

# Test 1: Our canonical test data
CANONICAL_EXPECTED=$(cat "${SCRIPT_DIR}/data/expected_output.txt" | tr -d '\n')
run_test "canonical (TrikeShed)" "${SCRIPT_DIR}/data/measurements_test.txt" "${CANONICAL_EXPECTED}"

# Test 2: Fork's own test data (if present)
if [[ -n "${FORK_TEST_DIR}" ]]; then
    for sample in "${FORK_TEST_DIR}"/measurements-*.txt; do
        [[ -f "${sample}" ]] || continue
        base=$(basename "${sample}" .txt)
        out="${sample%.txt}.out"
        if [[ -f "${out}" ]]; then
            expected=$(cat "${out}" | tr -d '\n')
            run_test "${base} (fork)" "${sample}" "${expected}"
        fi
    done
fi

# Test 3: Edge cases
TMP=$(mktemp -d)
trap "rm -rf ${TMP}" EXIT

# Single row
echo "Solo;42.0" > "${TMP}/single.txt"
run_test "single-row" "${TMP}/single.txt" "{Solo=42.0/42.0/42.0}"

# All negative
cat > "${TMP}/negative.txt" << 'EOF'
Arctic;-99.9
Arctic;-0.1
Antarctic;-50.0
Antarctic;-75.3
EOF
run_test "all-negative" "${TMP}/negative.txt" "{Antarctic=-75.3/-62.6/-50.0, Arctic=-99.9/-50.0/-0.1}"

# Boundary
cat > "${TMP}/boundary.txt" << 'EOF'
Hot;99.9
Hot;0.0
Cold;-99.9
Cold;0.0
EOF
run_test "boundary" "${TMP}/boundary.txt" "{Cold=-99.9/-49.9/0.0, Hot=0.0/50.0/99.9}"

# Zero
cat > "${TMP}/zero.txt" << 'EOF'
Zero;0.0
Zero;0.0
Zero;0.0
EOF
run_test "all-zero" "${TMP}/zero.txt" "{Zero=0.0/0.0/0.0}"

echo ""
echo "════════════════════════════════════════════════════════════════"
echo -e "Results: ${GREEN}${PASS} passed${NC}, ${RED}${FAIL} failed${NC}"

# ── Benchmark (optional) ────────────────────────────────────────────────
if [[ "${BENCHMARK}" == "1" ]]; then
    BRC_FILE="${BRC_FILE:-${PROJECT_DIR}/measurements.txt}"
    if [[ ! -f "${BRC_FILE}" ]]; then
        echo ""
        echo -e "${YELLOW}Skipping benchmark: ${BRC_FILE} not found${NC}"
        echo "Generate with: ./brc/create_measurements.sh 1000000000"
    else
        echo ""
        echo -e "${BLUE}Running benchmark...${NC}"
        echo "File: ${BRC_FILE} ($(wc -c < "${BRC_FILE}" | tr -d ' ') bytes)"
        echo ""

        if command -v hyperfine &>/dev/null; then
            cd "${FORK_DIR}"
            hyperfine --warmup 1 --runs 3 \
                --command-name "${ENTRY_NAME}" \
                "BRC_FILE=${BRC_FILE} bash ${CALC_SCRIPT}"
        else
            echo "  (install hyperfine for precise benchmarking)"
            cd "${FORK_DIR}"
            START=$(python3 -c 'import time; print(int(time.time()*1e9))')
            BRC_FILE="${BRC_FILE}" bash "${CALC_SCRIPT}" > /dev/null 2>&1
            END=$(python3 -c 'import time; print(int(time.time()*1e9))')
            ELAPSED=$(echo "scale=3; (${END} - ${START}) / 1000000000" | bc)
            echo "  ${ENTRY_NAME}: ${ELAPSED}s"
        fi
    fi
fi

echo ""
if [[ ${FAIL} -gt 0 ]]; then
    exit 1
fi
