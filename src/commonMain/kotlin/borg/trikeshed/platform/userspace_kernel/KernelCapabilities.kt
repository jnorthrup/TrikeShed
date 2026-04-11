package borg.literbike.userspace_kernel

/**
 * Kernel Capabilities Detection
 *
 * Provides a unified interface for detecting system capabilities
 * related to kernel features, performance optimizations, and
 * low-level system interfaces.
 */
object KernelCapabilitiesModule {

    data class SystemCapabilities(
        val ioUringAvailable: Boolean,
        val ebpfCapable: Boolean,
        val hugepagesAvailable: Boolean,
        val numaSupported: Boolean,
        val simdExtensions: List<String>
    ) {
        companion object {
            fun detect(): SystemCapabilities = SystemCapabilities(
                ioUringAvailable = detectIoUring(),
                ebpfCapable = detectEbpf(),
                hugepagesAvailable = detectHugepages(),
                numaSupported = detectNuma(),
                simdExtensions = detectSimdExtensions()
            )

            private fun detectIoUring(): Boolean {
                val osName = System.getProperty("os.name") ?: ""
                return osName.contains("linux", ignoreCase = true)
            }

            private fun detectEbpf(): Boolean {
                val osName = System.getProperty("os.name") ?: ""
                return osName.contains("linux", ignoreCase = true)
            }

            private fun detectHugepages(): Boolean = false

            private fun detectNuma(): Boolean = false

            private fun detectSimdExtensions(): List<String> {
                val extensions = mutableListOf<String>()
                val osArch = System.getProperty("os.arch") ?: ""

                if (osArch.contains("x86_64") || osArch.contains("amd64")) {
                    extensions.add("SSE2")
                    extensions.add("SSE4.2")
                    // AVX/AVX2 detection would require native code
                } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
                    extensions.add("NEON")
                }

                return extensions
            }
        }

        fun printCapabilities() {
            println("System Capabilities:")
            println("  io_uring:        $ioUringAvailable")
            println("  eBPF:           $ebpfCapable")
            println("  Hugepages:      $hugepagesAvailable")
            println("  NUMA:           $numaSupported")
            println("  SIMD Extensions: ${simdExtensions.joinToString(", ")}")
        }
    }
}
