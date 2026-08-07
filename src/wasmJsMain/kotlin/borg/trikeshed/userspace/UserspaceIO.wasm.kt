package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.ByteBuffer

private object WasmFileRegistry {
    private var nextId = 1
    fun open(): FileImpl = FileImpl(nextId++)
}

private class WasmUserspaceChannelBackend : UserspaceChannelBackend {
}

actual fun openUserspaceChannelBackend(entries: Int): UserspaceChannelBackend = WasmUserspaceChannelBackend()

