@file:JsModule("blessed")
@file:JsNonModule
package dreamer.terminal

// Top-level factory functions: blessed.screen(), blessed.box(), etc.
external fun screen(opts: dynamic = definedExternally): Screen
external fun box(opts: dynamic = definedExternally): Box
external fun list(opts: dynamic = definedExternally): BlessedList
external fun text(opts: dynamic = definedExternally): BlessedText

external class Screen {
    val width: Int
    val height: Int
    fun render()
    fun destroy()
    fun key(keys: Array<String>, handler: (ch: String, key: dynamic) -> Unit)
    fun append(element: dynamic)
}

external class Box {
    fun setContent(text: String)
    fun setLabel(text: String)
    fun append(element: dynamic)
    fun hide()
    fun show()
}

external class BlessedList {
    fun addItem(text: String)
    fun setItems(items: Array<String>)
    fun scroll(offset: Int)
    fun clearItems()
    fun setContent(text: String)
    fun setLabel(text: String)
    fun hide()
    fun show()
}

external class BlessedText {
    fun setContent(text: String)
    fun setLabel(text: String)
    fun hide()
    fun show()
}
