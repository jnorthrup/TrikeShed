package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.platform.loadPlatformHost
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations

/**
 * Explicit source ports for dials that bind before CONNECT. A connect that leaves the port to the kernel takes it from
 * the host's ephemeral range, and an unbound connect needs a number free on every local address: a client holding
 * every ephemeral number across aliases leaves the host no outbound port at all. Bound dials stay below that range.
 *
 * `TRIKESHED_SOURCE_PORTS=lo-hi` sets the range; otherwise the host default sits under its ephemeral range
 * (macOS and Windows 49152-65535, Linux ip_local_port_range 32768-60999 by default).
 */
class SourcePorts(val range: PortRange) {
    init {
        require(!range.isEmpty() && range.first >= 1 && range.last <= 0xFFFF) { "source ports out of 1..65535: $range" }
    }

    /** The next port per source address (its 4 or 16 octets), round-robin over [range]. */
    val cursors = HashMap<List<Byte>, Int>()

    fun next(source: Ipv4): Port {
        val key = source.asList()
        val at = cursors[key] ?: 0
        cursors[key] = (at + 1) % (range.last - range.first + 1)
        return range.first + at
    }

    companion object {
        const val ENVIRONMENT = "TRIKESHED_SOURCE_PORTS"

        fun parse(value: CharSequence): PortRange =
            requireNotNull(parseWhole(value) { portRange() }) { "$ENVIRONMENT must be port or lo-hi: $value" }

        fun hostDefault(os: String?): PortRange = when (os) {
            "linux" -> 10000..32767
            else -> 10000..48999
        }

        fun configured(): SourcePorts = SourcePorts(
            SystemOperations.default.getenv(ENVIRONMENT)?.takeIf { it.isNotBlank() }?.let(::parse)
                ?: hostDefault(loadPlatformHost().descriptor.os)
        )
    }
}
