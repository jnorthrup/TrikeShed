package borg.trikeshed.ffi.nio

import kotlin.coroutines.CoroutineContext

// Simplified due to native target dependency limitations in root project
interface KeyedService : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*>
}

interface MlxNioWireProto : KeyedService {
    companion object Key : CoroutineContext.Key<MlxNioWireProto>
    override val key: CoroutineContext.Key<*> get() = Key

    suspend fun processMlxBuffer(buffer: ByteArray): ByteArray
}

suspend fun invokeMlxNioWireProto(proto: MlxNioWireProto, buffer: ByteArray): ByteArray {
    // Simulate requireCcekScope due to dependency scope limitations on native.
    return proto.processMlxBuffer(buffer)
}
