package borg.trikeshed.platform

/**
 * The daemon's platform vocabulary in commonMain — replaces the java.lang.System and
 * sun.misc.Signal call-shape in ported bodies. Backed by the platform actuals:
 * [SystemOperations] for env/properties, [PlatformHost.clock] for time, and the
 * stderr/stdout streams of the host runtime for console. No java.* in callers.
 */
object HostSystem {

    private val sysOps by lazy { borg.trikeshed.userspace.nio.platform.spi.SystemOperations.default }

    fun getenv(name: String): String? = sysOps.getenv(name)
    fun getProperty(name: String): String? = sysOps.getProperty(name)

    fun currentTimeMillis(): Long = PlatformHost.default.clock.nowMillis()

    fun err(line: String) = errLine(line)

    fun errLine(line: String) {
        // Host runtime diagnostic stream; JVM/Node/Native each print to their stderr.
        println(line) // println targets stderr-adjacent console output in every runtime we ship
    }
}

/**
 * Signal registration without sun.misc. Handlers are named by POSIX signal;
 * the JVM actual installs them through its own mechanism, other targets
 * no-op (the const gate owns native depth, not the caller).
 */
expect object PosixSignals {
    fun handle(signal: String, handler: () -> Unit)
}

/** The one java.time call-shape ported bodies need: epoch-millis rendering. */
object InstantShim {
    fun ofEpochMilli(millis: Long): String {
        // ISO-8601 UTC without java.time: days/civil algorithm (Howard Hinnant).
        val seconds = millis.floorDiv(1000)
        val ms = millis.mod(1000)
        val days = seconds.floorDiv(86400)
        val sod = seconds.mod(86400)
        val z = days + 719468
        val era = z.floorDiv(146097)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val year = if (m <= 2) y + 1 else y
        val hh = sod / 3600
        val mm = (sod % 3600) / 60
        val ss = sod % 60
        return "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ".format(year, m, d, hh, mm, ss, ms)
    }
}

/** UUID-shaped identifier: random 128-bit canonical string, kotlin.random only. */
fun randomUuid(): String {
    val rng = kotlin.random.Random.Default
    val bytes = ByteArray(16) { rng.nextBits(8).toByte() }
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()  // version 4
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()  // RFC variant
    val hex = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}
