#!/bin/bash
# binance-pair-scanner.sh — discover trading pairs from Binance Vision archive
# Two-pass: (1) discover assets, eliminate leveraged (2) generate base×counter

ARCHIVE="${1:-data/spot/monthly/klines}"

# Pass 1: discover assets — extract base and quote from symbol dirs
declare -A assets
leveraged='^(BTC|ETH|BNB|SOL)?UP$|^(BTC|ETH|BNB|SOL)DOWN$|^[A-Z]+[0-9]{2}$'

for dir in "$ARCHIVE"/*/; do
    [[ -d "$dir" ]] || continue
    sym=$(basename "$dir")
    [[ "$sym" =~ ^([A-Z]+)([A-Z]+)$ ]] || continue
    
    base="${BASH_REMATCH[1]}"
    quote="${BASH_REMATCH[2]}"
    
    # Skip leveraged tokens: BTCUP, ETHDOWN, etc.
    [[ "$base" =~ $leveraged ]] && continue
    
    assets["$base"]=1
    assets["$quote"]=1
done

# Output discovery
echo "# Discovered assets (${!assets[@]})"
printf '%s\n' "${!assets[@]}" | sort

# Pass 2: generate pairs — base × counter, no counter × counter
bases=()
counters=()

# Heuristic: USDT/USDC/USDD are counters, others are bases
stable='USDT|USDC|USDD|USD|BUSD'

for a in "${!assets[@]}"; do
    if [[ "$a" =~ $stable ]]; then
        counters+=("$a")
    else
        bases+=("$a")
    fi
done

# Default counters if none found
(( ${#counters[@]} )) || counters=(USDT)

echo "# Pairs: ${#bases[@]} bases × ${#counters[@]} counters"
for base in "${bases[@]}"; do
    for counter in "${counters[@]}"; do
        echo "${base}${counter}"
    done
done