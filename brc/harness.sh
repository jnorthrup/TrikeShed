#!/usr/bin/env bash
#
# Test Harness for 1BRC Fork Implementations
#
# Tests any calculate_average_*.sh script against multiple data sets
# with correctness validation, output format checking, and timing.
#
# Compatible with gunnarmorling/1brc fork conventions.
#
# Usage:
#   ./brc/harness.sh                          # test all TrikeShed variants
#   ./brc/harness.sh baseline mmap            # test specific variants
#   ./brc/harness.sh --fork /path/to/fork     # test an external 1brc fork
#   ./brc/harness.sh --script /path/to/calc.sh  # test a single script
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

# ── Colors ───────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m'

# ── Parse args ───────────────────────────────────────────────────────────
FORK_DIR=""
SINGLE_SCRIPT=""
VARIANTS=()
TIMEOUT=${BRC_TIMEOUT:-120}
SKIP_BUILD=${BRC_SKIP_BUILD:-0}
VERBOSE=${BRC_VERBOSE:-0}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --fork)     FORK_DIR="$2"; shift 2 ;;
        --script)   SINGLE_SCRIPT="$2"; shift 2 ;;
        --timeout)  TIMEOUT="$2"; shift 2 ;;
        --skip-build) SKIP_BUILD=1; shift ;;
        --verbose)  VERBOSE=1; shift ;;
        --help|-h)
            echo "Usage: $0 [options] [variant...]"
            echo ""
            echo "Options:"
            echo "  --fork DIR       Test all calculate_average_*.sh in DIR"
            echo "  --script FILE    Test a single script"
            echo "  --timeout SECS   Timeout per run (default: 120)"
            echo "  --skip-build     Skip Gradle build step"
            echo "  --verbose        Show diff output on failure"
            echo "  --help           Show this help"
            echo ""
            echo "Without --fork/--script, tests TrikeShed variants."
            echo "Variants: baseline cursor mmap parallel fixedpoint"
            exit 0
            ;;
        *)          VARIANTS+=("$1"); shift ;;
    esac
done

# ── Counters ─────────────────────────────────────────────────────────────
TOTAL=0
PASS=0
FAIL=0
SKIP=0
ERRORS=()

pass() { PASS=$((PASS+1)); TOTAL=$((TOTAL+1)); echo -e "  ${GREEN}PASS${NC} $1"; }
fail() { FAIL=$((FAIL+1)); TOTAL=$((TOTAL+1)); ERRORS+=("$1: $2"); echo -e "  ${RED}FAIL${NC} $1: $2"; }
skip() { SKIP=$((SKIP+1)); echo -e "  ${YELLOW}SKIP${NC} $1: $2"; }

# ── Time a command, return ms ────────────────────────────────────────────
time_ms() {
    local start end
    start=$(python3 -c 'import time; print(int(time.time()*1e6))' 2>/dev/null || date +%s%6N)
    "$@" > /dev/null 2>&1
    end=$(python3 -c 'import time; print(int(time.time()*1e6))' 2>/dev/null || date +%s%6N)
    echo $(( (end - start) / 1000 ))
}

# ── Generate test data sets ──────────────────────────────────────────────
DATA_DIR="${SCRIPT_DIR}/data"
GEN_DIR=$(mktemp -d "${TMPDIR:-/tmp}/brc_harness.XXXXXX")
trap "rm -rf ${GEN_DIR}" EXIT

echo -e "${BOLD}1BRC Test Harness${NC}"
echo "════════════════════════════════════════════════════════════════"

echo -e "\n${BLUE}[1/5] Generating test data sets...${NC}"

# Dataset 1: Canonical (bundled)
CANONICAL_FILE="${DATA_DIR}/measurements_test.txt"
CANONICAL_EXPECTED="${DATA_DIR}/expected_output.txt"

# Dataset 2: All negative temperatures
cat > "${GEN_DIR}/negative.txt" << 'EOF'
Arctic;-99.9
Antarctic;-50.0
Arctic;-0.1
Antarctic;-75.3
Arctic;-42.7
Siberia;-33.3
Siberia;-88.8
EOF

NEGATIVE_EXPECTED="{Antarctic=-75.3/-62.6/-50.0, Arctic=-99.9/-47.6/-0.1, Siberia=-88.8/-61.0/-33.3}"

# Dataset 3: Boundary temperatures
cat > "${GEN_DIR}/boundary.txt" << 'EOF'
Hot;99.9
Cold;-99.9
Hot;0.0
Cold;0.0
Zero;0.0
Zero;0.0
EOF

BOUNDARY_EXPECTED="{Cold=-99.9/-49.9/0.0, Hot=0.0/50.0/99.9, Zero=0.0/0.0/0.0}"

# Dataset 4: Single row
cat > "${GEN_DIR}/single.txt" << 'EOF'
Solo;42.0
EOF

SINGLE_EXPECTED="{Solo=42.0/42.0/42.0}"

# Dataset 5: Unicode station names
cat > "${GEN_DIR}/unicode.txt" << 'EOF'
São Paulo;25.3
Zürich;8.1
München;-3.4
São Paulo;22.7
Zürich;-1.4
München;12.6
EOF

UNICODE_EXPECTED="{München=-3.4/4.6/12.6, São Paulo=22.7/24.0/25.3, Zürich=-1.4/3.4/8.1}"

# Dataset 6: Same value repeated (min=mean=max)
python3 -c "
for i in range(100):
    print('Constant;7.3')
" > "${GEN_DIR}/constant.txt"

CONSTANT_EXPECTED="{Constant=7.3/7.3/7.3}"

# Dataset 7: Many stations (generated)
python3 -c "
import random
random.seed(42)
stations = [f'Station_{i:04d}' for i in range(200)]
for _ in range(2000):
    s = random.choice(stations)
    t = random.randint(-999, 999) / 10.0
    print(f'{s};{t:.1f}')
" > "${GEN_DIR}/many_stations.txt"

# Compute expected for many_stations using python oracle
MANY_EXPECTED=$(python3 -c "
import math, sys
from collections import defaultdict

acc = {}
with open('${GEN_DIR}/many_stations.txt') as f:
    for line in f:
        line = line.strip()
        if not line: continue
        name, temp_s = line.split(';')
        temp = float(temp_s)
        if name not in acc:
            acc[name] = [temp, temp, 0.0, 0]
        a = acc[name]
        if temp < a[0]: a[0] = temp
        if temp > a[1]: a[1] = temp
        a[2] += temp
        a[3] += 1

def fmt(v):
    scaled = v * 10
    rounded = math.floor(scaled + 0.5)
    r = int(rounded)
    a = abs(r)
    sign = '-' if r < 0 else ''
    return f'{sign}{a // 10}.{a % 10}'

parts = []
for name in sorted(acc.keys()):
    a = acc[name]
    mean = a[2] / a[3]
    parts.append(f'{name}={fmt(a[0])}/{fmt(mean)}/{fmt(a[1])}')
print('{' + ', '.join(parts) + '}')
")

echo "  Generated 7 test data sets in ${GEN_DIR}"

# ── Build ────────────────────────────────────────────────────────────────
if [[ "${SKIP_BUILD}" == "0" ]] && [[ -z "${FORK_DIR}" ]] && [[ -z "${SINGLE_SCRIPT}" ]]; then
    echo -e "\n${BLUE}[2/5] Building TrikeShed...${NC}"
    cd "${PROJECT_DIR}"
    ./gradlew jvmJar --quiet 2>&1
    rm -f build/.brc_classpath
    echo "  Build OK"
else
    echo -e "\n${BLUE}[2/5] Build${NC} (skipped)"
fi

# ── Collect scripts to test ──────────────────────────────────────────────
echo -e "\n${BLUE}[3/5] Collecting scripts to test...${NC}"

declare -a SCRIPTS=()
declare -a SCRIPT_NAMES=()

if [[ -n "${SINGLE_SCRIPT}" ]]; then
    SCRIPTS=("${SINGLE_SCRIPT}")
    SCRIPT_NAMES=("$(basename "${SINGLE_SCRIPT}" .sh)")
elif [[ -n "${FORK_DIR}" ]]; then
    # Discover all calculate_average_*.sh in the fork directory
    for script in "${FORK_DIR}"/calculate_average_*.sh; do
        [[ -f "${script}" ]] || continue
        SCRIPTS+=("${script}")
        name=$(basename "${script}" .sh | sed 's/^calculate_average_//')
        SCRIPT_NAMES+=("${name}")
    done
    if [[ ${#SCRIPTS[@]} -eq 0 ]]; then
        echo -e "${RED}ERROR: No calculate_average_*.sh scripts found in ${FORK_DIR}${NC}"
        exit 1
    fi

    # Run prepare scripts if they exist
    for script in "${SCRIPTS[@]}"; do
        name=$(basename "${script}" .sh | sed 's/^calculate_average_//')
        prepare="${FORK_DIR}/prepare_${name}.sh"
        if [[ -f "${prepare}" ]]; then
            echo "  Running prepare_${name}.sh..."
            bash "${prepare}" 2>&1 | tail -1 || true
        fi
    done
else
    # Default: TrikeShed variants
    DEFAULT_VARIANTS=(baseline cursor mmap parallel fixedpoint)
    if [[ ${#VARIANTS[@]} -gt 0 ]]; then
        DEFAULT_VARIANTS=("${VARIANTS[@]}")
    fi
    for variant in "${DEFAULT_VARIANTS[@]}"; do
        script="${SCRIPT_DIR}/calculate_average_${variant}.sh"
        if [[ -f "${script}" ]]; then
            SCRIPTS+=("${script}")
            SCRIPT_NAMES+=("${variant}")
        else
            echo -e "  ${YELLOW}WARN: ${script} not found${NC}"
        fi
    done
fi

echo "  Found ${#SCRIPTS[@]} script(s): ${SCRIPT_NAMES[*]}"

# ── Run Tests ────────────────────────────────────────────────────────────
echo -e "\n${BLUE}[4/5] Running correctness tests...${NC}"

# Test datasets: (name, file, expected)
declare -a TEST_NAMES=("canonical" "negative" "boundary" "single" "unicode" "constant" "many_stations")
declare -a TEST_FILES=("${CANONICAL_FILE}" "${GEN_DIR}/negative.txt" "${GEN_DIR}/boundary.txt" "${GEN_DIR}/single.txt" "${GEN_DIR}/unicode.txt" "${GEN_DIR}/constant.txt" "${GEN_DIR}/many_stations.txt")
declare -a TEST_EXPECTED=("$(cat "${CANONICAL_EXPECTED}" | tr -d '\n')" "${NEGATIVE_EXPECTED}" "${BOUNDARY_EXPECTED}" "${SINGLE_EXPECTED}" "${UNICODE_EXPECTED}" "${CONSTANT_EXPECTED}" "${MANY_EXPECTED}")

for si in "${!SCRIPTS[@]}"; do
    script="${SCRIPTS[$si]}"
    sname="${SCRIPT_NAMES[$si]}"
    echo ""
    echo -e "${BOLD}  ── ${sname} ──${NC}"

    for ti in "${!TEST_NAMES[@]}"; do
        tname="${TEST_NAMES[$ti]}"
        tfile="${TEST_FILES[$ti]}"
        texpected="${TEST_EXPECTED[$ti]}"

        if [[ ! -f "${tfile}" ]]; then
            skip "${sname}/${tname}" "data file missing"
            continue
        fi

        # Run with timeout
        set +e
        actual=$(BRC_FILE="${tfile}" timeout "${TIMEOUT}" bash "${script}" 2>/dev/null)
        rc=$?
        set -e

        if [[ ${rc} -eq 124 ]]; then
            fail "${sname}/${tname}" "TIMEOUT (>${TIMEOUT}s)"
            continue
        fi

        if [[ ${rc} -ne 0 ]] && [[ -z "${actual}" ]]; then
            fail "${sname}/${tname}" "exit code ${rc}"
            continue
        fi

        actual_trimmed=$(echo "${actual}" | tr -d '\n' | sed 's/[[:space:]]*$//')

        if [[ "${actual_trimmed}" == "${texpected}" ]]; then
            pass "${sname}/${tname}"
        else
            fail "${sname}/${tname}" "output mismatch"
            if [[ "${VERBOSE}" == "1" ]]; then
                echo "    Expected: ${texpected}"
                echo "    Actual:   ${actual_trimmed}"
                if command -v diff &>/dev/null; then
                    diff --color=auto <(echo "${texpected}") <(echo "${actual_trimmed}") || true
                fi
            fi
        fi
    done
done

# ── Output Format Validation ─────────────────────────────────────────────
echo -e "\n${BLUE}[5/5] Validating output format...${NC}"

for si in "${!SCRIPTS[@]}"; do
    script="${SCRIPTS[$si]}"
    sname="${SCRIPT_NAMES[$si]}"

    actual=$(BRC_FILE="${CANONICAL_FILE}" bash "${script}" 2>/dev/null | tr -d '\n' || true)

    # Check starts with { and ends with }
    if [[ "${actual}" == "{"*"}" ]]; then
        pass "${sname}/format-braces"
    else
        fail "${sname}/format-braces" "output not wrapped in {}"
    fi

    # Check alphabetical ordering of station names
    inner="${actual#\{}"
    inner="${inner%\}}"
    ordered=$(echo "${inner}" | tr ',' '\n' | sed 's/^ //' | cut -d= -f1 | sort -c 2>&1 || echo "UNSORTED")
    if [[ -z "${ordered}" ]]; then
        pass "${sname}/format-sorted"
    else
        fail "${sname}/format-sorted" "stations not alphabetically sorted"
    fi

    # Check min/mean/max format (d.d)
    format_ok=true
    # Use while-read to handle station names with spaces (e.g. "St. John's")
    echo "${inner}" | tr ',' '\n' | sed 's/^ //' | while IFS= read -r entry; do
        [[ -z "${entry}" ]] && continue
        values="${entry#*=}"
        IFS='/' read -r v1 v2 v3 <<< "${values}"
        for v in "${v1}" "${v2}" "${v3}"; do
            if ! echo "${v}" | grep -qE '^-?[0-9]+\.[0-9]$'; then
                # Signal failure via a temp file since we're in a subshell
                touch "${GEN_DIR}/.format_fail"
                break 2
            fi
        done
    done
    if [[ -f "${GEN_DIR}/.format_fail" ]]; then
        rm -f "${GEN_DIR}/.format_fail"
        format_ok=false
    fi
    if ${format_ok}; then
        pass "${sname}/format-values"
    else
        fail "${sname}/format-values" "values not in [-]d.d format"
    fi
done

# ── Summary ──────────────────────────────────────────────────────────────
echo ""
echo "════════════════════════════════════════════════════════════════"
echo -e "${BOLD}Results:${NC} ${GREEN}${PASS} passed${NC}, ${RED}${FAIL} failed${NC}, ${YELLOW}${SKIP} skipped${NC} (${TOTAL} total)"

if [[ ${#ERRORS[@]} -gt 0 ]]; then
    echo ""
    echo -e "${RED}Failures:${NC}"
    for err in "${ERRORS[@]}"; do
        echo "  • ${err}"
    done
fi

echo ""
if [[ ${FAIL} -gt 0 ]]; then
    echo -e "${RED}HARNESS FAILED${NC}"
    exit 1
else
    echo -e "${GREEN}ALL TESTS PASSED${NC}"
    exit 0
fi
