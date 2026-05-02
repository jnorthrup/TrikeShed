package borg.trikeshed.miniduck

// Small test-only stubs to satisfy concurrency tests when libs:miniduck isn't available
class DocRowVec(val keys: List<String>, val cells: List<Any?>) {
    val isShell: Boolean get() = keys.isEmpty() && cells.isEmpty()
    operator fun get(index: Int): Any? = cells.getOrNull(index)
}

sealed class BlockRowVec {
    abstract val rowCount: Int
    abstract val child: MutableList<DocRowVec>?
    companion object {
        fun mutable(): MutableBlockRowVec = MutableBlockRowVec(mutableListOf())
    }
}

class MutableBlockRowVec(override val child: MutableList<DocRowVec>) : BlockRowVec() {
    override val rowCount: Int get() = child.size
    fun append(row: DocRowVec) { child.add(row) }
    fun seal(): BlockRowVec = SealedBlockRowVec(child.toList())
}

class SealedBlockRowVec(val rows: List<DocRowVec>) : BlockRowVec() {
    override val rowCount: Int get() = rows.size
    override val child: MutableList<DocRowVec>? = rows.toMutableList()
}
