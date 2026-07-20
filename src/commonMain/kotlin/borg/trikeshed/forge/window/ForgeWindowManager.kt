package borg.trikeshed.forge.window

interface ForgeWindowManager {
    /**
     * Launch and render the HTML shell.
     * @param html The generated HTML string to render or serve.
     */
    fun launch(html: String)

    /** Bind the HTML shell to the underlying surface. */
    fun bind(html: String)

    /** Inject a script snippet into the bound surface. */
    fun injectScript(snippet: ScriptSnippet)

    /** Dispatch a structured event into the surface (e.g., a blackboard
     *  update from the JVM). The event is delivered as a serialised JSON
     *  payload into the page's global `window.dispatchEvent`. */
    fun dispatchEvent(event: WindowEvent)

    /** Capture a snapshot of the current surface state — for tests and
     *  headless verification. The default impl returns a WindowSnapshot
     *  marked as "noop". */
    fun captureSnapshot(): WindowSnapshot
}
