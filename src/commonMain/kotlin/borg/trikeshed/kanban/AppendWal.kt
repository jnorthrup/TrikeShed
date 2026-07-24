package borg.trikeshed.kanban

expect class AppendWal(path: String) {
    fun append(key: String, payload: ByteArray): Long
    fun replay(): Sequence<Pair<String, ByteArray>>
    fun close()
}
