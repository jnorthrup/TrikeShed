package borg.trikeshed.windowtoolkit.confix

// TODO: Pull in full org.xvm.activejs when KMP variant resolution fixes are applied
// import org.xvm.activejs.BlackBoardEntry
// import org.xvm.activejs.SaxEvent

// The renderer engine abstraction
interface ConfixRenderer {
    fun render(entry: Any) // TODO: Swap to BlackBoardEntry
    fun processInputToken(token: String)
}

// A widget state holder that intercepts SAX events
class ConfixBlackboardWidget : ConfixRenderer {

    private val tokenBuffer = mutableListOf<String>()

    // Renders the ConfixDoc by walking it as SAX events
    override fun render(entry: Any) {
        // abstract kernel parsing fallback
        // val doc = entry.doc
        // when (doc) {
        //     is org.xvm.activejs.ConfixDoc -> doc.saxWalk { event -> ... }
        // }
    }

    override fun processInputToken(token: String) {
        tokenBuffer.add(token)
    }

    fun getBufferedTokens(): List<String> = tokenBuffer.toList()
}
