package borg.literbike.userspace_kernel

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Direct syscall network operations - densified from literbike
 *
 * Low-level network operations using direct syscalls for maximum performance.
 */
object SyscallNetModule {

    data class NetworkInterface(
        val name: String,
        val addrs: MutableList<InterfaceAddr>,
        val flags: Int
    )

    sealed class InterfaceAddr {
        data class V4(val address: Inet4Address) : InterfaceAddr()
        data class V6(val address: Inet6Address) : InterfaceAddr()
    }

    class SocketOps {
        companion object {
            fun create(): SocketOps = SocketOps()
        }
    }

    fun getDefaultGateway(): Result<Inet4Address> = runCatching {
        val osName = System.getProperty("os.name") ?: ""

        when {
            osName.contains("linux", ignoreCase = true) -> parseProcNetRoute()
            osName.contains("mac", ignoreCase = true) -> parseNetstatRoute()
            else -> throw IOException("Unsupported OS")
        }
    }

    fun getDefaultGatewayV6(): Result<Inet6Address> = runCatching {
        val osName = System.getProperty("os.name") ?: ""

        when {
            osName.contains("linux", ignoreCase = true) -> parseProcNetIpv6Route()
            osName.contains("mac", ignoreCase = true) -> parseNetstatRouteV6()
            else -> throw IOException("Unsupported OS")
        }
    }

    fun getDefaultLocalIpv4(): Result<Inet4Address> = runCatching {
        val targets = listOf(
            InetSocketAddress("1.1.1.1", 80),
            InetSocketAddress("8.8.8.8", 80),
            InetSocketAddress("9.9.9.9", 80)
        )

        for (target in targets) {
            try {
                val sock = DatagramSocket()
                sock.connect(target)
                val local = sock.localAddress
                sock.close()
                if (local is Inet4Address) return Result.success(local)
            } catch (_: Exception) {
                // Try next target
            }
        }

        throw IOException("unable to determine local IPv4")
    }

    fun getDefaultLocalIpv6(): Result<Inet6Address> = runCatching {
        val targets = listOf(
            InetSocketAddress("2001:4860:4860::8888", 80),
            InetSocketAddress("2606:4700:4700::1111", 80)
        )

        for (target in targets) {
            try {
                val sock = DatagramSocket()
                sock.connect(target)
                val local = sock.localAddress
                sock.close()
                if (local is Inet6Address) return Result.success(local)
            } catch (_: Exception) {
                // Try next target
            }
        }

        throw IOException("unable to determine local IPv6")
    }

    private fun parseProcNetRoute(): Inet4Address {
        val file = File("/proc/net/route")
        if (!file.exists()) throw IOException("/proc/net/route not found")

        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                val fields = line.split(Regex("\\s+"))
                if (fields.size >= 3 && fields[1] == "00000000") {
                    val gateway = fields[2].toIntOrNull(16)
                    if (gateway != null) {
                        val bytes = byteArrayOf(
                            (gateway and 0xFF).toByte(),
                            ((gateway shr 8) and 0xFF).toByte(),
                            ((gateway shr 16) and 0xFF).toByte(),
                            ((gateway shr 24) and 0xFF).toByte()
                        )
                        return InetAddress.getByAddress(bytes) as Inet4Address
                    }
                }
            }
        }

        throw IOException("No default gateway found")
    }

    private fun parseProcNetIpv6Route(): Inet6Address {
        val file = File("/proc/net/ipv6_route")
        if (!file.exists()) throw IOException("/proc/net/ipv6_route not found")

        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                val fields = line.split(Regex("\\s+"))
                if (fields.size >= 5 && fields[0] == "00000000000000000000000000000000") {
                    val gatewayHex = fields[4]
                    if (gatewayHex != "00000000000000000000000000000000") {
                        val bytes = ByteArray(16)
                        for (i in 0 until 16) {
                            val byteStr = gatewayHex.substring(i * 2, i * 2 + 2)
                            bytes[i] = byteStr.toInt(16).toByte()
                        }
                        return InetAddress.getByAddress(bytes) as Inet6Address
                    }
                }
            }
        }

        throw IOException("No default IPv6 gateway found")
    }

    private fun parseNetstatRoute(): Inet4Address {
        val process = ProcessBuilder("netstat", "-rn", "-f", "inet").start()
        val output = process.inputStream.bufferedReader().readText()

        for (line in output.lineSequence()) {
            if (line.startsWith("default") || line.startsWith("0.0.0.0")) {
                val fields = line.split(Regex("\\s+"))
                if (fields.size >= 2) {
                    return InetAddress.getByName(fields[1]) as Inet4Address
                }
            }
        }

        throw IOException("No default gateway found")
    }

    private fun parseNetstatRouteV6(): Inet6Address {
        val process = ProcessBuilder("netstat", "-rn", "-f", "inet6").start()
        val output = process.inputStream.bufferedReader().readText()

        for (line in output.lineSequence()) {
            if (line.startsWith("default") || line.startsWith("::")) {
                val fields = line.split(Regex("\\s+"))
                if (fields.size >= 2) {
                    return InetAddress.getByName(fields[1]) as Inet6Address
                }
            }
        }

        throw IOException("No default IPv6 gateway found")
    }
}
