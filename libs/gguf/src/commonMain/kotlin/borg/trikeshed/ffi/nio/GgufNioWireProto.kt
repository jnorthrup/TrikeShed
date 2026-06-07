package borg.trikeshed.ffi.nio

import kotlin.coroutines.CoroutineContext

// Simplified due to native target dependency limitations in root project
interface KeyedService : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*>
}

interface GgufNioWireProto : KeyedService {
    companion object Key : CoroutineContext.Key<GgufNioWireProto>
    override val key: CoroutineContext.Key<*> get() = Key

    suspend fun processGgufBuffer(buffer: ByteArray): ByteArray
}

suspend fun invokeGgufNioWireProto(proto: GgufNioWireProto, buffer: ByteArray): ByteArray {
    // Simulate requireCcekScope due to dependency scope limitations on native.
    return proto.processGgufBuffer(buffer)
}
