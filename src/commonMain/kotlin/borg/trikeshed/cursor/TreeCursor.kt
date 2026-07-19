package borg.trikeshed.cursor

data class TreeCursor(
    val row: RowVec,
    val children: Sequence<TreeCursor> = emptySequence(),
) {
    fun flatten(): Sequence<RowVec> = sequence {
        yield(row)
        for (child in children) {
            yieldAll(child.flatten())
        }
    }

    fun navigate(path: IntArray): TreeCursor {
        var current = this
        for (index in path) {
            current = current.children.elementAt(index)
        }
        return current
    }
}
