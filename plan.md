1. **Fix `Channel.kt`**:
   - Remove `TODO()` stubs from the `Channel` interface methods (`isOpen` and `close`) making them purely abstract.

2. **Fix `Channels.kt`**:
   - Replace `TODO("NIO common stub")` implementations in `Channels.companion object` with `throw UnsupportedOperationException("Channels operations are not supported in commonMain")` to properly stub out the static methods.

3. **Fix `GatheringByteChannel.kt`**:
   - Remove `TODO()` stubs from the `GatheringByteChannel` interface methods (`write`) making them purely abstract.

4. **Fix `InterruptibleChannel.kt`**:
   - Remove the `TODO()` stub from `InterruptibleChannel.close()` making it purely abstract.

5. **Fix `ReadableByteChannel.kt`**:
   - Remove the `TODO()` stub from `ReadableByteChannel.read()` making it purely abstract.

6. **Fix `ScatteringByteChannel.kt`**:
   - Remove the `TODO()` stub from `ScatteringByteChannel.read()` making it purely abstract.

7. **Test Compilation**:
   - Use `run_in_bash_session` to execute `./gradlew :jvmMainClasses --no-daemon` to ensure compilation is unbroken.

8. **Pre-commit**:
   - Complete pre-commit steps to ensure proper testing, verification, review, and reflection are done.
