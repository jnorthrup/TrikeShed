package borg.trikeshed.miniduck

/**
 * Compile-stage block codec seam.
 *
 * This intentionally keeps the durable byte codec behind a tiny contract while
 * MiniDuck stabilizes around RowVec/BlockRowVec. The in-process token format is
 * enough for WAL/checkpoint proof paths; durable Drain codecs can replace this
 * implementation without changing call sites.
 */
object MiniDuckBlockCodec {
    private val blocks = mutableMapOf<String, BlockRowVec>()
    private var nextId = 0L

    fun encode(block: BlockRowVec): String {
        val id = "miniduck-block-${nextId++}"
        blocks[id] = block.seal()
        return id
    }

    fun decode(text: String): BlockRowVec =
        blocks[text]?.seal() ?: BlockRowVec.sealed(emptyList())
}
