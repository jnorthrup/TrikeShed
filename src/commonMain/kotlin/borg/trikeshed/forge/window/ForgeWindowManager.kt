package borg.trikeshed.forge.window

import kotlin.coroutines.CoroutineContext
import kotlinx.datetime.Clock

/**
 * Unified Forge window manager — the single CCEK-aware interface for
 * launching, binding, scripting, and snapshotting the Forge HTML shell.
 *
 * Replaces the former dual-interface split between
 * `forge.window.ForgeWindowManager` (typed API) and
 * `forge.shell.spi.ForgeWindowManager` (raw-string CCEK element).
 * Both are merged here: typed methods for safety, CCEK Key for
 * coroutine context placement.
 */
interface ForgeWindowManager : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = Key

    companion object Key : CoroutineContext.Key<ForgeWindowManager>

    /** Launch and render the HTML shell. */
    fun launch(html: String)

    /** Bind the HTML shell to the underlying surface. */
    fun bind(html: String)

    /** Inject a typed script snippet into the bound surface. */
    fun injectScript(snippet: ScriptSnippet)

    /** Inject raw script text (convenience overload for CCEK callers). */
    fun injectScript(script: String) =
        injectScript(ScriptSnippet(id = "ccek-${script.hashCode()}", source = script))

    /** Dispatch a structured event into the surface. */
    fun dispatchEvent(event: WindowEvent)

    /** Dispatch a raw event by type and payload string (CCEK convenience). */
    fun dispatchEvent(event: String, payload: String) =
        dispatchEvent(WindowEvent(type = event, payload = payload, timestampMillis = Clock.System.now().toEpochMilliseconds()))

    /**
     * Capture a snapshot of the current surface state — for tests and
     * headless verification.
     */
    fun captureSnapshot(): WindowSnapshot
}
