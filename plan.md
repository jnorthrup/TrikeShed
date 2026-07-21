1. **Tests First (TDD)**
   - Set up `ConfixCborTest.kt` with vectors for RFC 8949 (integers, strings, arrays, maps, nested), definite lengths, canonical ordering, and idempotency.
   - Set up `ConfixSerializationBoundaryTest.kt` to ensure `kotlinx-serialization-json` is NOT in `build.gradle.kts` and forbidden types are NOT in `src/commonMain`.
   
2. **Remove Dependencies and Forbidden Imports**
   - In `build.gradle.kts`, remove `implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")`
   - Move `src/jvmMain/kotlin/borg/trikeshed/parse/confix/ConfixSerialization.kt` to `src/commonMain/kotlin/borg/trikeshed/parse/confix/ConfixSerialization.kt`.
   - Remove `kotlinx.serialization.json.*` imports and usages. We will implement our own intermediate representation (e.g., `ConfixElement`, `ConfixObject`, etc.) to replace them in `ForgeApp.kt`, `SyncMessage.kt`, etc.

3. **Implement ConfixElement**
   - Create `src/commonMain/kotlin/borg/trikeshed/parse/confix/ConfixElement.kt`.
   - Implement `ConfixElement`, `ConfixObject`, `ConfixArray`, `ConfixPrimitive`, `ConfixNull`.
   - Replace `JsonElement` with `ConfixElement` everywhere in `commonMain`.

4. **Implement KSerializer <-> ConfixElement Bridge**
   - Write our own `ConfixElementEncoder` and `ConfixElementDecoder` extending `kotlinx.serialization.encoding.AbstractEncoder/AbstractDecoder` to serialize from `T` to `ConfixElement` and deserialize from `ConfixElement` to `T`.
   
5. **Implement Canonical CBOR Encoder/Decoder**
   - Implement CBOR bytes emission from `ConfixElement` with deterministic map ordering and minimal integer widths as specified.
   - For `ConfixCbor`, use this path. For JSON/YAML, use the existing emission logic modified to take `ConfixElement`.

6. **Pre-commit Instructions**
   - Complete pre-commit steps to make sure proper testing, verifications, reviews, and reflections are done.
