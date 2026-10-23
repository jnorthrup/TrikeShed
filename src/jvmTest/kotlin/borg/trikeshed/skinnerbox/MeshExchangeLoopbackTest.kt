package borg.trikeshed.skinnerbox

import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.htx.openHtxElement
import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import kotlin.test.*

/** Real sockets on both sides: common listener and HTX userspace NIO, no in-memory transport substitute. */
class MeshExchangeLoopbackTest {
    @Test fun signedExchangeUsesTheBoundListenerAndPreservesSharedCompletion() = runBlocking<Unit> {
        withTimeout(30_000) {
            val htx = openHtxElement()
            var receiver: MeshExchangeElement? = null
            val listener = MeshListener.open(this) { method, path, _, body ->
                checkNotNull(receiver).route()(method, path, body, null)
            }
            val senderPeer = meshPeer(11)
            val receiverPeer = MeshPeer(meshIdentity(12).publicKey, "rack-12", "http://${listener.address}", Subnet.parse("tenant"))
            var executions = 0
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val target = MeshExchangeElement(receiverPeer, meshIdentity(12), s_[senderPeer], coroutineContext,
                execute = { peer, task ->
                    assertEquals(senderPeer.id, peer.id)
                    executions++
                    entered.complete(Unit)
                    release.await()
                    task.payload.reversedArray()
                })
            receiver = target
            val sender = MeshExchangeElement(senderPeer, meshIdentity(11), s_[receiverPeer], coroutineContext + htx,
                execute = { _, _ -> error("Sender has no inbound task") })
            try {
                val task = MeshTask("tenant.rows", "GET", ByteArray(8192) { (it % 251).toByte() })
                val first = async { sender.dispatchReply(receiverPeer, task) }
                entered.await()
                val second = async(start = CoroutineStart.UNDISPATCHED) { sender.dispatch(receiverPeer, task) }
                yield()
                release.complete(Unit)
                val reply = first.await()
                assertContentEquals(task.payload.reversedArray(), reply.payload)
                assertContentEquals(reply.payload, second.await())
                assertEquals(receiverPeer.id, reply.peer.id)
                assertEquals(task.id, reply.taskId)
                assertEquals(receiverPeer.id, MeshEnvelope.decode(reply.signedBytes).source)
                assertEquals(1, executions)
            } finally {
                release.complete(Unit)
                sender.drain()
                listener.drain()
                target.drain()
                htx.close()
            }
        }
    }
}
