package borg.trikeshed.kanban

actual class AppendWal actual constructor(path: String) {
    actual fun append(key: String, payload: ByteArray): Long {
        error("Not implemented in Wasm")
    }

    actual fun replay(): Sequence<Pair<String, ByteArray>> {
        error("Not implemented in Wasm")
    }

    actual fun close() {
        error("Not implemented in Wasm")
    }
}
