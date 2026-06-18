package borg.trikeshed.windowtoolkit.confix

/**
 * The renderer engine abstraction for Confix docs.
 * Supports kernel-only rendering;  backend available when KMP variant resolution is fixed.
 */
interface ConfixRenderer {
    fun render(entry: Any) // Swap to ConfixBlackboardEntry when  backend is available
    fun processInputToken(token: String)
}

/**
 * A widget state holder that intercepts SAX events.
 */
class ConfixBlackboardWidget : ConfixRenderer {

    private val tokenBuffer = mutableListOf<String>()

    // Renders the ConfixDoc by walking it as SAX events
    override fun render(entry: Any) {
        // Abstract kernel parsing fallback
        // When  backend is available: (entry as ConfixBlackboardEntry).doc.saxWalk { ... }
    }

    override fun processInputToken(token: String) {
        tokenBuffer.add(token)
    }

    fun getBufferedTokens(): List<String> = tokenBuffer.toList()
}