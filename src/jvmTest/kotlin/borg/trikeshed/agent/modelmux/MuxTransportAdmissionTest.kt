package borg.trikeshed.agent.modelmux

import borg.trikeshed.agent.keymux.*
import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.reactor.MuxReactorElement
import borg.trikeshed.userspace.nio.channels.spi.JvmChannelOperations
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import java.util.concurrent.atomic.AtomicInteger
import java.net.ServerSocket
import java.net.InetAddress
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.*

class MuxTransportAdmissionTest {
    @Test fun realHttpDateRetryAfterBlocksUntilTheReportedReset() = runBlocking {
        val owner = SupervisorJob(coroutineContext[Job])
        val network = HtxReactorElement(JvmChannelOperations(), parentJob = owner)
        withContext(Dispatchers.IO + owner) { network.open() }
        val htx = openHtxElement(routeService = network, parentJob = owner)
        val reactor = MuxReactorElement(parentJob = owner)
        val ledger = QuotaLegion()
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val baseUrl = "http://127.0.0.1:${server.localPort}/v1"
        val mux = ModelMux(KeyMux { }) {
            quota(ledger)
            model("local", caps = _s["chat"], auth = LlmAuth.NONE, baseUrl = baseUrl)
        }
        val resetMs = (System.currentTimeMillis() / 1000L + 180L) * 1000L
        val retry = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(resetMs))
        val serving = CoroutineScope(Dispatchers.IO + owner).launch {
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader()
                while (!input.readLine().isNullOrEmpty()) { }
                socket.getOutputStream().write("HTTP/1.1 429 Too Many Requests\r\nRetry-After: $retry\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".encodeToByteArray())
            }
        }
        try {
            val first = withContext(reactor + htx) { mux.chat("local", s_["user" j "first"], timeoutMs = 5_000) }
            assertTrue(first.isFailure)
            val key = "endpoint:$baseUrl"
            assertFalse(ledger.isAvailable(key, resetMs - 1))
            assertTrue(ledger.isAvailable(key, resetMs))
            val second = withContext(reactor + htx) { mux.chat("local", s_["user" j "second"], timeoutMs = 1_000) }
            assertContains(second.exceptionOrNull()?.message.orEmpty(), "quota exhausted")
            serving.join()
        } finally {
            server.close()
            reactor.close()
            htx.close()
            network.drain()
            owner.complete()
            owner.join()
        }
    }

    private class Transport(private val firstStatus: Int = 200) : HtxRouteService {
        val starts = Channel<Int>(8)
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        private val active = AtomicInteger()
        val maximum = AtomicInteger()

        override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
            val call = calls.incrementAndGet()
            val current = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, current) }
            try {
                starts.send(call)
                if (call == 1) withContext(NonCancellable) { release.await() }
                val status = if (call == 1) firstStatus else 200
                val body = if (request.target.requestPath.endsWith("/embeddings"))
                    """{"data":[{"embedding":[0.1,0.2]}]}"""
                else """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":2,"completion_tokens":1}}"""
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request,
                    response = HtxResponse(status = status, body = ByteSeries(body.encodeToByteArray()))))
            } finally { active.decrementAndGet() }
        }
    }

    @Test fun keyedCallsAlsoRespectTheirSharedEndpointPool() = runBlocking {
        val keys = KeyMux {
            bind("llm.first.key", TestKeySource(value = "one"))
            bind("llm.second.key", TestKeySource(value = "two"))
        }
        val baseUrl = "https://shared.invalid/v1"
        val mux = ModelMux(keys) {
            model("first/model", caps = _s["chat"], provider = "first", baseUrl = baseUrl)
            model("second/model", caps = _s["chat"], provider = "second", baseUrl = baseUrl)
        }
        val transport = Transport()
        val htx = openHtxElement(routeService = transport)
        val reactor = MuxReactorElement(parentJob = coroutineContext[Job], pools = _m[
            "key:llm.first.key" j MuxPool("account", 1),
            "endpoint:$baseUrl" j MuxPool("endpoint", 1),
        ])
        try {
            val first = async { withContext(reactor + htx) { mux.chat("first/model", s_["user" j "first"]).getOrThrow() } }
            assertEquals(1, withTimeout(5_000) { transport.starts.receive() })
            val second = async(start = CoroutineStart.UNDISPATCHED) { withContext(reactor + htx) {
                mux.chat("second/model", s_["user" j "second"]).getOrThrow()
            } }
            assertNull(withTimeoutOrNull(150) { transport.starts.receive() })
            transport.release.complete(Unit)
            withTimeout(5_000) { first.await(); second.await() }
            assertEquals(2, transport.calls.get())
        } finally {
            transport.release.complete(Unit)
            reactor.close()
            htx.close()
        }
    }

    @Test fun differentKeysShareAnExplicitPhysicalPool() = runBlocking {
        val keys = KeyMux {
            bind("llm.first.key", TestKeySource(value = "one"))
            bind("llm.second.key", TestKeySource(value = "two"))
            bind("llm.third.key", TestKeySource(value = "three"))
        }
        val mux = ModelMux(keys) {
            model("first/model", caps = _s["chat"], provider = "first", baseUrl = "https://first.invalid/v1")
            model("second/model", caps = _s["embed"], provider = "second", baseUrl = "https://second.invalid/v1")
            model("third/model", caps = _s["chat"], provider = "third", baseUrl = "https://third.invalid/v1")
        }
        val transport = Transport()
        val htx = openHtxElement(routeService = transport)
        val reactor = MuxReactorElement(parentJob = coroutineContext[Job], pools = mapOf(
            "key:llm.first.key" to MuxPool("account", 1),
            "key:llm.second.key" to MuxPool("account", 1),
        ))
        try {
            val first = async { withContext(reactor + htx) { mux.chat("first/model", s_["user" j "first"]).getOrThrow() } }
            assertEquals(1, withTimeout(5_000) { transport.starts.receive() })
            val second = async { withContext(reactor + htx) { mux.embed("second/model", s_["second"]) } }
            val independent = async { withContext(reactor + htx) { mux.chat("third/model", s_["user" j "third"]).getOrThrow() } }
            assertEquals(2, withTimeout(5_000) { transport.starts.receive() })
            independent.await()
            assertNull(withTimeoutOrNull(150) { transport.starts.receive() })
            transport.release.complete(Unit)
            withTimeout(5_000) { first.await(); second.await() }
            assertEquals(3, transport.calls.get())
        } finally {
            transport.release.complete(Unit)
            reactor.close()
            htx.close()
        }
    }

    @Test fun mixedCallsAcrossMuxInstancesShareTheBindingSlot() = runBlocking {
        val source = TestKeySource(value = "secret")
        val keys = KeyMux { bind("llm.first.key", source) }
        fun mux() = ModelMux(keys) {
            model("first/model", caps = _s["chat", "embed"], provider = "first", baseUrl = "https://first.invalid/v1")
        }
        val one = mux(); val two = mux()
        val transport = Transport()
        val htx = openHtxElement(routeService = transport)
        val reactor = MuxReactorElement(parentJob = coroutineContext[Job])
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) { withContext(reactor + htx) {
                one.chat("first/model", s_["user" j "first"]).getOrThrow()
            } }
            assertEquals(1, withTimeout(5_000) { transport.starts.receive() })
            val second = async(start = CoroutineStart.UNDISPATCHED) { withContext(reactor + htx) {
                two.stream("first/model", s_["user" j "second"]).toList()
            } }
            val third = async(start = CoroutineStart.UNDISPATCHED) { withContext(reactor + htx) {
                two.embed("first/model", s_["third"])
            } }
            val expired = async(start = CoroutineStart.UNDISPATCHED) { withContext(reactor + htx) {
                two.chat("first/model", s_["user" j "deadline"], timeoutMs = 100)
            } }
            assertTrue(withTimeout(5_000) { expired.await() }.isFailure)
            assertNull(withTimeoutOrNull(150) { transport.starts.receive() })
            transport.release.complete(Unit)
            withTimeout(5_000) { first.await(); second.await(); third.await() }
            assertEquals(3, transport.calls.get())
            assertEquals(1, transport.maximum.get())
        } finally {
            transport.release.complete(Unit)
            reactor.close()
            htx.close()
        }
    }

    @Test fun queuedCallRechecksAProvider429BeforeDispatch() = runBlocking {
        val keys = KeyMux { bind("llm.first.key", TestKeySource(value = "secret")) }
        val ledger = QuotaLegion()
        fun mux() = ModelMux(keys) {
            quota(ledger)
            model("first/model", caps = _s["chat", "embed"], provider = "first", baseUrl = "https://first.invalid/v1")
        }
        val one = mux(); val two = mux()
        val transport = Transport(firstStatus = 429)
        val htx = openHtxElement(routeService = transport)
        val reactor = MuxReactorElement(parentJob = coroutineContext[Job])
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) { withContext(reactor + htx) {
                one.chat("first/model", s_["user" j "first"])
            } }
            assertEquals(1, withTimeout(5_000) { transport.starts.receive() })
            val second = async(start = CoroutineStart.UNDISPATCHED) { withContext(reactor + htx) {
                two.chat("first/model", s_["user" j "second"])
            } }
            transport.release.complete(Unit)
            assertTrue(withTimeout(5_000) { first.await() }.isFailure)
            assertContains(withTimeout(5_000) { second.await() }.exceptionOrNull()?.message.orEmpty(), "quota exhausted")
            assertEquals(1, transport.calls.get())
        } finally {
            transport.release.complete(Unit)
            reactor.close()
            htx.close()
        }
    }

    @Test fun keylessEndpointSharesA429AcrossTrailingSlashVariants() = runBlocking {
        val keys = KeyMux { }
        val ledger = QuotaLegion()
        fun mux(base: String) = ModelMux(keys) {
            quota(ledger)
            model("local", caps = _s["chat"], auth = LlmAuth.NONE, baseUrl = base)
        }
        val one = mux("https://local.invalid/v1/"); val two = mux("https://local.invalid/v1")
        val transport = Transport(firstStatus = 429)
        val htx = openHtxElement(routeService = transport)
        val reactor = MuxReactorElement(parentJob = coroutineContext[Job])
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) { withContext(reactor + htx) {
                one.chat("local", s_["user" j "first"])
            } }
            assertEquals(1, withTimeout(5_000) { transport.starts.receive() })
            val second = async(start = CoroutineStart.UNDISPATCHED) { withContext(reactor + htx) {
                two.chat("local", s_["user" j "second"])
            } }
            transport.release.complete(Unit)
            assertTrue(withTimeout(5_000) { first.await() }.isFailure)
            assertContains(withTimeout(5_000) { second.await() }.exceptionOrNull()?.message.orEmpty(), "quota exhausted")
            assertEquals(1, transport.calls.get())
        } finally {
            transport.release.complete(Unit)
            reactor.close()
            htx.close()
        }
    }

    @Test fun queuedCallRefusesAReplacedCredential() = runBlocking {
        val source = TestKeySource(value = "before")
        val keys = KeyMux { bind("llm.first.key", source) }
        val mux = ModelMux(keys) {
            model("first/model", caps = _s["chat"], provider = "first", baseUrl = "https://first.invalid/v1")
        }
        val transport = Transport()
        val htx = openHtxElement(routeService = transport)
        val reactor = MuxReactorElement(parentJob = coroutineContext[Job])
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) { withContext(reactor + htx) {
                mux.chat("first/model", s_["user" j "first"])
            } }
            assertEquals(1, withTimeout(5_000) { transport.starts.receive() })
            val second = async(start = CoroutineStart.UNDISPATCHED) { withContext(reactor + htx) {
                mux.chat("first/model", s_["user" j "second"])
            } }
            source.value = "after"
            transport.release.complete(Unit)
            assertTrue(withTimeout(5_000) { first.await() }.isSuccess)
            assertContains(withTimeout(5_000) { second.await() }.exceptionOrNull()?.message.orEmpty(), "credential or destination changed")
            assertEquals(1, transport.calls.get())
        } finally {
            transport.release.complete(Unit)
            reactor.close()
            htx.close()
        }
    }

    @Test fun cancellationReachesTransportAndKeepsTheSlotUntilCleanup() = runBlocking {
        val keys = KeyMux { bind("llm.first.key", TestKeySource(value = "secret")) }
        val mux = ModelMux(keys) {
            model("first/model", caps = _s["chat"], provider = "first", baseUrl = "https://first.invalid/v1")
        }
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val transport = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                if (calls.incrementAndGet() == 1) {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally {
                        cleaning.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                    }
                }
                val body = """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request,
                    response = HtxResponse(status = 200, body = ByteSeries(body.encodeToByteArray()))))
            }
        }
        val htx = openHtxElement(routeService = transport)
        val reactor = MuxReactorElement(parentJob = coroutineContext[Job])
        try {
            val first = async { withTimeout(150) { withContext(reactor + htx) {
                mux.chat("first/model", s_["user" j "first"])
            } } }
            withTimeout(5_000) { entered.await(); cleaning.await() }
            val second = async(start = CoroutineStart.UNDISPATCHED) { withContext(reactor + htx) {
                mux.chat("first/model", s_["user" j "second"])
            } }
            assertEquals(1, calls.get())
            release.complete(Unit)
            assertFailsWith<TimeoutCancellationException> { first.await() }
            assertTrue(withTimeout(5_000) { second.await() }.isSuccess)
            assertEquals(2, calls.get())
        } finally {
            release.complete(Unit)
            reactor.close()
            htx.close()
        }
    }

    @Test fun canceledRealHtxExchangeStillMetersIts429BeforeTheNextCall() = runBlocking {
        val owner = SupervisorJob(coroutineContext[Job])
        val network = HtxReactorElement(JvmChannelOperations(), parentJob = owner)
        withContext(Dispatchers.IO + owner) { network.open() }
        val htx = openHtxElement(routeService = network, parentJob = owner)
        val reactor = MuxReactorElement(parentJob = owner)
        val ledger = QuotaLegion()
        val keys = KeyMux { bind("llm.first.key", TestKeySource(value = "secret")) }
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val mux = ModelMux(keys) {
            quota(ledger)
            model("first/model", caps = _s["chat"], provider = "first", baseUrl = "http://127.0.0.1:${server.localPort}/v1")
        }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val serving = CoroutineScope(Dispatchers.IO + owner).launch {
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader()
                while (!input.readLine().isNullOrEmpty()) { }
                entered.complete(Unit)
                release.await()
                socket.getOutputStream().write("HTTP/1.1 429 Too Many Requests\r\nRetry-After: 60\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".encodeToByteArray())
            }
        }
        try {
            val first = async { withContext(reactor + htx) { mux.chat("first/model", s_["user" j "first"], timeoutMs = 5_000) } }
            withTimeout(5_000) { entered.await() }
            first.cancel()
            val second = async(start = CoroutineStart.UNDISPATCHED) { withContext(reactor + htx) {
                mux.chat("first/model", s_["user" j "second"], timeoutMs = 1_000)
            } }
            release.complete(Unit)
            assertFailsWith<CancellationException> { withTimeout(5_000) { first.await() } }
            assertContains(withTimeout(5_000) { second.await() }.exceptionOrNull()?.message.orEmpty(), "quota exhausted")
            serving.join()
        } finally {
            release.complete(Unit)
            server.close()
            reactor.close()
            htx.close()
            network.drain()
            owner.complete()
            owner.join()
        }
    }
}
