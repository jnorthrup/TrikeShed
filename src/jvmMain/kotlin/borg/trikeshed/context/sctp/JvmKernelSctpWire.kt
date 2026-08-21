package borg.trikeshed.sctp

/**
 * Kernel-SCTP backing for [SctpWire] — STUB, deliberately not registered.
 *
 * When the bridge is burned: open `IPPROTO_SCTP` / `SOCK_SEQPACKET` sockets (JDK has no SCTP API on
 * macOS; on Linux use `com.sun.nio.sctp` where present or the io_uring facade in
 * `borg.trikeshed.sctp.LiburingFacadeSeam`), then install with `SctpWire.register(JvmKernelSctpWire)`.
 * Until then every call throws so a misconfiguration is loud, never silent.
 *
 * [kernelOffload] is true here because a kernel SCTP socket inherits NIC CRC32c/GSO — the property the
 * loopback and any userspace packetizer cannot claim.
 */
object JvmKernelSctpWire : SctpWire {
    override val backing: String = "kernel-sctp (stub)"
    override val kernelOffload: Boolean = true

    override suspend fun bind(port: Int): Int = notWired()
    override suspend fun send(path: String, packet: ByteArray): Unit = notWired()
    override suspend fun receive(port: Int): SctpDatagram? = notWired()
    override suspend fun close(): Unit = notWired()

    private fun notWired(): Nothing =
        throw UnsupportedOperationException("JvmKernelSctpWire is a stub: kernel SCTP is not wired yet (SctpWire SPI)")
}
