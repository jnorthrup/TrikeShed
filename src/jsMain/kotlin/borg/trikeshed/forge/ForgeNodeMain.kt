package borg.trikeshed.forge

fun main() {
    val html = forgeAtlasHtml()
    if (isBrowserRuntime()) {
        renderBrowser(html)
    } else {
        println(html)
    }
}

private fun isBrowserRuntime(): Boolean = js(
    "typeof window !== 'undefined' && typeof document !== 'undefined'"
) as Boolean

private fun renderBrowser(html: String) {
    js(
        "document.open(); document.write(html); document.close();"
    )
}
