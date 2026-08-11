package borg.trikeshed.utils.keymuxd

import borg.trikeshed.htx.HtxElement
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import borg.trikeshed.userspace.reactor.MuxReactorElement
import borg.trikeshed.userspace.reactor.MuxReactorConfig
import borg.trikeshed.userspace.reactor.MuxCredentialRecord
import borg.trikeshed.htx.openHtxElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.coroutines.channels.BufferOverflow
import kotlin.coroutines.CoroutineContext
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Path

/**
 * KeyMux Daemon — standalone CCEK reactor for key+quota+modelmux state.
 * 
 * Architecture (mirrors Rust litebike):
 * - HTX reactor for KeyMux/ModelMux API calls (userspace.nio, no JDK networking)
 * - MuxReactorElement: key pool, lease management, quota, ModelApiCache
 * - KeyMux: lazy env/persist/api/reactor sources with FirstWinsResolver
 * - UNIX domain socket health endpoint at ~/.local/forge/keymux.sock
 * 
 * Runs with:
 *   bin/keymux-daemon [--port N] [--forge-home PATH] [--health-sock PATH]
 * 
 * Environment:
 *   JULES_API_KEY (optional, for Jules integration)
 *   KEYMUX_PERSIST_ROOT (optional, default ~/.local/forge/keymux)
 */
object KeyMuxDaemon {

    @JvmStatic
    fun main(args: Array<String>) {
        val config = parseConfig(args)
        
        runBlocking {
            mainImpl(config)
        }
    }

    data class DaemonConfig(
        val port: Int = 8888,
        val forgeHome: File = File(System.getProperty("user.home"), ".local/forge"),
        val healthSock: File = File(System.getProperty("user.home"), ".local/forge/keymux.sock"),
    )

    private fun parseConfig(args: Array<String>): DaemonConfig {
        var port = 8888
        var forgeHome = File(System.getProperty("user.home"), ".local/forge")
        var healthSock = File(System.getProperty("user.home"), ".local/forge/keymux.sock")
        
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--port" -> {
                    port = args[i + 1].toIntOrNull() ?: 8888
                    i += 2
                }
                "--forge-home" -> {
                    forgeHome = File(args[i + 1])
                    i += 2
                }
                "--health-sock" -> {
                    healthSock = File(args[i + 1])
                    i += 2
                }
                else -> {
                    System.err.println("Unknown argument: ${args[i]}")
                    i++
                }
            }
        }
        
        return DaemonConfig(port, forgeHome, healthSock)
    }

    private suspend fun CoroutineScope.mainImpl(config: DaemonConfig) {
        val keyMux = KeyMux {
            env()
            persist(config.forgeHome.resolve("keymux").absolutePath)
            reactor()
        }
        
        // Probe early so a missing key aborts before opening the HTX reactor.
        val apiKeyPresent = withContext(Dispatchers.IO) { keyMux.get("JULES_API_KEY") }
        if (apiKeyPresent.isNullOrBlank()) {
            System.err.println("[KEYMUX] JULES_API_KEY not set; Jules integration unavailable.")
        }
        
        // HTX reactor for KeyMux/ModelMux API calls
        val nioSupervisor = NioSupervisor()
        nioSupervisor.open()
        val htxElement: HtxElement = openHtxElement(
            nioSupervisor = nioSupervisor,
            parentJob = coroutineContext[Job],
        )
        System.err.println("[KEYMUX] HTX reactor open: ${htxElement.state} — KeyMux/ModelMux via TLS codec")
        
        // MuxReactorElement: the live key+quota surface
        val muxReactor = MuxReactorElement(
            initialConfig = MuxReactorConfig(),
            parentJob = coroutineContext[Job],
        )
        muxReactor.open()
        
        // Seed from already-resolved KeyMux env keys
        withContext(Dispatchers.IO) {
            for (provider in listOf("jules", "brain", "openai", "anthropic", "google", "nvidia", "xai")) {
                val v = keyMux.get("$provider.default.key") ?: continue
                muxReactor.loadCredentialPool(
                    mapOf(provider to listOf(
                        MuxCredentialRecord(
                            id = "$provider-default",
                            label = "$provider-default",
                            baseUrl = "",
                            lastStatus = "active",
                        )
                    ))
                )
                muxReactor.recordAccess(
                    keyId = "$provider-default",
                    provider = provider,
                    label = "$provider-default",
                )
            }
        }
        System.err.println("[KEYMUX] MuxReactor open: ${muxReactor.state} — KeyMux/ModelMux live")
        
        // Start reactor tick loop
        val reactorJob = muxReactor.startLoop(CoroutineScope(Dispatchers.Default))
        
        // Health socket (UNIX domain socket, not TCP)
        val healthSockFile = config.healthSock
        if (healthSockFile.exists()) healthSockFile.delete()
        var serverSocket: ServerSocketChannel? = null
        var bindAttempt = 0
        while (serverSocket == null && bindAttempt < 3) {
            try {
                serverSocket = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
                serverSocket.bind(UnixDomainSocketAddress.of(healthSockFile.toPath()))
            } catch (e: Throwable) {
                System.err.println("[KEYMUX] health.sock bind attempt ${bindAttempt + 1} failed: ${e.message}")
                try { serverSocket?.close() } catch (_: Exception) {}
                serverSocket = null
                if (healthSockFile.exists()) healthSockFile.delete()
                bindAttempt++
            }
        }
        if (serverSocket == null) {
            System.err.println("[KEYMUX] health.sock bind FAILED after 3 attempts; aborting")
            return
        }
        
        val healthJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                var client: java.nio.channels.SocketChannel? = null
                try {
                    client = serverSocket!!.accept()
                    val state = muxReactor.flowState.value
                    val uptimeMs = System.currentTimeMillis() - daemonStartTime
                    val msg = "ALIVE $uptimeMs ${state.currentlyRunning} ${state.availableKeys} ${state.maxInProgress} ${state.maxSpawn} ${state.tickSequence}\n"
                    val buf = java.nio.ByteBuffer.wrap(msg.toByteArray())
                    while (buf.hasRemaining()) {
                        client.write(buf)
                    }
                } catch (e: Exception) { /* ignore */ }
                finally {
                    try { client?.close() } catch (_: Exception) {}
                }
            }
        }
        
        System.err.println("[KEYMUX] daemon up. forgeHome=${config.forgeHome} healthSock=${config.healthSock} port=${config.port}")
        
        // Run until cancelled
        try {
            reactorJob.join()
        } finally {
            healthJob.cancel()
            try { serverSocket?.close() } catch (_: Exception) {}
            if (healthSockFile.exists()) healthSockFile.delete()
            muxReactor.close()
            htxElement.close()
            nioSupervisor.close()
        }
    }

    private var daemonStartTime = System.currentTimeMillis()
}

private fun nowMs(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()