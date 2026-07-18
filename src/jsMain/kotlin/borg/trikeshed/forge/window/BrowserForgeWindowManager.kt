package borg.trikeshed.forge.window

/**
 * Browser-based Window Manager that writes HTML directly into the document.
 */
class BrowserForgeWindowManager : ForgeWindowManager {
    override fun launch(html: String) {
        val isBrowser = js("typeof window !== 'undefined' && typeof document !== 'undefined'") as Boolean
        if (isBrowser) {
            js("document.open(); document.write(html); document.close();")
        }
    }
}
