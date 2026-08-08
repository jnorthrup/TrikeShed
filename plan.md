1. **Remove `htxMutex` from `FlywheelDriver.kt`**
   - Use `replace_with_git_merge_diff` to remove `private val htxMutex = Mutex()` and all `htxMutex.withLock { ... }` blocks from `src/jvmMain/kotlin/borg/trikeshed/jules/FlywheelDriver.kt`.
   - Verify the changes using `run_in_bash_session` with `cat src/jvmMain/kotlin/borg/trikeshed/jules/FlywheelDriver.kt | grep htxMutex` which should return empty.

2. **Fix `JvmChannelOperations` to be non-blocking**
   - Use `replace_with_git_merge_diff` to update `connect` in `src/jvmMain/kotlin/borg/trikeshed/userspace/nio/channels/spi/JvmChannelOperations.kt`:
     - Remove `ch.configureBlocking(true)`.
     - Use `ioWorkers.execute { ... }` to wrap `java.net.InetSocketAddress` instantiation and `ch.connect(address)` in an async block to prevent synchronous DNS stalls. (Verified `ioWorkers` is an `internal val` `ThreadPoolExecutor` inside `JvmChannelOperations`).
     - Ensure the method still correctly registers `Interest.CONNECT`.
   - Verify the changes using `run_in_bash_session` with `git diff src/jvmMain/kotlin/borg/trikeshed/userspace/nio/channels/spi/JvmChannelOperations.kt`.

3. **Update `JvmTlsCodecBackend` to run delegated tasks asynchronously**
   - Use `replace_with_git_merge_diff` to update `runDelegatedTasks` to be `suspend` and use `withContext(Dispatchers.IO)` in `src/jvmMain/kotlin/borg/trikeshed/reactor/JvmTlsCodecBackend.kt`.
   - Update its caller functions (`unwrapDownstream`, `wrapApplicationCiphertext`, `drainHandshakeCiphertext`) to be `suspend`.
   - Verify the changes using `run_in_bash_session` with `git diff src/jvmMain/kotlin/borg/trikeshed/reactor/JvmTlsCodecBackend.kt`.

4. **Delete `NioHttp.kt`**
   - Use `run_in_bash_session` to run `rm src/jvmMain/kotlin/keymux/transport/NioHttp.kt` since `NioHttp` is not used anywhere else (verified via grep).

5. **Multiplatform TLS bindings**
   - Use `replace_with_git_merge_diff` to modify `src/linuxMain/kotlin/borg/trikeshed/userspace/nio/spi/PlatformProviders.linux.kt`, `src/jsMain/kotlin/borg/trikeshed/userspace/nio/spi/PlatformProviders.js.kt`, `src/macosMain/kotlin/borg/trikeshed/userspace/nio/spi/PlatformProviders.macos.kt`, and `src/wasmJsMain/kotlin/borg/trikeshed/userspace/nio/spi/PlatformProviders.wasm.kt`.
   - I will add a `class StubTlsCodecBackend : TlsCodecBackend { ... }` stub throwing `UnsupportedOperationException` and register it in `platformNioProviders()` alongside `HtxReactorElement(channelOperations = channelOperations, tlsBackend = StubTlsCodecBackend())` where `channelOperations` is defined as `LinuxChannelOperations()`, `JsChannelOperations()`, `PosixChannelOperations()`, and `WasmChannelOperations()` respectively.
   - Verify the changes using `run_in_bash_session` with `git diff`.

6. **Verify Build Compliance and Testing**
   - Use `run_in_bash_session` to run `./gradlew jvmMainClasses --console=plain --no-daemon`.
   - Use `run_in_bash_session` to run `./gradlew jvmTest --tests "*JvmTlsCodecBackendTest*" --no-daemon`.
   - Use `run_in_bash_session` to run `./gradlew jvmTest --tests "*supervisorJobCanProveRemoteHttpsSite*" -DtrikeShed.liveTls=true --no-daemon`.

7. Complete pre-commit steps to ensure proper testing, verification, review, and reflection are done.
