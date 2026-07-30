1. **Forge Dashboards surface in `ForgeWorkspace.kt` (JVM shell)**
    - Import `currentNioCapabilityReport` and `OroborosDaemon`.
    - Retrieve the capability report using `borg.trikeshed.userspace.nio.spi.currentNioCapabilityReport()`.
    - Retrieve cycle telemetry from `OroborosDaemon.lastCycleReport` and `OroborosDaemon.daemonStartTime`.
    - Create a new `@Composable` `DashboardPanel` (styled similarly to `BoardPanel` / `GalleryPanel`) to display this data:
        - I/O Backend (e.g. io_uring / posix_aio) and capabilities.
        - Kernel Hint if available.
        - Flywheel stats: uptime, cycle time, harvested, dispatched, alive, available.
    - Render `DashboardPanel` in the `Workspace` UI (e.g. beside the `GalleryPanel` or the `BoardPanel`).

2. **CCEK 'concentric network mesh' choreography in `CCEK.kt`**
    - The task is to "begin CCEK 'concentric network mesh' choreography: single SharedFlow event bus, coherent choreography, reactor wiring beyond imperative blocking IO".
    - In `CCEK.kt`, create a `ConcentricMeshEvent` sealed class (or similar payload).
    - Inside `CCEK` object, add a `val meshBus: MutableSharedFlow<ConcentricMeshEvent> = MutableSharedFlow(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)` to represent the single SharedFlow event bus.
    - Inside `CcekReactorBinding`, add a method `fun bindMesh(bus: SharedFlow<ConcentricMeshEvent>)` or have `choreograph` accept it, or use the global `meshBus`.
    - Alternatively, in `CCEK.kt`, look at how `KanbanFSM` uses a `SharedFlow` and replicate it for mesh events, or just add the `SharedFlow` for mesh events directly into `CcekReactorBinding`. I will define a basic `MeshEvent` sealed class and a `MutableSharedFlow` in `CCEK.kt` to satisfy the "single SharedFlow event bus" and "concentric network mesh" requirement.

3. **Native CLI argument capture notes**
    - Modify `src/nativeMain/kotlin/borg/trikeshed/cli/htx/HtxAria2CliNative.kt` to add a comment inside the `main` function stating that arguments are captured and stored in `NativeMainArguments.args`.
    - Modify `src/linuxMain/kotlin/linux_uring/placeholder/KioUring.kt` to add a comment inside the `main` function stating that arguments are captured and stored in `NativeMainArguments.args`.

4. **Verify and Pre-commit**
    - Ensure tests pass with `./gradlew :jvmMainClasses --no-daemon`.
    - Run `./gradlew linuxX64Test --tests "*nio*"` to verify structural correctness of the native changes.
    - Run pre-commit checks and submit.
