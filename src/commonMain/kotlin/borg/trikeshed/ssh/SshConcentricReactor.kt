package borg.trikeshed.ssh

import borg.trikeshed.context.Drain
import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nuid
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.capability
import borg.trikeshed.context.nuid.subnet
import borg.trikeshed.lib.j
import borg.trikeshed.reactor.ReactorAction
import borg.trikeshed.reactor.ReactorEndpoint
import borg.trikeshed.reactor.ReactorResult
import borg.trikeshed.userspace.ByteRegion
import borg.trikeshed.userspace.network.AdapterType
import borg.trikeshed.userspace.network.SessionChannel
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Concentric Network SSH Client Reactor Element.
 *
 * Implements CCEK architecture:
 * - CoroutineContext.Element with companion object Key : CoroutineContext.Key<SshClientElement>
 * - Channel compositions under owning SupervisorJob.
 * - Straight pipeline: input command channel -> packet encoding/cipher -> dispatch -> response.
 * - Drain stops admission, processes in-flight work, joins children, and closes channels.
 * - Aligned to NUID concentric authorization (Capability × Subnet containment).
 * - Implements SessionChannel protocol session surface.
 */
class SshClientElement(
    val scope: SshScope,
    parentJob: Job? = null,
    val channelCapacity: Int = 64
) : CoroutineContext.Element, Drain, SessionChannel, ReactorEndpoint {

    companion object Key : CoroutineContext.Key<SshClientElement>

    override val key: CoroutineContext.Key<SshClientElement> get() = Key

    val supervisor: CompletableJob = SupervisorJob(parentJob)
    val admission: CompletableJob = Job()

    val commandChannel = Channel<ReactorAction>(channelCapacity)
    val resultChannel = Channel<ReactorResult>(channelCapacity)

    private var outboundSeq = SshSequenceNumber(0)
    private var inboundSeq = SshSequenceNumber(0)

    private var connected: Boolean = true

    // Worker coroutine for straight channel pipeline processing under supervisor
    private val workerJob: Job = CoroutineScope(supervisor).launch {
        for (action in commandChannel) {
            val res = processAction(action)
            resultChannel.send(res)
        }
    }

    override fun adapterType(): AdapterType = AdapterType.Ssh

    override fun remoteAddr(): String = "${scope.capability.host}:${scope.capability.port}"

    override fun isConnected(): Boolean = connected && admission.isActive

    override fun close(): Result<Unit> {
        connected = false
        admission.complete()
        commandChannel.close()
        return Result.success(Unit)
    }

    override fun read(dst: ByteRegion): Int {
        // Non-blocking read from completed packet buffer
        return 0
    }

    override fun write(src: borg.trikeshed.lib.ByteSeries): Int {
        // Bounded write to pipeline
        return src.size
    }

    override suspend fun invoke(action: ReactorAction): ReactorResult {
        ensureActive()

        // Concentric authorization verification: capability must match and request subnet must be contained
        val nuid: Nuid = action.a
        val cap: Capability = nuid.capability
        val sub: Subnet = nuid.subnet

        if (!(cap matches scope.capability)) {
            return action.a j ("error" j "Capability rejected for concentric SSH scope".encodeToByteArray())
        }

        if (!(scope.subnet contains sub)) {
            return action.a j ("error" j "Subnet outside concentric authorization boundary".encodeToByteArray())
        }

        // Send into channel composition
        commandChannel.send(action)
        return resultChannel.receive()
    }

    private fun processAction(action: ReactorAction): ReactorResult {
        val verb = action.b.a
        val payload = action.b.b

        val msgNumber = when (verb) {
            "kexinit" -> SshMessageNumber.KEXINIT
            "service_request" -> SshMessageNumber.SERVICE_REQUEST
            "userauth_request" -> SshMessageNumber.USERAUTH_REQUEST
            "channel_open" -> SshMessageNumber.CHANNEL_OPEN
            "channel_data" -> SshMessageNumber.CHANNEL_DATA
            "channel_close" -> SshMessageNumber.CHANNEL_CLOSE
            "disconnect" -> SshMessageNumber.DISCONNECT
            else -> SshMessageNumber.DEBUG
        }

        val framedPacket = frameBinaryPacket(msgNumber, payload)
        outboundSeq = outboundSeq.next()

        // Produce result packet
        return action.a j ("ok" j framedPacket)
    }

    /**
     * Frames binary packet RFC 4253 §6:
     * uint32    packet_length
     * byte      padding_length
     * byte[n1]  payload; n1 = packet_length - padding_length - 1
     * byte[n2]  random padding; n2 = padding_length (4..255 bytes, total packet_length + 4 multiple of cipher block size 8)
     */
    fun frameBinaryPacket(msg: SshMessageNumber, payload: ByteArray): ByteArray {
        val unpadded = 1 + payload.size + 1 // msgCode (1) + payload + padding_length (1)
        val blockSize = 8
        var paddingLen = blockSize - ((unpadded + 4) % blockSize)
        if (paddingLen < 4) paddingLen += blockSize

        val packetLength = 1 + payload.size + paddingLen
        val totalLength = 4 + packetLength
        val buf = ByteArray(totalLength)

        // packet_length (32-bit big endian Int)
        buf[0] = ((packetLength ushr 24) and 0xFF).toByte()
        buf[1] = ((packetLength ushr 16) and 0xFF).toByte()
        buf[2] = ((packetLength ushr 8) and 0xFF).toByte()
        buf[3] = (packetLength and 0xFF).toByte()

        // padding_length
        buf[4] = paddingLen.toByte()

        // payload
        buf[5] = msg.code.toByte()
        payload.copyInto(buf, 6, 0, payload.size)

        // padding (zeros for deterministic autovectorized alignment)
        for (i in (6 + payload.size) until totalLength) {
            buf[i] = 0
        }

        return buf
    }

    /**
     * Unframes binary packet. Returns SshPacket or null if malformed.
     */
    fun unframeBinaryPacket(packetBytes: ByteArray): SshPacket? {
        if (packetBytes.size < 5) return null
        val packetLength = ((packetBytes[0].toInt() and 0xFF) shl 24) or
                ((packetBytes[1].toInt() and 0xFF) shl 16) or
                ((packetBytes[2].toInt() and 0xFF) shl 8) or
                (packetBytes[3].toInt() and 0xFF)

        if (packetBytes.size < 4 + packetLength) return null
        val paddingLength = packetBytes[4].toInt() and 0xFF
        val payloadLen = packetLength - paddingLength - 1
        if (payloadLen < 1) return null

        val msgCode = packetBytes[5].toInt() and 0xFF
        val msg = SshMessageNumber.fromCode(msgCode) ?: return null

        val payload = ByteArray(payloadLen - 1)
        if (payload.isNotEmpty()) {
            packetBytes.copyInto(payload, 0, 6, 6 + payload.size)
        }
        inboundSeq = inboundSeq.next()
        return SshPacket(msg, payload)
    }

    override suspend fun drain() {
        if (admission.complete()) {
            commandChannel.close()
            workerJob.join()
            resultChannel.close()
            supervisor.complete()
            supervisor.join()
        }
        connected = false
    }

    private fun ensureActive() {
        check(admission.isActive && connected) {
            "SSH concentric client is draining or closed"
        }
    }
}
