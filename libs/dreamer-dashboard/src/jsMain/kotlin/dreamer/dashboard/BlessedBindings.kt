@file:JsModule("blessed")
@file:JsNonModule
package dreamer.dashboard

// ── Screen (full terminal canvas) ──────────────────────────────────────────
external class Screen {
    val width: Int
    val height: Int
    fun render()
    fun destroy()
    fun key(keys: Array<String>, handler: (ch: String, key: dynamic) -> Unit)
    fun append(element: dynamic)
    companion object {
        fun screen(opts: dynamic = definedExternally): Screen
    }
}

// ── Box (rectangular container) ───────────────────────────────────────────
external class Box {
    fun setContent(text: String)
    fun setLabel(text: String)
    fun append(element: dynamic)
    fun hide()
    fun show()
    companion object {
        fun box(opts: dynamic = definedExternally): Box
    }
}

// ── List (scrollable item list for logs) ──────────────────────────────────
external class BlessedList {
    fun addItem(text: String)
    fun setItems(items: Array<String>)
    fun scroll(offset: Int)
    fun clearItems()
    fun setContent(text: String)
    fun setLabel(text: String)
    fun hide()
    fun show()
    companion object {
        fun list(opts: dynamic = definedExternally): BlessedList
    }
}

// ── Text (single-line or multi-line static text) ──────────────────────────
external class BlessedText {
    fun setContent(text: String)
    fun setLabel(text: String)
    fun hide()
    fun show()
    companion object {
        fun text(opts: dynamic = definedExternally): BlessedText
    }
}
