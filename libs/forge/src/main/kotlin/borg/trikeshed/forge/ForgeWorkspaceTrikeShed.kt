package borg.trikeshed.forge

/**
 * TrikeShed-facing Forge workspace entrypoint.
 *
 * This class intentionally keeps the public "TrikeShed-backed" name available
 * while the real PRELOAD-native backend is still being lowered into cursor /
 * Confix / blackboard storage. The current green implementation delegates to
 * the Forge workspace contract implementation so the Forge app, test,
 * harness stand up from the root Gradle build instead of carrying a broken
 * experimental dependency on Miniduck/Confix modules that are not wired into the
 * Forge JVM build yet.
 *
 * The backend seam is explicit: replace [delegate] with a cursor-native
 * implementation once the storage dependencies compile as normal Forge
 * dependencies. Until then this class is a working adapter, not a second
 * half-implemented store.
 */
class ForgeWorkspaceTrikeShed(
    private val delegate: ForgeWorkspace = ForgeWorkspaceImpl(),
) : ForgeWorkspace by delegate {

    /** Human-readable lowering target for UI diagnostics. */
    val storageContract: String =
        "ForgeWorkspace -> Cursor rows -> Confix body/facade -> Blackboard provenance -> CCEK lifecycle"
}
