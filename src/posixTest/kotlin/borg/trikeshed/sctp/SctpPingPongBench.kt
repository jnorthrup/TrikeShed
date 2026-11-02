@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package borg.trikeshed.sctp

import borg.trikeshed.htx.PPID_HTX11
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.channels.decimal
import borg.trikeshed.userspace.nio.channels.endpoint
import borg.trikeshed.userspace.nio.channels.ipv4
import borg.trikeshed.userspace.nio.channels.list
import borg.trikeshed.userspace.nio.channels.parseWhole
import borg.trikeshed.userspace.nio.channels.sockaddrIpv4
import borg.trikeshed.userspace.nio.channels.sockaddrUnixName
import borg.trikeshed.userspace.nio.channels.spi.PosixChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixReactorOperations
import borg.trikeshed.userspace.nio.channels.unixName
import kotlinx.cinterop.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.*
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * The cost of one request and response through SctpUdpPort, over UDP on 127.0.0.1 and over AF_UNIX datagrams, measured
 * by one harness in one process: a client port and a server port, each with its owner loop on this thread and its own
 * poll reactor, as the server and the load generator run them. A request is one HTX1 message of SCTP_PINGPONG_BYTES
 * bytes (default 300) and its response is as long. Each round trip is one request answered by one response; with
 * several associations each keeps one request in flight.
 *
 * It does nothing unless SCTP_PINGPONG is set to the number of round trips of each run. Also read:
 *   SCTP_PINGPONG_USERS       associations, a list (default 1,64)
 *   SCTP_PINGPONG_TRANSPORTS  udp, unix, or udp,unix (default udp,unix)
 *   SCTP_PINGPONG_MTU         the packet size (default 1452, the port's)
 *   SCTP_PINGPONG_FLOOR       any value: also time the bare sendto/recvfrom of the same datagrams on Linux, no SCTP
 * It prints one PINGPONG line per run (and one FLOOR line per transport): microseconds of wall time and of CPU time
 * (user and system, all threads of the process) per round trip.
 */
class SctpPingPongBench {
    val linux = Platform.osFamily == OsFamily.LINUX

    fun env(name: String): String? = getenv(name)?.toKString()

    /**
     * The CPU the process has used, all its threads: the total in microseconds (clock_gettime), and the system share of
     * it (times(2): clock ticks, 10 ms each, so a figure over a few seconds of work).
     */
    fun cpuMicros(): Twin<Long> = memScoped {
        val now = alloc<timespec>()
        clock_gettime(CLOCK_PROCESS_CPUTIME_ID.convert(), now.ptr)
        val ticks = alloc<tms>()
        times(ticks.ptr)
        (now.tv_sec * 1_000_000L + now.tv_nsec / 1000) j (ticks.tms_stime.toLong() * 1_000_000L / sysconf(_SC_CLK_TCK))
    }

    /** A number with two decimals: common code has no String.format. */
    fun fixed(x: Double): String {
        val hundredths = (x * 100 + 0.5).toLong()
        return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
    }

    /** [n] round trips over [users] associations of one client port and one server port. */
    suspend fun run(unix: Boolean, users: Int, n: Int, bytes: Int, mtu: Int, report: Boolean) = coroutineScope {
        val channels = PosixChannelOperations()
        val secret = "pingpong".encodeToByteArray()
        val serverFile = "/tmp/sctp-pingpong-server"
        val clientFile = "/tmp/sctp-pingpong-client"
        if (unix && !linux) { unlink(serverFile); unlink(clientFile) }
        val serverName = unixName(if (linux) "@sctp-pingpong-server" else serverFile)
        val clientName = unixName(if (linux) "" else clientFile)
        val request = "GET /feed HTTP/1.1\r\nHost: bench\r\n\r\n".padEnd(bytes, '.').encodeToByteArray()
        val response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".padEnd(bytes, '.').encodeToByteArray()
        lateinit var server: SctpUdpPort
        lateinit var client: SctpUdpPort
        val echo = object : SctpUpper {
            override fun associated(tcb: SctpTcb) {}
            override fun message(tcb: SctpTcb, ppid: Ppid, bytes: ByteArray, offset: Int, length: Int) {
                server.table.send(tcb, ppid, response, 0, response.size)
            }
            override fun closed(tcb: SctpTcb) {}
        }
        var associated = 0
        var sent = 0
        var answered = 0
        val up = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val caller = object : SctpUpper {
            override fun associated(tcb: SctpTcb) { if (++associated == users) up.complete(Unit) }
            override fun message(tcb: SctpTcb, ppid: Ppid, bytes: ByteArray, offset: Int, length: Int) {
                if (++answered == n) finished.complete(Unit)
                else if (sent < n) { sent++; client.submit(tcb, PPID_HTX11, request, 0, request.size) }
            }
            override fun closed(tcb: SctpTcb) {}
        }
        if (unix) {
            server = SctpUdpPort(channels, PosixReactorOperations(), serverName, 5001, echo, secret, mtu = mtu).open()
            client = SctpUdpPort(channels, PosixReactorOperations(), clientName, 5002, caller, secret, mtu = mtu).open()
        } else {
            server = SctpUdpPort(channels, PosixReactorOperations(), parseWhole("127.0.0.1:35001") { endpoint() }!!, echo, secret, mtu = mtu).open()
            client = SctpUdpPort(channels, PosixReactorOperations(), parseWhole("127.0.0.1:35002") { endpoint() }!!, caller, secret, mtu = mtu).open()
        }
        val serving = launch { server.run() }
        val dialing = launch { client.run() }
        val tcbs = arrayOfNulls<SctpTcb>(users)
        for (i in (0 until users).toSeries().view) tcbs[i] = if (unix) client.connect(serverName, 5001) else client.connect(parseWhole("127.0.0.1:35001") { endpoint() }!!)
        withTimeout(30_000) { up.await() }
        val clock = TimeSource.Monotonic.markNow()
        val (cpu0, system0) = cpuMicros()
        for (i in (0 until users).toSeries().view) { sent++; client.submit(tcbs[i]!!, PPID_HTX11, request, 0, request.size) }
        withTimeout(600_000) { finished.await() }
        val wall = clock.elapsedNow().inWholeMicroseconds
        val (cpu1, system1) = cpuMicros()
        client.stop(); server.stop()
        dialing.join(); serving.join()
        client.close(); server.close()
        if (unix && !linux) { unlink(serverFile); unlink(clientFile) }
        if (report) println("PINGPONG transport=${if (unix) "unix" else "udp"} users=$users n=$n bytes=$bytes mtu=$mtu " +
            "wall_us=${fixed(wall.toDouble() / n)} cpu_us=${fixed((cpu1 - cpu0).toDouble() / n)} " +
            "user_us=${fixed((cpu1 - cpu0 - system1 + system0).toDouble() / n)} sys_us=${fixed((system1 - system0).toDouble() / n)} refused=${client.refused + server.refused}")
    }

    /**
     * The bare datagram exchange, Linux only (the addresses are Linux sockaddr bytes): [n] round trips of two named
     * sockets on this thread, a sendto and a recvfrom each way.
     */
    fun floor(unix: Boolean, n: Int, bytes: Int) {
        val a = socket(if (unix) AF_UNIX else AF_INET, SOCK_DGRAM, 0)
        val b = socket(if (unix) AF_UNIX else AF_INET, SOCK_DGRAM, 0)
        check(a >= 0 && b >= 0) { "socket" }
        val loopback = parseWhole("127.0.0.1") { ipv4() }!!
        val addrA = if (unix) sockaddrUnixName(unixName("@floor-a")) else sockaddrIpv4(loopback, 35101)
        val addrB = if (unix) sockaddrUnixName(unixName("@floor-b")) else sockaddrIpv4(loopback, 35102)
        val size: socklen_t = addrA.size.convert()
        val payload = ByteArray(bytes)
        addrA.usePinned { pa -> addrB.usePinned { pb -> payload.usePinned { p ->
            val at = p.addressOf(0)
            val toA = pa.addressOf(0).reinterpret<sockaddr>()
            val toB = pb.addressOf(0).reinterpret<sockaddr>()
            check(bind(a, toA, size) == 0) { "bind a" }
            check(bind(b, toB, size) == 0) { "bind b" }
            val clock = TimeSource.Monotonic.markNow()
            val (cpu0, system0) = cpuMicros()
            var i = 0
            while (i++ < n) {
                check(sendto(a, at, bytes.convert(), 0, toB, size).toInt() == bytes) { "sendto a" }
                check(recvfrom(b, at, bytes.convert(), 0, null, null).toInt() == bytes) { "recvfrom b" }
                check(sendto(b, at, bytes.convert(), 0, toA, size).toInt() == bytes) { "sendto b" }
                check(recvfrom(a, at, bytes.convert(), 0, null, null).toInt() == bytes) { "recvfrom a" }
            }
            val wall = clock.elapsedNow().inWholeMicroseconds
            val (cpu1, system1) = cpuMicros()
            println("FLOOR transport=${if (unix) "unix" else "udp"} n=$n bytes=$bytes wall_us=${fixed(wall.toDouble() / n)} " +
                "cpu_us=${fixed((cpu1 - cpu0).toDouble() / n)} sys_us=${fixed((system1 - system0).toDouble() / n)}")
        } } }
        close(a); close(b)
    }

    @Test
    fun pingPong() = runBlocking {
        val n = env("SCTP_PINGPONG")?.let { parseWhole(it) { decimal(9, 100_000_000) } } ?: return@runBlocking
        val users = env("SCTP_PINGPONG_USERS")?.let { parseWhole(it) { list { decimal(6, 100_000) } } } ?: s_[1, 64]
        val transports = env("SCTP_PINGPONG_TRANSPORTS") ?: "udp,unix"
        val bytes = env("SCTP_PINGPONG_BYTES")?.let { parseWhole(it) { decimal(5, 60_000) } } ?: 300
        val mtu = env("SCTP_PINGPONG_MTU")?.let { parseWhole(it) { decimal(5, 60_000) } } ?: 1452
        if (env("SCTP_PINGPONG_FLOOR") != null && linux) for (t in s_["udp", "unix"].view) if (t in transports) floor(t == "unix", n, bytes)
        for (u in users.view) for (t in s_["udp", "unix"].view) if (t in transports) {
            run(t == "unix", u, minOf(n / 10, 20_000).coerceAtLeast(u), bytes, mtu, report = false)
            run(t == "unix", u, n, bytes, mtu, report = true)
        }
    }
}
