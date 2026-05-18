# libs/common — TODO

## Boundary Audit

### Current State

- `TrikeShedLib.kt` is intentionally empty. No action needed.
- `NioSpiLoader.kt` provides two stateless factory functions
  (`loadUserspaceNioSpi()`, `loadLiburingFacadeSpi()`) that perform
  `ServiceLoader.load()` lookups. These are pure — no lifecycle, no fanout.

### Key/Element Classification

| Symbol | Classification | Action |
|---|---|---|
| `loadUserspaceNioSpi()` | Pure factory (stateless lookup) | No Key needed; remains as-is |
| `loadLiburingFacadeSpi()` | Pure factory (stateless lookup) | No Key needed; remains as-is |
| `UserspaceNioSpi` (root) | Routable SPI interface | Could become `AsyncContextKey` carrier if lifecycle management is needed downstream |
| `LiburingFacadeSpi` (root) | Routable SPI interface | Could become `AsyncContextKey` carrier if lifecycle management is needed downstream |

### Findings

1. **No production Keys or Elements in this module.** The test suite
   (`AsyncContextSupervisorTest`, `AsyncContextInvariantsTest`) creates private
   test-only `AsyncContextElement` subclasses to prove root-project types are
   usable from downstream — correct pattern.
2. **SPI loaders are JVM-only.** The `jvmMain` source set carries the only
   platform-specific code. Common source sets have no platform branching.
3. **No `ElementState` transitions tested beyond CREATED→OPEN→CLOSED.** The
   ACTIVE and DRAINING states have no exercise in this module's tests (they
   are covered in root tests, but not here).

## Integration Steps

1. **Partner lib integration**: Other libs that need `ServiceLoader`-based SPI
   loading (e.g., `libs/uring`) should add `libs/common` as a dependency rather
   than duplicating the loader functions.
2. **SPI Key wrapping**: If `UserspaceNioSpi` or `LiburingFacadeSpi` providers
   need lifecycle management (open/drain/close), create wrapper Elements in
   the consuming module with the SPI interface as the Key's routed type.
3. **Shared test infrastructure**: The test-only `ElementA`/`ElementB`/
   `TestElementX`/`TestElementY`/`TestElementZ` patterns should be extracted
   into a shared test-fixtures module if more than 2-3 libs need them.

## Path to Stable

- [ ] Confirm `TrikeShedLib.kt` empty shim can be removed entirely (verify no
      downstream references to `borg.trikeshed.lib.TrikeShedLib`)
- [ ] Add ACTIVE and DRAINING state transition tests in `commonTest` to close
      coverage gap
- [ ] Decide whether SPI loaders should live here or move to a dedicated
      `libs/spi` module (current placement is fine for single-consumer usage)
- [ ] Add `@JvmOverloads` or `@JvmStatic` annotations if Java interop is needed
      on SPI loaders
- [ ] Mark module as stable once root-project algebra is API-locked
