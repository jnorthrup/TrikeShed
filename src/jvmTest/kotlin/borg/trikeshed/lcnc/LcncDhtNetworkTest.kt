package borg.trikeshed.lcnc

import borg.trikeshed.agent.*
import borg.trikeshed.couch.*
import borg.trikeshed.dag.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.htx.*
import borg.trikeshed.ipns.*
import borg.trikeshed.job.*
import borg.trikeshed.kanban.module.KanbanModule
import borg.trikeshed.lcnc.ccek.*
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.modelmux.BrainClient
import borg.trikeshed.module.*
import borg.trikeshed.parse.*
import borg.trikeshed.reactor.JvmTlsCodecBackend
import borg.trikeshed.userspace.nio.channels.spi.JvmChannelOperations
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.userspace.reactor.MuxReactorElement
import borg.trikeshed.util.oroboros.*
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import keymux.*
import kotlinx.coroutines.*
import modelmux.ModelMux
import kotlin.test.*

/** Independent Kubo peers authenticate and store the real Amino DHT traffic, never an in-memory dialer. */
class LcncDhtNetworkTest {
    private class Kubo(val directory: File) : AutoCloseable {
        private val executable = System.getenv("TRIKESHED_IPFS_BINARY")
            ?: File("/opt/homebrew/bin/ipfs").takeIf { it.canExecute() }?.path ?: "ipfs"
        private var daemon: Process? = null
        private val log = File(directory, "daemon.log")
        lateinit var address: String
            private set

        private fun command(vararg arguments: String): String {
            val output = File(directory, "command.log")
            val process = ProcessBuilder(executable, *arguments).apply {
                environment()["IPFS_PATH"] = File(directory, "repo").path
                redirectErrorStream(true)
                redirectOutput(output)
            }.start()
            check(process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); "Kubo command timed out" }
            check(process.exitValue() == 0) { output.readText() }
            return output.readText().trim()
        }

        suspend fun open() {
            directory.mkdirs()
            command("init", "--profile=test,autoconf-off")
            val port = freePort()
            command("config", "--json", "Addresses.Swarm", "[\"/ip4/127.0.0.1/tcp/$port\"]")
            command("config", "Addresses.API", "/ip4/127.0.0.1/tcp/${freePort()}")
            command("config", "Addresses.Gateway", "")
            command("bootstrap", "rm", "--all")
            address = "/ip4/127.0.0.1/tcp/$port/p2p/" + command("config", "Identity.PeerID")
            daemon = ProcessBuilder(executable, "daemon", "--routing=dhtserver").apply {
                environment()["IPFS_PATH"] = File(directory, "repo").path
                redirectErrorStream(true)
                redirectOutput(log)
            }.start()
            withTimeout(20_000) {
                while (!log.readText().contains("Daemon is ready")) {
                    check(daemon!!.isAlive) { log.readText() }
                    delay(50)
                }
            }
        }

        override fun close() {
            daemon?.let { process ->
                process.destroy()
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly().waitFor()
            }
        }
    }

    private class Provider : AutoCloseable {
        val requests = CopyOnWriteArrayList<Map<String, Any?>>()
        @Volatile var runResponse = 503 j """{"error":"receipt_commit_failed","runId":"unretained"}"""
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base: String get() = "http://127.0.0.1:${server.address.port}/v1"
        init {
            // Fault injection for the remote wire boundary, separate from the actual model execution below.
            server.createContext("/api/lcnc/run") { exchange ->
                exchange.use {
                    it.requestBody.readBytes()
                    val reply = runResponse
                    val bytes = reply.b.encodeToByteArray()
                    it.sendResponseHeaders(reply.a, bytes.size.toLong())
                    it.responseBody.write(bytes)
                }
            }
            server.createContext("/v1/chat/completions") { exchange ->
                exchange.use {
                    requests += mapOf("authorization" to it.requestHeaders.getFirst("Authorization"),
                        "body" to reifyMap(it.requestBody.readBytes()))
                    val bytes = """{"choices":[{"message":{"content":"Evidence supports the requested ruling."}}],"usage":{"prompt_tokens":7,"completion_tokens":6}}""".encodeToByteArray()
                    it.responseHeaders.set("Content-Type", "application/json")
                    it.sendResponseHeaders(200, bytes.size.toLong())
                    it.responseBody.write(bytes)
                }
            }
            server.start()
        }
        override fun close() = server.stop(0)
    }

    private class Host(
        val context: ModuleContext,
        val supervisor: ModuleSupervisor,
        val node: IpnsNode,
        val mux: ModelMux,
        val reactor: MuxReactorElement,
        val server: Job,
        val origin: String,
    ) {
        fun store(program: LcncProgram) = context.blackboard.put(LcncBlackboard.programKey(program.name),
            LcncBlackboard.programEntry(program.name, program, LcncContracts.all().associateBy { it.type }), "network-test")
        suspend fun close() = cleanup(
            { server.cancelAndJoin() }, { node.close() }, { supervisor.detach("kanban") },
            { reactor.close() }, { context.scope.coroutineContext.job.cancelAndJoin() },
        )
    }

    private suspend fun host(directory: File, seeds: String, htx: HtxElement, provider: Provider,
        program: LcncProgram? = null): Host {
        directory.mkdirs()
        val owner = SupervisorJob()
        val scopeContext = Dispatchers.Default + owner
        val node = jvmIpnsNode(File(directory, "network.ipns").path, scopeContext, seeds,
            IpnsDhtLimits(replication = 2, parallelism = 2, maxQueries = 8, maxCandidates = 8,
                minValidResponses = 2, rpcTimeoutMillis = 8_000, operationTimeoutMillis = 30_000,
                allowPrivateAddresses = true, readRepair = false))
        var ownedReactor: MuxReactorElement? = null
        var ownedSupervisor: ModuleSupervisor? = null
        var ownedServer: Job? = null
        try {
            node.open()
            val scope = CoroutineScope(scopeContext + node)
            val keys = KeyMux { bind("llm.default.key", FixedKeySource("loopback-key")) }
            val mux = ModelMux(keys) { model("local-model", setOf("chat", "conflict-resolve"), provider.base) }
            val brain = BrainClient(apiKey = "loopback-key", base = provider.base, model = "local-model", keyMux = keys, modelMux = mux)
            val reactor = MuxReactorElement(keyMux = keys, modelMuxProvider = { mux }, parentJob = owner)
                .also { ownedReactor = it; it.open() }
            val cas = FileCasStore(JvmFileOperations(), File(directory, "cas").path)
            val store = CouchStoreFactory.casBacked(cas)
            val blackboard = ConfixBlackboard.empty()
            program?.let { blackboard.put(LcncBlackboard.programKey(it.name),
                LcncBlackboard.programEntry(it.name, it, LcncContracts.all().associateBy { it.type }), "network-test") }
            val context = ModuleContext(
                couch = Couch("network", store, cas), rete = ReteNetwork(), productions = ReteProductionRegistry(),
                beliefBag = null, turnReview = null, blackboard = blackboard, casStore = cas,
                attachments = CouchAttachmentGateway(store, cas), routes = ModuleRouteRegistry(), scope = scope,
                clock = System::currentTimeMillis, stateDir = directory, muxContext = htx + reactor,
                ccekBinding = ccekReactorBinding(scope.coroutineContext + htx + reactor),
                programLoader = { name -> LcncBlackboard.programOf(blackboard.get(LcncBlackboard.programKey(name))) },
            )
            context.lcncRunners.putAll(PureNodes.registry(System::currentTimeMillis))
            context.lcncRunners.putAll(TribunalNodes.registry(modelMuxDialog(brain, htx + reactor)))
            val supervisor = ModuleSupervisor(context).also { ownedSupervisor = it }
            supervisor.attach(KanbanModule())
            val port = freePort()
            val server = scope.launch { JvmKanbanServer(moduleRoutes = context.routes, stateDir = directory).run(port, null) }
                .also { ownedServer = it }
            val origin = "http://127.0.0.1:$port"
            withTimeout(15_000) {
                while (true) {
                    if (runCatching { htx.request(parseHtxRequest("$origin/api/lcnc/network/status").copy(timeoutMs = 500)).status == 200 }.getOrDefault(false)) break
                    check(server.isActive) { "LCNC server terminated before readiness" }
                    delay(50)
                }
            }
            return Host(context, supervisor, node, mux, reactor, server, origin)
        } catch (failure: Throwable) {
            try { cleanup(
                { ownedServer?.cancelAndJoin() }, { node.close() }, { ownedSupervisor?.detach("kanban") },
                { ownedReactor?.close() }, { owner.cancelAndJoin() },
            ) } catch (closing: Throwable) { failure.addSuppressed(closing) }
            throw failure
        }
    }

    private fun council(): LcncProgram {
        val program = LcncProgram("network-council", s_[
            LcncNode("brief", "scope.in", mapOf("name" to "brief", "kind" to "text")),
            LcncNode("panel", "scope", children = s_[
                LcncNode("record", "scope.in", mapOf("name" to "record", "kind" to "text")),
                LcncNode("seat", "mux.chat", mapOf("system" to "Decide from the supplied evidence.", "maxTokens" to "73", "temperature" to "0.4")),
                LcncNode("verdict", "scope.out", mapOf("name" to "verdict", "kind" to "text")),
            ]),
            LcncNode("ruling", "scope.out", mapOf("name" to "ruling", "kind" to "text")),
        ], s_[
            LcncWire("brief", "value", "panel", "record"),
            LcncWire("record", "value", "seat", "prompt?"),
            LcncWire("seat", "content", "verdict", "value"),
            LcncWire("panel", "verdict", "ruling", "value"),
        ], controls = LcncConfixControls(subnet = "local.council"))
        return LcncModelSelection.select(program, "seat", s_["override" j "local-model"], s_["override" j "local-model"])
    }

    private suspend fun post(htx: HtxElement, host: Host, path: String, value: Map<String, Any?>): Join<Int, Map<String, Any?>> {
        val response = htx.request(parseHtxRequest(host.origin + path, method = HtxMethod.POST,
            body = ByteSeries(jsonOf(value).encodeToByteArray())).copy(timeoutMs = 120_000)
            .withHeader("Content-Type", "application/json"))
        return response.status j reifyMap(response.body.toArray())
    }

    @Test fun signedTwoPeerDiscoveryHostsSelectedNestedModelAndRetainsPinnedSubnetReceipt() = runBlocking {
        val directory = Files.createTempDirectory("lcnc-dht-network-").toFile()
        val first = Kubo(File(directory, "peer-a"))
        val second = Kubo(File(directory, "peer-b"))
        val provider = Provider()
        val owner = SupervisorJob()
        val transport = HtxReactorElement(JvmChannelOperations(), JvmTlsCodecBackend(), parentJob = owner)
        val htx = HtxElement(routeService = transport, parentJob = owner)
        var publisher: Host? = null
        var consumer: Host? = null
        try {
            transport.open()
            htx.open()
            first.open()
            second.open()
            val seeds = first.address + "," + second.address
            val program = council()
            assertTrue(LcncTypeCheck.check(program).isEmpty(), LcncTypeCheck.check(program).toString())
            val source = host(File(directory, "host"), seeds, htx, provider, program).also { publisher = it }
            val client = host(File(directory, "client"), seeds, htx, provider).also { consumer = it }
            val published = post(htx, source, "/api/lcnc/network/publish", mapOf("origin" to source.origin))
            assertEquals(200, published.a, published.b.toString())
            assertEquals(2, (published.b["acknowledgements"] as Number).toInt())
            assertEquals(2, (published.b["replicaTarget"] as Number).toInt())
            val name = published.b.getValue("name") as String
            val discovered = post(htx, client, "/api/lcnc/network/resolve", mapOf("name" to name))
            assertEquals(200, discovered.a, discovered.b.toString())
            assertEquals(published.b["hostCid"], discovered.b["hostCid"])
            val row = assertIs<Map<*, *>>(assertIs<List<*>>(discovered.b["programs"]).single())
            assertEquals(program.name, row["program"])
            assertEquals("local.council", row["scope"])
            assertEquals(LcncBlackboard.cidOf(program), row["programCid"])
            assertTrue(provider.requests.isEmpty(), "Publication and discovery never execute a model")
            val selected = mapOf("name" to name, "hostCid" to discovered.b["hostCid"],
                "program" to program.name, "programCid" to row["programCid"], "subnet" to "local.council.review",
                "inputs" to mapOf("brief" to "The retained evidence establishes the requested facts."))
            val completed = post(htx, client, "/api/lcnc/network/run", selected)
            assertEquals(200, completed.a, completed.b.toString())
            val receipt = assertIs<Map<*, *>>(completed.b["receipt"])
            assertEquals("completed", receipt["status"])
            assertEquals(row["programCid"], receipt["programCid"])
            assertEquals("local.council.review", receipt["subnet"])
            assertEquals("local.council", receipt["claimedAtSubnet"])
            assertTrue((receipt["claimId"] as Number).toLong() > 0)
            assertEquals("Evidence supports the requested ruling.", assertIs<Map<*, *>>(receipt["returns"])["ruling"])
            val bytes = assertNotNull(source.context.casStore.get(ContentId(receipt["receiptCid"] as String)))
            assertEquals(receipt["receiptCid"], ContentId.of(bytes).value)
            val retained = assertIs<Map<*, *>>(reifyMap(bytes)["lcncRun"])
            for (key in s_["runId", "programCid", "subnet", "claimedAtSubnet", "claimId", "returns"].view)
                assertEquals(receipt[key], retained[key], "Durable receipt retains $key")
            val stored = assertNotNull(source.context.casStore.get(ContentId(row["programCid"] as String)))
            assertTrue(stored.decodeToString().contains("local-model"), "Selected endpoint/model is part of the retained construction")
            val observed = provider.requests.single()
            assertEquals("Bearer loopback-key", observed["authorization"])
            val body = assertIs<Map<*, *>>(observed["body"])
            assertEquals("local-model", body["model"])
            assertEquals(73, (body["max_tokens"] as Number).toInt())
            assertEquals(0.4, (body["temperature"] as Number).toDouble())
            assertEquals(reify("""[{"role":"system","content":"Decide from the supplied evidence."},{"role":"user","content":"The retained evidence establishes the requested facts."}]"""), body["messages"])
            assertEquals("tribunal-seat", source.mux.lastReceipt?.assessmentId)
            assertEquals(200, source.mux.lastReceipt?.httpStatus)
            assertEquals(400, post(htx, client, "/api/lcnc/network/run", selected - "subnet").a)
            assertEquals(400, post(htx, client, "/api/lcnc/network/run", selected + ("subnet" to "local.other")).a)
            val changed = program.copy(controls = program.controls.copy(humanOversight = false))
            source.store(changed)
            val staleProgram = post(htx, client, "/api/lcnc/network/run", selected)
            assertEquals(409, staleProgram.a, staleProgram.b.toString())
            assertEquals("remote_refused", staleProgram.b["error"])
            assertNull(staleProgram.b["receipt"], "Refused admission has no execution receipt")
            val changedHost = post(htx, source, "/api/lcnc/network/publish", mapOf("origin" to source.origin))
            assertEquals(200, changedHost.a, changedHost.b.toString())
            assertNotEquals(discovered.b["hostCid"], changedHost.b["hostCid"])
            val staleHost = post(htx, client, "/api/lcnc/network/run", selected)
            assertEquals(409, staleHost.a, staleHost.b.toString())
            assertEquals("host_changed", staleHost.b["error"])
            assertEquals(1, provider.requests.size, "Rejected pins and subnets never execute the model")
            val faultDirectory = LcncDhtHost(provider.base.removeSuffix("/v1"), s_[
                LcncDhtProgram(changed.name, ContentId(LcncBlackboard.cidOf(changed)), "local.council"),
            ])
            assertTrue(source.node.request { LcncDht.publish(faultDirectory).b.complete })
            val faultSelection = selected + mapOf("hostCid" to faultDirectory.cid.value,
                "programCid" to LcncBlackboard.cidOf(changed))
            val unknown = post(htx, client, "/api/lcnc/network/run", faultSelection)
            assertEquals(503, unknown.a)
            assertEquals("remote_outcome_unknown", unknown.b["error"], "Missing final receipt does not prove refusal")
            assertNull(unknown.b["receipt"])
            provider.runResponse = 502 j "malformed upstream response"
            val malformed = post(htx, client, "/api/lcnc/network/run", faultSelection)
            assertEquals(502, malformed.a)
            assertEquals("remote_response_invalid", malformed.b["error"], "Upstream decoding is not a caller request error")
            source.close()
            publisher = null
            val reopened = FileCasStore(JvmFileOperations(), File(directory, "host/cas").path)
            assertContentEquals(bytes, reopened.get(ContentId(receipt["receiptCid"] as String)),
                "The receipt survives the serving runtime and CAS instance")
            println("LCNC DHT proof: two authenticated Kademlia peers, ${source.origin}, ${client.origin}, $name, receipt=${receipt["receiptCid"]}")
        } finally { cleanup(
            { consumer?.close() }, { publisher?.close() }, { htx.close() }, { transport.close() },
            { owner.cancelAndJoin() }, { provider.close() }, { second.close() }, { first.close() },
            { directory.deleteRecursively() },
        ) }
    }

    companion object {
        private fun freePort(): Int = ServerSocket(0).use { it.localPort }
        private suspend fun cleanup(vararg actions: suspend () -> Unit): Unit = withContext(NonCancellable) {
            var failure: Throwable? = null
            for (action in actions) try { action() } catch (caught: Throwable) {
                if (failure == null) failure = caught else failure.addSuppressed(caught)
            }
            failure?.let { throw it }
        }
    }
}
