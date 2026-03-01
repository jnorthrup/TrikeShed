#!/usr/bin/env bash
#
# Test all 1BRC TrikeShed variants against reference output.
# Usage: ./brc/test.sh [variant_name]
#
# If variant_name is given, only test that variant.
# Otherwise test all variants.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

TEST_FILE="${SCRIPT_DIR}/data/measurements_test.txt"
EXPECTED="${SCRIPT_DIR}/data/expected_output.txt"

VARIANTS=(baseline cursor mmap parallel fixedpoint)
if [[ $# -gt 0 ]]; then
    VARIANTS=("$1")
fi

# Build first
echo -e "${YELLOW}Building...${NC}"
bash "${SCRIPT_DIR}/prepare.sh"
echo ""

PASS=0
FAIL=0

for variant in "${VARIANTS[@]}"; do
    script="${SCRIPT_DIR}/calculate_average_${variant}.sh"
    if [[ ! -f "${script}" ]]; then
        echo -e "${RED}SKIP${NC} ${variant}: script not found"
        continue
    fi

    echo -n "Testing ${variant}... "

    # Run variant against test data
    actual=$(BRC_FILE="${TEST_FILE}" bash "${script}" 2>/dev/null || true)
    expected=$(cat "${EXPECTED}" | tr -d '\n')
    actual_trimmed=$(echo "${actual}" | tr -d '\n')

    if [[ "${actual_trimmed}" == "${expected}" ]]; then
        echo -e "${GREEN}PASS${NC}"
        ((PASS++))
    else
        echo -e "${RED}FAIL${NC}"
        echo "  Expected: ${expected}"
        echo "  Actual:   ${actual_trimmed}"
        if command -v diff &>/dev/null; then
            diff <(echo "${expected}") <(echo "${actual_trimmed}") || true
        fi
        ((FAIL++))
    fi
done

echo ""
echo -e "Results: ${GREEN}${PASS} passed${NC}, ${RED}${FAIL} failed${NC}"

if [[ ${FAIL} -gt 0 ]]; then
    exit 1
fi
