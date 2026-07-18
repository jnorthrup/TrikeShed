package borg.trikeshed.forge.window

/**
 * Per-target Window Manager for launching and rendering the Forge PWA.
 */
interface ForgeWindowManager {
    /**
     * Launch and render the HTML shell.
     * @param html The generated HTML string to render or serve.
     */
    fun launch(html: String)
}
