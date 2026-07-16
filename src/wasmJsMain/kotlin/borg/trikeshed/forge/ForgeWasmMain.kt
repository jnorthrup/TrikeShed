package borg.trikeshed.forge

/**
 * WASM/JS Browser entry point for Forge workspace.
 * wasmJs targets browser only - no Node.js support needed.
 */
@JsName("renderHtml")
private external fun renderHtml(html: String)

fun main() {
    val html = forgeAppHtml()
    renderHtml(html)
}
