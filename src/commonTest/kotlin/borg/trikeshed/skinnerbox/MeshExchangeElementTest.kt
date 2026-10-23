package borg.trikeshed.skinnerbox

import borg.trikeshed.lib.*
import borg.trikeshed.htx.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MeshExchangeElementTest {
    @Test fun duplicateAuthorityAndTaskShareExecutionButBindTheirOwnNonce() = runTest {
        val source = meshPeer(1)
        val local = meshPeer(2)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var executions = 0
        val element = MeshExchangeElement(local, meshIdentity(2), s_[source], coroutineContext,
            execute = { peer, task ->
                assertEquals(source.id, peer.id)
                executions++
                started.complete(Unit)
                finish.await()
                task.payload
            }, nowMillis = { 100_000 })
        val task = MeshTask("tenant.rows", "RETAIN", byteArrayOf(4, 5))
        val first = MeshEnvelope.request(source, local, task, meshIdentity(1), 100_000, ByteArray(32) { 1 })
        val second = MeshEnvelope.request(source, local, task, meshIdentity(1), 100_000, ByteArray(32) { 2 })
        try {
            val a = async { element.receive(first.bytes()) }
            val b = async { element.receive(second.bytes()) }
            started.await()
            runCurrent()
            assertEquals(1, executions)
            finish.complete(Unit)
            val aBytes = a.await()
            MeshEnvelope.decode(aBytes).verifyResponse(first, local, 100_000)
            MeshEnvelope.decode(b.await()).verifyResponse(second, local, 100_000)
            assertContentEquals(aBytes, element.receive(first.bytes()))
            assertEquals(1, executions)
        } finally { finish.complete(Unit); element.drain() }
    }

    @Test fun cancelledCallerCannotCancelAcceptedExecutionAndDrainWaits() = runTest {
        val source = meshPeer(1)
        val local = meshPeer(2)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var committed = false
        val element = MeshExchangeElement(local, meshIdentity(2), s_[source], coroutineContext,
            execute = { _, _ -> started.complete(Unit); finish.await(); committed = true; byteArrayOf(1) },
            nowMillis = { 100_000 })
        val request = MeshEnvelope.request(source, local, MeshTask("tenant", "PUT", byteArrayOf()),
            meshIdentity(1), 100_000, ByteArray(32))
        val caller = launch { element.receive(request.bytes()) }
        started.await()
        caller.cancelAndJoin()
        val draining = async { element.drain() }
        runCurrent()
        assertFalse(draining.isCompleted)
        finish.complete(Unit)
        draining.await()
        assertTrue(committed)
        assertFailsWith<IllegalStateException> { element.receive(request.bytes()) }
    }

    @Test fun boundedReplayAndWaitersRefuseInsteadOfSpawningAHerd() = runTest {
        val source = meshPeer(1)
        val local = meshPeer(2)
        val finish = CompletableDeferred<Unit>()
        val element = MeshExchangeElement(local, meshIdentity(2), s_[source], coroutineContext,
            execute = { _, _ -> finish.await(); byteArrayOf() },
            limits = MeshExchangeLimits(workers = 1, queueCapacity = 1, maxWaiters = 1, replayEntries = 1),
            nowMillis = { 100_000 })
        val request = MeshEnvelope.request(source, local, MeshTask("tenant", "PUT", byteArrayOf()),
            meshIdentity(1), 100_000, ByteArray(32))
        try {
            val admitted = async { element.receive(request.bytes()) }
            runCurrent()
            assertFailsWith<MeshOverloaded> { element.receive(request.bytes()) }
            val second = MeshEnvelope.request(source, local, MeshTask("tenant", "PUT", byteArrayOf(1)),
                meshIdentity(1), 100_000, ByteArray(32) { 2 })
            assertFailsWith<MeshOverloaded> { element.receive(second.bytes()) }
            val reuse = MeshEnvelope.request(source, local, MeshTask("tenant", "PUT", byteArrayOf(2)),
                meshIdentity(1), 100_000, ByteArray(32))
            assertFailsWith<IllegalArgumentException> { element.receive(reuse.bytes()) }
            finish.complete(Unit)
            admitted.await()
        } finally { finish.complete(Unit); element.drain() }
    }

    @Test fun taskDeadlineAndParentCancellationSettleEveryAcceptedWaiter() = runTest {
        val source = meshPeer(1)
        val local = meshPeer(2)
        val owner = SupervisorJob(coroutineContext[Job])
        val element = MeshExchangeElement(local, meshIdentity(2), s_[source], coroutineContext + owner,
            execute = { _, _ -> awaitCancellation() },
            limits = MeshExchangeLimits(taskTimeoutMillis = 10, rpcTimeoutMillis = 10), nowMillis = { 100_000 })
        val request = MeshEnvelope.request(source, local, MeshTask("tenant", "GET", byteArrayOf()),
            meshIdentity(1), 100_000, ByteArray(32))
        assertFailsWith<TimeoutCancellationException> { element.receive(request.bytes()) }
        val second = MeshEnvelope.request(source, local, MeshTask("tenant", "GET", byteArrayOf(1)),
            meshIdentity(1), 100_000, ByteArray(32) { 1 })
        val pending = async { runCatching { element.receive(second.bytes()) } }
        runCurrent()
        owner.cancel()
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
        element.drain()
        owner.join()
    }

    @Test fun aggregateByteAdmissionRefusesBeforeTheHandlerRuns() = runTest {
        val source = meshPeer(1)
        val local = meshPeer(2)
        var executions = 0
        val element = MeshExchangeElement(local, meshIdentity(2), s_[source], coroutineContext,
            execute = { _, _ -> executions++; byteArrayOf() },
            limits = MeshExchangeLimits(maxAdmittedBytes = MeshEnvelope.MAX_WIRE_BYTES.toLong()),
            nowMillis = { 100_000 })
        val request = MeshEnvelope.request(source, local, MeshTask("tenant", "PUT", ByteArray(1024)),
            meshIdentity(1), 100_000, ByteArray(32))
        try {
            assertFailsWith<MeshOverloaded> { element.receive(request.bytes()) }
            assertEquals(0, executions)
        } finally { element.drain() }
    }

    @Test fun controlIngressRetainsItsWorkersReplayEntriesAndBytesUnderOrdinarySaturation() = runTest {
        val source = meshPeer(1)
        val local = meshPeer(2)
        val finish = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val element = MeshExchangeElement(local, meshIdentity(2), s_[source], coroutineContext,
            execute = { _, task ->
                if (task.verb == "row.put") { started.complete(Unit); finish.await() }
                task.payload
            }, limits = MeshExchangeLimits(workers = 1, queueCapacity = 1, replayEntries = 1,
                maxAdmittedBytes = 5L * MeshEnvelope.MAX_WIRE_BYTES,
                controlWorkers = 1, controlQueueCapacity = 1, controlReplayEntries = 1),
            nowMillis = { 100_000 })
        fun request(verb: String, nonce: Int) = MeshEnvelope.request(source, local,
            MeshTask("tenant", verb, byteArrayOf(nonce.toByte())), meshIdentity(1),
            100_000, ByteArray(32) { nonce.toByte() })
        try {
            val ordinary = async { element.receive(request("row.put", 1).bytes()) }
            started.await()
            assertFailsWith<MeshOverloaded> { element.receive(request("row.put", 2).bytes()) }
            // A changed verb cannot evade the nonce binding by entering another admission class.
            assertFailsWith<IllegalArgumentException> { element.receive(request("mesh.retain", 1).bytes()) }
            val control = request("mesh.retain", 3)
            MeshEnvelope.decode(element.receive(control.bytes())).verifyResponse(control, local, 100_000)
            assertFalse(ordinary.isCompleted)
            val nextControl = request("mesh.find", 4)
            MeshEnvelope.decode(element.receive(nextControl.bytes())).verifyResponse(nextControl, local, 100_000)
            assertFailsWith<MeshReplayUnavailable> { element.receive(control.bytes()) }
            finish.complete(Unit)
            ordinary.await()
        } finally { finish.complete(Unit); element.drain() }
    }

    @Test fun controlEgressProgressesWhileOrdinaryEgressIsBlocked() = runTest {
        val local = meshPeer(1)
        val remote = meshPeer(2)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val htx = HtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                val envelope = MeshEnvelope.decode(request.body.toArray())
                val task = envelope.verifyRequest(local, remote, 100_000)
                if (task.verb == "row.put") { started.complete(Unit); finish.await() }
                val response = MeshEnvelope.response(envelope, task.payload, meshIdentity(2), 100_000)
                return HtxExchangeResult(state.copy(response = HtxResponse(200, ByteSeries(response.bytes()))))
            }
        }, parentJob = coroutineContext[Job])
        htx.open()
        val element = MeshExchangeElement(local, meshIdentity(1), s_[remote], coroutineContext + htx,
            execute = { _, _ -> error("Unexpected ingress") },
            limits = MeshExchangeLimits(workers = 1, queueCapacity = 1,
                maxAdmittedBytes = 4L * MeshEnvelope.MAX_WIRE_BYTES), nowMillis = { 100_000 })
        try {
            val ordinary = async { element.dispatch(remote, MeshTask("tenant", "row.put", byteArrayOf(1))) }
            started.await()
            assertFailsWith<MeshOverloaded> {
                element.dispatch(remote, MeshTask("tenant", "row.put", byteArrayOf(2)))
            }
            assertContentEquals(byteArrayOf(3), element.dispatch(remote, MeshTask("tenant", "mesh.retain", byteArrayOf(3))))
            assertFalse(ordinary.isCompleted)
            finish.complete(Unit)
            ordinary.await()
        } finally { finish.complete(Unit); element.drain(); htx.close() }
    }

    @Test fun gracefulDrainAllowsAdmittedHandlersNestedControlEffects() = runTest {
        val source = meshPeer(1)
        val local = meshPeer(2)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var retained = false
        lateinit var element: MeshExchangeElement
        element = MeshExchangeElement(local, meshIdentity(2), s_[source], coroutineContext,
            execute = { _, task ->
                if (task.verb == "mesh.retain") { retained = true; byteArrayOf(7) }
                else {
                    started.complete(Unit)
                    finish.await()
                    element.dispatch(local, MeshTask("tenant", "mesh.retain", byteArrayOf()))
                }
            }, limits = MeshExchangeLimits(workers = 1, controlWorkers = 1), nowMillis = { 100_000 })
        val request = MeshEnvelope.request(source, local, MeshTask("tenant", "row.put", byteArrayOf()),
            meshIdentity(1), 100_000, ByteArray(32))
        val caller = async { element.receive(request.bytes()) }
        started.await()
        val draining = async { element.drain() }
        runCurrent()
        assertFalse(draining.isCompleted)
        assertFailsWith<IllegalStateException> { element.receive(request.bytes()) }
        finish.complete(Unit)
        val reply = MeshEnvelope.decode(caller.await())
        reply.verifyResponse(request, local, 100_000)
        assertContentEquals(byteArrayOf(7), reply.payload)
        draining.await()
        assertTrue(retained)
    }

    @Test fun indivisibleFrameLargerThanCacheStrokeStillHasStrictWireBounds() = runTest {
        val source = meshPeer(1)
        val local = meshPeer(2)
        val element = MeshExchangeElement(local, meshIdentity(2), s_[source], coroutineContext,
            execute = { _, task -> task.payload },
            limits = MeshExchangeLimits(stroke = MeshStroke(1, 1024)), nowMillis = { 100_000 })
        val task = MeshTask("tenant", "row.put", ByteArray(MeshTask.MAX_PAYLOAD_BYTES) { 7 })
        val request = MeshEnvelope.request(source, local, task, meshIdentity(1), 100_000, ByteArray(32))
        try {
            val wire = element.receive(request.bytes())
            assertTrue(wire.size <= MeshEnvelope.MAX_WIRE_BYTES)
            assertContentEquals(task.payload, MeshEnvelope.decode(wire).payload)
        } finally { element.drain() }
    }


    @Test fun completedReplySlotsAreReusableButEvictedNoncesCannotExecuteAgain() = runTest {
        val source = meshPeer(1)
        val local = meshPeer(2)
        var executions = 0
        val element = MeshExchangeElement(local, meshIdentity(2), s_[source], coroutineContext,
            execute = { _, task -> executions++; task.payload },
            limits = MeshExchangeLimits(replayEntries = 2, controlReplayEntries = 1,
                maxAdmittedBytes = 5L * MeshEnvelope.MAX_WIRE_BYTES,
                maxControlAdmittedBytes = 5L * MeshEnvelope.MAX_WIRE_BYTES), nowMillis = { 100_000 })
        fun request(verb: String, nonce: Int, payload: Int = nonce) = MeshEnvelope.request(source, local,
            MeshTask("tenant", verb, byteArrayOf(payload.toByte())), meshIdentity(1),
            100_000, ByteArray(32) { nonce.toByte() })
        try {
            // Fixed time deliberately prevents expiry from hiding the old completed-slot ceiling.
            for (n in 1..40) {
                val ordinary = request("row.get", n)
                MeshEnvelope.decode(element.receive(ordinary.bytes())).verifyResponse(ordinary, local, 100_000)
                val control = request("mesh.find", 100 + n)
                MeshEnvelope.decode(element.receive(control.bytes())).verifyResponse(control, local, 100_000)
            }
            assertEquals(80, executions)
            assertFailsWith<MeshReplayUnavailable> { element.receive(request("row.get", 1).bytes()) }
            assertFailsWith<MeshReplayUnavailable> { element.receive(request("mesh.find", 101).bytes()) }
            assertFailsWith<IllegalArgumentException> { element.receive(request("row.get", 1, 99).bytes()) }
            val latest = request("mesh.find", 140)
            MeshEnvelope.decode(element.receive(latest.bytes())).verifyResponse(latest, local, 100_000)
            assertEquals(80, executions)
        } finally { element.drain() }
    }

    @Test fun compactNonceCapacityRefusesUntilExpiryWithoutDiscardingReplayProtection() = runTest {
        val source = meshPeer(1)
        val local = meshPeer(2)
        var now = 100_000L
        var executions = 0
        val element = MeshExchangeElement(local, meshIdentity(2), s_[source], coroutineContext,
            execute = { _, _ -> executions++; byteArrayOf() },
            limits = MeshExchangeLimits(replayEntries = 1, replayTombstones = 2), nowMillis = { now })
        fun request(nonce: Int) = MeshEnvelope.request(source, local,
            MeshTask("tenant", "row.get", byteArrayOf(nonce.toByte())), meshIdentity(1),
            now, ByteArray(32) { nonce.toByte() })
        try {
            val first = request(1)
            element.receive(first.bytes())
            element.receive(request(2).bytes())
            assertFailsWith<MeshOverloaded> { element.receive(request(3).bytes()) }
            assertFailsWith<MeshReplayUnavailable> { element.receive(first.bytes()) }
            assertEquals(2, executions)
            now += MeshEnvelope.CLOCK_SKEW_MILLIS + 1
            element.receive(request(3).bytes())
            assertEquals(3, executions)
            assertFailsWith<IllegalArgumentException> { element.receive(first.bytes()) }
        } finally { element.drain() }
    }

}
