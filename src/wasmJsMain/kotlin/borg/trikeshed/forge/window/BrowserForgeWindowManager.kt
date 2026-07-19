package borg.trikeshed.forge.window

/**
 * WASM/JS Browser-based Window Manager that writes HTML directly into the document.
 */
class BrowserForgeWindowManager : ForgeWindowManager {
    override fun launch(html: String) {
        if (isBrowser()) {
            writeHtml(html)
        }
    }
}

fun isBrowser(): Boolean = js("typeof window !== 'undefined' && typeof document !== 'undefined'")

fun writeHtml(html: String): Unit = js("document.open(); document.write(html); document.close();")
