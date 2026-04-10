package borg.trikeshed.platform.kernel

/**
 * Direct syscall network operations
 *
 * Low-level network operations using direct syscalls for maximum performance.
 * Integrated with ENDGAME kernel bypass for zero-overhead networking.
 */

/**
 * Network interface for densified operations
 */
data class NetworkInterface(
    val name: String,
    val addrs: List<InterfaceAddr>,
    val flags: Int
)

sealed class InterfaceAddr {
    data class V4(val address: String) : InterfaceAddr()
    data class V6(val address: String) : InterfaceAddr()
}

/**
 * Socket operations with kernel bypass integration
 *
 * Note: Raw pointer operations and libc calls are platform-specific.
 * In commonMain we declare the interface; platform implementations
 * provide actual syscalls.
 */
class SocketOps(
    val densified: DensifiedKernel? = null
) {
    /**
     * UNSAFE: Direct syscall send with optional kernel bypass
     * In commonMain this is a stub; actual implementation is in linuxMain/posixMain
     */
    fun send(fd: RawFd, msg: ByteArray, flags: Int): Long {
        densified?.let {
            // In actual implementation: it.densifiedSend(fd, msg, flags)
        }
        // Fallback: platform-specific send
        return -1L
    }

    /**
     * UNSAFE: Direct syscall recv with optional kernel bypass
     */
    fun recv(fd: RawFd, msg: ByteArray, flags: Int): Long {
        densified?.let {
            // In actual implementation: it.densifiedRecv(fd, msg, flags)
        }
        // Fallback: platform-specific recv
        return -1L
    }
}

/**
 * Get default gateway (platform-specific implementation)
 */
expect fun getDefaultGateway(): Result<String>

/**
 * Get default IPv6 gateway (platform-specific implementation)
 */
expect fun getDefaultGatewayV6(): Result<String>

/**
 * Get default local IPv4 using UDP socket probing
 */
fun getDefaultLocalIpv4(): Result<String> {
    // Simplified implementation - in Kotlin/Native would use platform sockets
    return Result.failure(NotImplementedError("Platform-specific implementation required"))
}

/**
 * Get default local IPv6 using UDP socket probing
 */
fun getDefaultLocalIpv6(): Result<String> {
    // Simplified implementation
    return Result.failure(NotImplementedError("Platform-specific implementation required"))
}

/**
 * List network interfaces with addresses (platform-specific)
 */
expect fun listInterfaces(): Result<Map<String, NetworkInterface>>
