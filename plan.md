1. **Config schema and values (`borg.trikeshed.config`)**
   - Create `ConfigSchema`, `ConfigType`, and `ConfigValue` definitions.
   - We will put them in `src/commonMain/kotlin/borg/trikeshed/config/Config.kt`.

2. **Feature flags (`borg.trikeshed.flags`)**
   - Create `FeatureFlag` with `rolloutPercentage`.
   - Create `FeatureFlagManager` for percentage-based rollout using a stable hash function over a string like user ID or context ID.
   - We will put them in `src/commonMain/kotlin/borg/trikeshed/flags/FeatureFlags.kt`.

3. **Hot-reload (`borg.trikeshed.reload`)**
   - Create `HotReloader` using coroutines to poll or watch a config file.
   - Given `FileOperations` SPI, we can check file modification times or hash codes periodically.
   - We will put them in `src/commonMain/kotlin/borg/trikeshed/reload/HotReload.kt`.

4. **ISAM/Persistence connection (`borg.trikeshed.config`)**
   - Connect the configuration model to the ISAM persistence layer (e.g. read/write to `IsamDataFile`).
   - Create `IsamConfigStore` in `src/commonMain/kotlin/borg/trikeshed/config/IsamConfigStore.kt`.

5. **Testing (TDD)**
   - Run tests that I already created in `src/commonTest/kotlin/borg/trikeshed/{config,flags,reload}/`.
   - Iterate to make sure the tests compile and run properly. Note there are unrelated compilation errors in other files that should not block us, but we can compile just our test sources if needed.

6. **Pre-commit instructions**
   - Complete pre-commit steps to make sure proper testing, verifications, reviews and reflections are done.

7. **Submit**
