# libs/cmc — TODO (cmc-generated)

## Intent
Generated Kotlin sources for CMC (CoinMarketCap) API client. Plain JVM module with generated sources in `src/generated/kotlin/`.

## Status: GENERATED (no manual edits)

## Pure boundary audit

### Keys — check generated code
- [ ] Verify generated code follows Key/Element pattern if it has async operations
- Currently depends on kotlinx-coroutines-core only

### Elements — check generated code
- [ ] Same: verify lifecycle pattern

## Integration partners
- **No libs depend on this** — leaf module
- Used by integration-scratch or dreamer for CMC price data

## Path to stable
1. Verify generated sources compile
2. If async: ensure Key/Element pattern
3. Add compilation test only (no hand-editing generated code)
