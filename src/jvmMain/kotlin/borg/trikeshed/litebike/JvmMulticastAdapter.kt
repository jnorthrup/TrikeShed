@file:Suppress("UNCHECKED_CAST", "FunctionName")

package borg.trikeshed.litebike

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.litebike.taxonomy.Protocol
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.MembershipKey
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * JvmMulticastAdapter — the only place UDP multicast lives for the
 * LitebikeListenerElement.
 *
 * Wraps `java.nio.channels.DatagramChannel` to join the two multicast
 * groups litebike cares about:
 *   - mDNS / Bonjour:  224.0.0.251:5353  (RFC 6762)
 *   - SSDP / UPnP:     239.255.255.250:1900 (UPnP DA 1.1)
 *
 * Each accepted datagram is forwarded to `LitebikeListenerElement.accept(...)`
 * with the detected `Protocol.Bonjour` or `Protocol.Upnp`.
 *
 * This is a platform adapter, not a commonMain SPI. The commonMain
 * `MulticastChannel` stub remains unfilled (vocabulary-only); this file
 * is the real JVM implementation that makes multicast work.
 *
 * Lifecycle: every successful [joinAndForward] call returns a [MulticastHandle]
 * that tracks the read-loop [Job] and the underlying [MembershipKey].
 * Callers MUST keep the handle and call [JvmMulticastAdapter.close] (or
 * [MulticastHandle.close] individually) to cancel the read loops and
 * drop the multicast membership. The read-loop `CoroutineScope` is
 * owned by the adapter — never create it on a hot path you can't cancel.
 */
object JvmMulticastAdapter {

    data class MulticastGroup(
        val protocol: Protocol,
        val groupAddress: String,
        val port: Int,
        val networkInterface: String? = null,   // null = default interface
    )

    val MdnsGroup = MulticastGroup(Protocol.Bonjour, "224.0.0.251", 5353)
    val SsdpGroup = MulticastGroup(Protocol.Upnp, "239.255.255.250", 1900)

    /**
     * Returned by [joinAndForward] so callers can cancel the read loop
     * and drop the multicast membership at shutdown. Hold the handle
     * for the life of the daemon.
     */
    data class MulticastHandle(
        val group: MulticastGroup,
        val key: MembershipKey,
        val channel: DatagramChannel,
        val job: Job,
    ) {
        /** Cancel the read loop and drop the multicast membership. Idempotent. */
        fun close() {
            job.cancel()
            runCatching { key.drop() }
            runCatching { channel.close() }
        }
    }

    /** Live handles — referenced by the daemon so shutdown can cancel them. */
    private val liveHandles: MutableList<MulticastHandle> = java.util.Collections.synchronizedList(mutableListOf())

    /**
     * Join a multicast group and pipe datagrams into the listener.
     * Returns a [MulticastHandle] whose [MulticastHandle.job] is the
     * read loop and whose [MulticastHandle.key] is the multicast
     * membership. Call [JvmMulticastAdapter.close] (or [MulticastHandle.close])
     * to tear down.
     */
    suspend fun joinAndForward(
        listener: LitebikeListenerElement,
        group: MulticastGroup,
    ): MulticastHandle {
        val dc = DatagramChannel.open()
        // R03 — SO_REUSEPORT-first on macOS.
        // macOS mDNSResponder owns port 5353; SO_REUSEADDR alone yields EADDRINUSE.
        // SO_REUSEPORT is in StandardSocketOptions since JDK 9 but may be
        // unsupported at runtime (some platforms/JVMs), hence runCatching.
        runCatching {
            dc.setOption(StandardSocketOptions.SO_REUSEPORT, true)
        }
        dc.setOption(StandardSocketOptions.SO_REUSEADDR, true)
        dc.bind(InetSocketAddress(group.port))
        val ni = group.networkInterface?.let { NetworkInterface.getByName(it) }
            ?: NetworkInterface.getNetworkInterfaces().toList().firstOrNull { it.isUp && it.supportsMulticast() }
            ?: error("No multicast-capable interface found")
        val key = dc.join(
            java.net.InetAddress.getByName(group.groupAddress),
            ni,
        )
        // Read loop on a dedicated dispatcher so we don't block the caller.
        // R04 — owned scope tracked via the handle, NOT leaked.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch {
            val buf = ByteBuffer.allocate(2048)
            try {
                while (true) {
                    buf.clear()
                    val sender = dc.receive(buf) ?: continue
                    buf.flip()
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    runBlocking {
                        listener.accept(group.protocol, bytes)
                    }
                }
            } finally {
                // Best-effort cleanup so a cancellation here also drops the
                // membership and closes the socket. Idempotent with handle.close().
                runCatching { key.drop() }
                runCatching { dc.close() }
            }
        }
        val handle = MulticastHandle(group, key, dc, job)
        synchronized(liveHandles) { liveHandles.add(handle) }
        return handle
    }

    /**
     * Join both mDNS and SSDP groups. Convenience for the common case.
     */
    suspend fun joinAll(listener: LitebikeListenerElement): List<MulticastHandle> {
        return listOf(
            joinAndForward(listener, MdnsGroup),
            joinAndForward(listener, SsdpGroup),
        )
    }

    /**
     * Cancel every live read loop and drop every multicast membership.
     * Safe to call multiple times. The daemon invokes this from a
     * shutdown hook so sockets don't leak past JVM exit.
     */
    fun close() {
        val snapshot = synchronized(liveHandles) {
            val s = liveHandles.toList()
            liveHandles.clear()
            s
        }
        for (h in snapshot) {
            runCatching { h.close() }
        }
    }
}
