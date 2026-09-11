package borg.trikeshed.ipns

import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view
import borg.trikeshed.userspace.UringTrace
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.file.StandardOpenOption.*
import kotlinx.coroutines.*
import borg.trikeshed.parse.json.JsonSupport
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.system.exitProcess

/** Standalone native IPNS client. Network traffic uses the commonMain uring/libp2p stack. */
fun main(args: Array<String>) {
    var code = 0
    val stopping = java.util.concurrent.atomic.AtomicBoolean(false)
    val finished = java.util.concurrent.CountDownLatch(1)
    val shutdown = Thread({ stopping.set(true); finished.await() }, "ipns-drain")
    if (args.firstOrNull() == "serve") Runtime.getRuntime().addShutdownHook(shutdown)
    try {
    runBlocking {
        val options = mutableMapOf<String, String>()
        val command = args.firstOrNull() ?: error("Usage: ipns <init|publish|resolve|republish|serve> [--state path] [--value /ipfs/CID] [--name CID] [--bootstrap numeric-multiaddr,...] [--quorum 16] [--replicas 20] [--allow-private true] [--trace path]")
        require((args.size - 1) % 2 == 0) { "Options require --name value pairs" }
        for (i in 1 until args.size step 2) { require(args[i].startsWith("--")); options[args[i].drop(2)] = args[i + 1] }
        require(options.keys.all { it in setOf("state", "value", "name", "bootstrap", "quorum", "replicas", "allow-private", "trace", "timeout", "rpc-timeout", "lifetime", "republish-interval", "retry-interval", "ttl", "duration") }) { "Unknown option" }
        val crypto = JvmIpnsCrypto()
        val trace = options["trace"]?.let { IpnsIoTrace() }
        var journal: IpnsJournal? = null
        var publisher: IpnsPublisher? = null
        var dialer: UringLibp2pDialer? = null
        try {
            val identity = if (command == "resolve") crypto.generate() else {
                journal = IpnsJournal.open(options["state"] ?: "ipns.journal", crypto)
                journal!!.identity
            }
            if (command == "init") {
                emit(jsonObject { put("name", identity.name.toString()); put("peerId", identity.name.peerId()) })
            } else {
                val replicas = options["replicas"]?.toInt() ?: 20
                val quorum = options["quorum"]?.toInt() ?: minOf(16, replicas)
                val privateAddresses = options["allow-private"]?.toBooleanStrict() ?: false
                val bootstrap = IpnsDhtAddresses.bootstrap((options["bootstrap"] ?: IPNS_BOOTSTRAP).split(',').toSeries(), privateAddresses)
                val timeout = options["rpc-timeout"]?.toLong() ?: 15000L
                val tls = JvmLibp2pTls(identity, crypto)
                dialer = UringLibp2pDialer(tls::backend, parentJob = coroutineContext[Job], timeoutMillis = timeout,
                    maxConnections = 10, context = trace ?: kotlin.coroutines.EmptyCoroutineContext)
                val dht = IpnsDht(dialer!!, crypto, bootstrap, IpnsDhtLimits(replication = replicas,
                    minValidResponses = quorum, allowPrivateAddresses = privateAddresses, rpcTimeoutMillis = timeout,
                    operationTimeoutMillis = options["timeout"]?.toLong() ?: 120000L))
                if (command == "resolve") {
                    val name = IpnsName.parse(requireNotNull(options["name"]) { "--name required" })
                    val report = dht.resolveReport(name, Clock.System.now())
                    emit(jsonObject {
                        put("operation", "resolve"); put("localPeerId", identity.name.peerId()); put("name", name.toString()); put("quorumReached", report.quorumReached)
                        put("validResponses", report.valid.size); put("quorumRequired", report.quorumRequired)
                        report.selected?.let { put("value", it.value); put("sequence", it.sequence.toString()); put("validUntil", it.validUntil.toString()); put("wireBase64", java.util.Base64.getEncoder().encodeToString(it.bytes)) }
                        put("peers", (report.valid.view.map { jsonObject { putAll(peerJson(it.peer)); put("sequence", it.record.sequence.toString()); put("validUntil", it.record.validUntil.toString()) } }))
                        put("lookupExhausted", report.lookup.exhausted); put("queryLimitReached", report.lookup.queryLimitReached); put("candidateLimitReached", report.lookup.candidateLimitReached)
                        put("failures", (report.failures.view.map { failureJson(it) }))
                    })
                    if (!report.quorumReached && !report.absent) code = 2
                } else {
                    require(command in setOf("publish", "republish", "serve")) { "Unknown IPNS command" }
                    val policy = IpnsPublicationPolicy(
                        lifetime = (options["lifetime"]?.toLong() ?: 172800L).seconds,
                        ttl = (options["ttl"]?.toLong() ?: 300L).seconds,
                        republishInterval = (options["republish-interval"]?.toLong() ?: 14400L).seconds,
                        retryInterval = (options["retry-interval"]?.toLong() ?: 300L).seconds)
                    publisher = IpnsPublisher(journal!!, dht, crypto, coroutineContext, policy)
                    publisher!!.open()
                    val report = try {
                        if (command == "publish" || options["value"] != null) publisher!!.publish(requireNotNull(options["value"])) else publisher!!.republish()
                    } catch (failure: Exception) {
                        if (command != "serve") throw failure
                        if (failure is CancellationException) currentCoroutineContext().ensureActive()
                        emit(jsonObject { put("operation", "republish"); put("error", failure.toString()); put("retryScheduled", true) })
                        null
                    }
                    if (report != null) emit(publicationJson(publisher!!, report))
                    if (report?.complete == false && command != "serve") code = 2
                    if (command == "serve") {
                        var emitted = report
                        var emittedFailure: Throwable? = null
                        val duration = options["duration"]?.toLong()?.also { require(it > 0) }
                        val started = System.nanoTime()
                        while (isActive && !stopping.get() &&
                            (duration == null || (System.nanoTime() - started) / 1_000_000_000 < duration)) {
                            delay(1000)
                            if (publisher!!.lastReport !== emitted) publisher!!.lastReport?.let {
                                emit(publicationJson(publisher!!, it)); emitted = it
                            }
                            if (publisher!!.lastFailure !== emittedFailure) {
                                emittedFailure = publisher!!.lastFailure
                                emittedFailure?.let { emit(jsonObject { put("operation", "republish"); put("error", it.toString()) }) }
                            }
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            emit(jsonObject { put("operation", command); put("error", failure.stackTraceToString()) })
            code = 1
        } finally {
            withContext(NonCancellable) {
                try { if (publisher != null) publisher!!.close() else journal?.close() }
                finally {
                    try { dialer?.close() }
                    finally { if (trace != null) trace.write(requireNotNull(options["trace"])) }
                }
            }
        }
    }
    } finally {
        finished.countDown()
        if (args.firstOrNull() == "serve") runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
    }
    if (code != 0) exitProcess(code)
}

private fun peerJson(peer: Libp2pPeer) = jsonObject {
    put("id", IpnsEncoding.base58(peer.id))
    put("multiaddrs", peer.addresses.view.map { wire ->
        val tcp = Libp2pTcpAddress.decode(wire, peer.id)
        "/ip${if (tcp.domain == 2) 4 else 6}/${tcp.host}/tcp/${tcp.port}/p2p/${IpnsEncoding.base58(peer.id)}"
    })
    put("addresses", (peer.addresses.view.map { (java.util.Base64.getEncoder().encodeToString(it)) }))
}
private fun failureJson(failure: IpnsDhtFailure) = jsonObject {
    put("peer", peerJson(failure.peer)); put("stage", failure.stage.name); put("reason", failure.reason)
}
private fun publicationJson(publisher: IpnsPublisher, report: IpnsPublishReport) = jsonObject {
    put("operation", "publish"); put("localPeerId", publisher.identity.name.peerId()); put("name", report.record.name.toString()); put("complete", report.complete)
    put("primaryAcknowledgements", report.primaryAcknowledgements.size); put("replacementAcknowledgements", report.replacementAcknowledgements.size); put("putAttempts", report.putAttempts)
    put("acknowledgements", report.acknowledgements.size); put("replicaTarget", report.replicaTarget)
    put("lookupExhausted", report.lookup.exhausted); put("queryLimitReached", report.lookup.queryLimitReached); put("candidateLimitReached", report.lookup.candidateLimitReached)
    report.record.let { put("value", it.value); put("sequence", it.sequence.toString()); put("validUntil", it.validUntil.toString()); put("wireBase64", java.util.Base64.getEncoder().encodeToString(it.bytes)) }
    put("peers", (report.acknowledgements.view.map(::peerJson)))
    put("failures", (report.failures.view.map(::failureJson)))
}

/** Bounded paired trace; payload bytes and signing material are deliberately absent. */
private class IpnsIoTrace : UringTrace {
    private val events = mutableListOf<Map<String, Any?>>()
    private val admitted = mutableSetOf<Pair<Long, Long>>()
    private var omitted = 0L
    @Synchronized override fun channel(id: Long, availability: String, nativeCapabilities: Long) {
        if (events.size < 200000) events += jsonObject { put("event", "channel"); put("channel", id); put("availability", availability); put("nativeCapabilities", nativeCapabilities); put("socketBackend", "JVM descriptor emulation behind commonMain uring") }
    }
    @Synchronized override fun submit(channel: Long, submission: UringSubmission) {
        if (events.size + admitted.size + 2 > 200000) { omitted += 2; return }
        admitted += channel to submission.userData
        events += event("submit", channel, submission, null)
    }
    @Synchronized override fun complete(channel: Long, submission: UringSubmission, result: Int) {
        if (admitted.remove(channel to submission.userData)) events += event("complete", channel, submission, result)
    }
    @Synchronized override fun failed(channel: Long, submission: UringSubmission, failure: String) {
        if (admitted.remove(channel to submission.userData)) events += jsonObject {
            put("event", "backend_failure"); put("channel", channel); put("request", submission.userData); put("failure", failure)
        }
    }
    private fun event(kind: String, channel: Long, sub: UringSubmission, result: Int?) = jsonObject {
        put("event", kind); put("channel", channel); put("request", sub.userData); put("opcode", sub.opcode.name)
        put("fd", sub.fd); put("bytes", sub.len); put("offset", sub.offset); put("timeNanos", System.nanoTime())
        if (result != null) put("result", result)
    }
    fun write(path: String) {
        val file = FileChannel.open(path, WRITE, CREATE, TRUNCATE_EXISTING)
        try {
            for (event in events + jsonObject { put("event", "summary"); put("omitted", omitted); put("unpaired", admitted.size) }) {
                val buffer = ByteBuffer((JsonSupport.stringify(event) + "\n").encodeToByteArray())
                while (buffer.hasRemaining()) check(file.write(buffer) > 0)
            }
            file.force(true)
        } finally { file.close() }
    }
}

private fun jsonObject(block: MutableMap<String, Any?>.() -> Unit): Map<String, Any?> = linkedMapOf<String, Any?>().apply(block)
private fun emit(value: Map<String, Any?>) = println(JsonSupport.stringify(value))

// Official Amino bootstrappers, numeric DNS snapshot 2026-09-11; --bootstrap replaces this set.
private const val IPNS_BOOTSTRAP = "/ip4/54.38.47.166/tcp/4001/p2p/QmbLHAnMoJPWSCR5Zhtx6BHJX9KiKNN6tpvbUcqanj75Nb," +
    "/ip4/15.235.144.210/tcp/4001/p2p/QmcZf59bWwK5XFi76CZX8cbJ4BhTzzA3gU1ZjYZcYW3dwt," +
    "/ip4/51.81.93.51/tcp/4001/p2p/QmQCU2EcMqAqQPR2i9bChDtGNJchTbq5TbXJJ16u19uLTa," +
    "/ip4/147.135.44.132/tcp/4001/p2p/QmNnooDu7bfjPFoTZYxMNLWUQJyrVwtbZg5gBMjTezGAJN"
