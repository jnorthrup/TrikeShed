package borg.trikeshed.windowtoolkit.confix

// The renderer engine abstraction
interface ConfixRenderer {
    fun render(entry: Any)
    fun processInputToken(token: String)
}

// A widget state holder that intercepts SAX events
class ConfixBlackboardWidget : ConfixRenderer {

    private val tokenBuffer = mutableListOf<String>()

    // Renders the ConfixDoc by walking it as SAX events
    override fun render(entry: Any) {
        // abstract kernel parsing fallback
    }

    override fun processInputToken(token: String) {
        tokenBuffer.add(token)
    }

    fun getBufferedTokens(): List<String> = tokenBuffer.toList()
}