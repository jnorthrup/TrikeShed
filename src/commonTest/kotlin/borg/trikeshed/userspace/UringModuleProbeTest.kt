package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.platform.HostDescriptor
import borg.trikeshed.platform.HostRuntime
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Simulated Linux modules and CQEs; this suite does not establish native kernel support. */
class UringModuleProbeTest {
    private val linux = HostDescriptor(HostRuntime.JVM, rawOs = "Linux", rawArchitecture = "aarch64")
    private val fileOperations = UringOp.caps(UringOp.NOP, UringOp.OPENAT, UringOp.READ,
        UringOp.WRITE, UringOp.FSYNC, UringOp.FTRUNCATE, UringOp.CLOSE)

    private fun elf(machine: Int): ByteArray = ByteArray(20).apply {
        this[0] = 0x7f
        this[1] = 69
        this[2] = 76
        this[3] = 70
        this[4] = 2
        this[5] = 1
        this[6] = 1
        this[18] = machine.toByte()
        this[19] = (machine ushr 8).toByte()
    }

    private class ModuleFixture(
        override val capabilities: Long,
        private val native: Long = capabilities,
        private val result: Int = 0,
        private val completionCount: Int = 1,
        private val tokenOffset: Long = 0,
        private val capabilityFailure: Exception? = null,
        private val submissionFailure: Exception? = null,
        private val closeFailure: Exception? = null,
    ) : UserspaceChannelBackend {
        var closes = 0
        var submits = 0
        var submission: UringSubmission? = null
        override val nativeCapabilities: Long
            get() = capabilityFailure?.let { throw it } ?: native

        override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> {
            submits++
            submission = submissions.single()
            submissionFailure?.let { throw it }
            return List(completionCount) { SelectionResult(result, submission!!.userData + tokenOffset) }
        }

        override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> =
            submissions.size j { index -> UringCompletion(submissions[index].userData + tokenOffset, result, 0) }

        override fun close() {
            closes++
            closeFailure?.let { throw it }
        }
    }

    @Test
    fun linuxArchitectureAliasFixtures() {
        for (architecture in arrayOf("aarch64", "arm64")) {
            assertNull(uringHostConstraint(linux.copy(rawArchitecture = architecture)))
            assertNull(uringElfCompatibility(elf(183), architecture))
        }
        for (architecture in arrayOf("amd64", "x64", "x86_64")) {
            assertNull(uringHostConstraint(linux.copy(rawArchitecture = architecture)))
            assertNull(uringElfCompatibility(elf(62), architecture))
        }
    }

    @Test
    fun elfMismatchFixtures() {
        assertEquals(UringProbeState.ARCH_MISMATCH, uringElfCompatibility(elf(62), "arm64"))
        assertEquals(UringProbeState.ARCH_MISMATCH, uringElfCompatibility(elf(183), "x86_64"))
        assertEquals(UringProbeState.ARCH_UNSUPPORTED, uringElfCompatibility(elf(243), "riscv64"))
        assertEquals(UringProbeState.ABI_MISMATCH, uringElfCompatibility(elf(183).copyOf(19), "arm64"))
        for (header in arrayOf(
            elf(183).apply { this[0] = 0 },
            elf(183).apply { this[4] = 1 },
            elf(183).apply { this[5] = 2 },
            elf(183).apply { this[6] = 0 },
        )) assertEquals(UringProbeState.ABI_MISMATCH, uringElfCompatibility(header, "arm64"))
    }

    @Test
    fun hostConstraintFixtures() {
        assertEquals(UringProbeState.HOST_UNKNOWN, uringHostConstraint(linux.copy(rawOs = null)))
        assertEquals(UringProbeState.HOST_UNKNOWN, uringHostConstraint(linux.copy(rawArchitecture = null)))
        assertEquals(UringProbeState.HOST_UNKNOWN, uringHostConstraint(linux.copy(rawArchitecture = " ")))
        assertEquals(UringProbeState.HOST_UNSUPPORTED, uringHostConstraint(linux.copy(rawOs = "Darwin")))
        assertEquals(UringProbeState.HOST_UNSUPPORTED, uringHostConstraint(linux.copy(rawOs = "FreeBSD")))
        assertEquals(UringProbeState.ARCH_UNSUPPORTED, uringHostConstraint(linux.copy(rawArchitecture = "riscv64")))
        assertEquals(UringProbeState.ARCH_UNSUPPORTED, uringHostConstraint(linux.copy(rawArchitecture = "i686")))
    }

    @Test
    fun deniedCompletionFixtures() {
        for (errno in intArrayOf(1, 13)) assertCompletionFailure(errno, UringProbeState.SETUP_DENIED)
    }

    @Test
    fun unsupportedCompletionFixtures() {
        for (errno in intArrayOf(38, 95)) assertCompletionFailure(errno, UringProbeState.KERNEL_UNSUPPORTED)
    }

    @Test
    fun unknownErrnoFixture() {
        assertCompletionFailure(22, UringProbeState.PROBE_FAILED)
    }

    private fun assertCompletionFailure(errno: Int, state: UringProbeState) {
        val module = ModuleFixture(fileOperations, result = -errno)
        val discovery = probeUringBackend(linux, "fixture", module)
        assertEquals(state, discovery.report.state)
        assertEquals(UringProbePhase.EXECUTION, discovery.report.phase)
        assertEquals(errno, discovery.report.errno)
        assertEquals(fileOperations, discovery.report.nativeCapabilities)
        assertFalse(discovery.report.available)
        assertNull(discovery.backend)
        assertEquals(1, module.submits)
        assertEquals(1, module.closes)
    }

    @Test
    fun limitedNativeFixture() {
        val native = UringOp.caps(UringOp.NOP, UringOp.READ)
        val module = ModuleFixture(fileOperations, native = native)
        val discovery = probeUringBackend(linux, "fixture", module)
        assertEquals(UringProbeState.AVAILABLE_LIMITED, discovery.report.state)
        assertEquals(native, discovery.report.nativeCapabilities)
        assertTrue(discovery.report.available)
        assertSame(module, discovery.backend)
        assertEquals(UringOp.NOP, module.submission?.opcode)
        assertEquals(1, module.submits)
        assertEquals(0, module.closes)
        discovery.backend!!.close()
        assertEquals(1, module.closes)
    }

    @Test
    fun fullNativeFixture() {
        val module = ModuleFixture(fileOperations)
        val discovery = probeUringBackend(linux, "fixture", module)
        assertEquals(UringProbeState.AVAILABLE, discovery.report.state)
        assertEquals(UringProbePhase.EXECUTION, discovery.report.phase)
        assertEquals(fileOperations, discovery.report.nativeCapabilities)
        assertNull(discovery.report.errno)
        assertTrue(discovery.report.available)
        assertSame(module, discovery.backend)
        assertEquals(UringOp.NOP, module.submission?.opcode)
        assertEquals(1, module.submits)
        assertEquals(0, module.closes)
        discovery.backend!!.close()
        assertEquals(1, module.closes)
    }

    @Test
    fun malformedCompletionFixtures() {
        for (module in arrayOf(
            ModuleFixture(fileOperations, completionCount = 0),
            ModuleFixture(fileOperations, completionCount = 2),
            ModuleFixture(fileOperations, tokenOffset = 1),
            ModuleFixture(fileOperations, result = 1),
        )) {
            val discovery = probeUringBackend(linux, "fixture", module)
            assertEquals(UringProbeState.PROBE_FAILED, discovery.report.state)
            assertEquals(UringProbePhase.EXECUTION, discovery.report.phase)
            assertFalse(discovery.report.available)
            assertNull(discovery.backend)
            assertEquals(1, module.closes)
        }
    }

    @Test
    fun nativeMaskMismatchFixture() {
        val module = ModuleFixture(fileOperations, native = fileOperations or UringOp.SEND.mask)
        val discovery = probeUringBackend(linux, "fixture", module)
        assertEquals(UringProbeState.PROBE_FAILED, discovery.report.state)
        assertEquals(UringProbePhase.OPERATIONS, discovery.report.phase)
        assertNull(discovery.backend)
        assertEquals(0, module.submits)
        assertEquals(1, module.closes)
    }

    @Test
    fun missingOperationFixtures() {
        for (native in longArrayOf(0, UringOp.READ.mask)) {
            val module = ModuleFixture(fileOperations, native = native)
            val discovery = probeUringBackend(linux, "fixture", module)
            assertEquals(UringProbeState.OPERATIONS_UNSUPPORTED, discovery.report.state)
            assertEquals(native, discovery.report.nativeCapabilities)
            assertNull(discovery.backend)
            assertEquals(0, module.submits)
            assertEquals(1, module.closes)
        }
        val module = ModuleFixture(fileOperations and UringOp.WRITE.mask.inv())
        val discovery = probeUringBackend(linux, "fixture", module)
        assertEquals(UringProbeState.PROBE_FAILED, discovery.report.state)
        assertNull(discovery.backend)
        assertEquals(0, module.submits)
        assertEquals(1, module.closes)
    }

    @Test
    fun probeExceptionFixtures() {
        val capabilityModule = ModuleFixture(fileOperations, capabilityFailure = IllegalStateException("capability fixture"))
        val capability = probeUringBackend(linux, "fixture", capabilityModule)
        assertEquals(UringProbeState.PROBE_FAILED, capability.report.state)
        assertEquals(UringProbePhase.OPERATIONS, capability.report.phase)
        assertNull(capability.report.nativeCapabilities)
        assertEquals("capability fixture", capability.report.detail)
        assertNull(capability.backend)
        assertEquals(0, capabilityModule.submits)
        assertEquals(1, capabilityModule.closes)

        val submissionModule = ModuleFixture(fileOperations, submissionFailure = IllegalStateException("submission fixture"))
        val submission = probeUringBackend(linux, "fixture", submissionModule)
        assertEquals(UringProbeState.PROBE_FAILED, submission.report.state)
        assertEquals(UringProbePhase.EXECUTION, submission.report.phase)
        assertEquals(fileOperations, submission.report.nativeCapabilities)
        assertEquals("submission fixture", submission.report.detail)
        assertNull(submission.backend)
        assertEquals(1, submissionModule.submits)
        assertEquals(1, submissionModule.closes)
    }

    @Test
    fun cleanupFailureFixture() {
        val module = ModuleFixture(fileOperations, result = -13,
            closeFailure = IllegalStateException("close fixture"))
        val discovery = probeUringBackend(linux, "fixture", module)
        assertEquals(UringProbeState.SETUP_DENIED, discovery.report.state)
        assertEquals(UringProbePhase.EXECUTION, discovery.report.phase)
        assertEquals(13, discovery.report.errno)
        assertEquals(fileOperations, discovery.report.nativeCapabilities)
        assertTrue(discovery.report.detail.contains("close fixture"))
        assertNull(discovery.backend)
        assertEquals(1, module.closes)
    }
}
