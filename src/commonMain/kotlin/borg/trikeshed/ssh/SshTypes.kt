package borg.trikeshed.ssh

import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import kotlin.jvm.JvmInline

/**
 * SSH Protocol Numbers per RFC 4250 / RFC 4251 / RFC 4253.
 */
enum class SshMessageNumber(val code: Int) {
    DISCONNECT(1),
    IGNORE(2),
    UNIMPLEMENTED(3),
    DEBUG(4),
    SERVICE_REQUEST(5),
    SERVICE_ACCEPT(6),
    KEXINIT(20),
    NEWKEYS(21),
    KEXDH_INIT(30),
    KEXDH_REPLY(31),
    USERAUTH_REQUEST(50),
    USERAUTH_FAILURE(51),
    USERAUTH_SUCCESS(52),
    USERAUTH_BANNER(53),
    GLOBAL_REQUEST(80),
    REQUEST_SUCCESS(81),
    REQUEST_FAILURE(82),
    CHANNEL_OPEN(90),
    CHANNEL_OPEN_CONFIRMATION(91),
    CHANNEL_OPEN_FAILURE(92),
    CHANNEL_WINDOW_ADJUST(93),
    CHANNEL_DATA(94),
    CHANNEL_EXTENDED_DATA(95),
    CHANNEL_EOF(96),
    CHANNEL_CLOSE(97),
    CHANNEL_REQUEST(98),
    CHANNEL_SUCCESS(99),
    CHANNEL_FAILURE(100);

    companion object {
        fun fromCode(code: Int): SshMessageNumber? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Concentric SSH zero-cost value types and capabilities.
 */
@JvmInline
value class SshChannelId(val id: Int)

@JvmInline
value class SshSequenceNumber(val seq: Int) {
    inline fun next(): SshSequenceNumber = SshSequenceNumber(seq + 1)
}

typealias SshPacket = Join<SshMessageNumber, ByteArray>

fun SshPacket(number: SshMessageNumber, payload: ByteArray): SshPacket = number j payload
val SshPacket.messageNumber: SshMessageNumber get() = a
val SshPacket.payload: ByteArray get() = b

/**
 * Capability for concentric SSH invocation within TrikeShed NUID routing.
 */
data class SshCapability(val host: String, val port: Int = 22) : Capability("ssh") {
    override fun familyRoot(): Capability = SshAll
}

data object SshAll : Capability("ssh*")

/**
 * Concentric boundary scope for SSH operations.
 */
data class SshScope(
    val subnet: Subnet,
    val capability: SshCapability,
)
