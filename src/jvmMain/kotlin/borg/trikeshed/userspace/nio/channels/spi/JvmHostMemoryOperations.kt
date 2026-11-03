package borg.trikeshed.userspace.nio.channels.spi

import java.lang.management.ManagementFactory
import com.sun.management.OperatingSystemMXBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The JVM's OS metrics; these do not account for all GPU allocations or reserve memory. */
class JvmHostMemoryOperations : HostMemoryOperations {
    override suspend fun sample(): HostMemory = withContext(Dispatchers.IO) {
        val bean = ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean
            ?: error("Host memory metrics unavailable")
        val total = bean.totalMemorySize
        val free = bean.freeMemorySize
        val swap = bean.totalSwapSpaceSize
        val swapFree = bean.freeSwapSpaceSize
        check(total > 0 && free in 0..total && swap >= 0 && swapFree in 0..swap) {
            "Host memory metrics invalid"
        }
        HostMemory(free, swap - swapFree)
    }
}
