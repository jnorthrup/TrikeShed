# libs/polyglot — TODO

## Intent
Language/parse taxonomy bridge. Maps between Narsive parse kinds, MLIR dialect taxonomy, and source fragment classification. Depends on kursive. JVM-only.

## Status: ALPHA (mostly static registries, no lifecycle)

## Pure boundary audit

### Keys (need creation)
- No context keys exist. This module is currently pure registries.

### Elements (stateful — none currently)
- No stateful classes. All `object` singletons.

### Statics → evaluate
- `LangRegistry` object — maps language names to parsers. Pure lookup ✓
- `LangParsers` object — parser factory registry ✓
- `Nars3PolyglotBridge` object — bridges Narsive elements to MLIR taxonomy ✓
- `FuncOps` / `ArithOps` objects — MLIR operation taxonomies ✓
- `NodeKind` enum — source fragment classification. Stays enum ✓ (domain taxonomy)

### Refactoring
- [ ] If polyglot ever needs to dispatch parsers concurrently, create a `PolyglotDispatchKey` and make it an AsyncContextElement
- Currently no need — this is a static registry module

## Integration partners
- **kursive**: imports kursive for `NarsiveElementKind`, parser types
- **No other libs depend on this** — it's a leaf dependency

## Path to stable
1. Add tests for `LangRegistry` lookup round-trip
2. Add tests for `Nars3PolyglotBridge` mapping
3. Consider if MLIR taxonomy types should live here or in a dedicated MLIR module
