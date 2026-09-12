package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.platform.HostDescriptor
import borg.trikeshed.platform.HostRuntime
import borg.trikeshed.platform.loadPlatformHost
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.spi.currentNioCapabilityReport
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.Assume.assumeFalse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UringJvmDiscoveryTest {
    @Test
    fun requestedModeBypassesSetupOrRequiresNativeExecution() {
        val priorMode = System.getProperty("trikeshed.uring.mode")
        val priorLibrary = System.getProperty("trikeshed.uring.library")
        try {
            System.setProperty("trikeshed.uring.library", "/missing/trikeshed-uring-library")
            System.setProperty("trikeshed.uring.mode", "emulated")
            val discovery = discoverJvmUringBackend(2)
            assertNull(discovery.backend)
            assertTrue(discovery.report.description.contains("native setup bypassed"))
            val backend = openUserspaceChannelBackend(2)
            try {
                assertEquals(jvmUringOperations, backend.capabilities)
                assertEquals(0L, backend.nativeCapabilities)
            } finally { backend.close() }
            System.setProperty("trikeshed.uring.mode", "native")
            assertFailsWith<IllegalStateException> { discoverJvmUringBackend(2) }
            System.setProperty("trikeshed.uring.mode", "invalid")
            assertFailsWith<IllegalArgumentException> { discoverJvmUringBackend(2) }
        } finally {
            if (priorMode == null) System.clearProperty("trikeshed.uring.mode") else System.setProperty("trikeshed.uring.mode", priorMode)
            if (priorLibrary == null) System.clearProperty("trikeshed.uring.library") else System.setProperty("trikeshed.uring.library", priorLibrary)
        }
    }

    @Test
    fun linkageFailureFixture() {
        val failure = UnsatisfiedLinkError("simulated missing JNI submission symbol")
        var closes = 0
        val module = object : UserspaceChannelBackend {
            override val capabilities = UringOp.caps(UringOp.NOP, UringOp.OPENAT, UringOp.READ,
                UringOp.WRITE, UringOp.FSYNC, UringOp.FTRUNCATE, UringOp.CLOSE)
            override val nativeCapabilities = capabilities
            override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> = throw failure
            override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> = throw failure
            override fun close() { closes++ }
        }
        val thrown = assertFailsWith<UnsatisfiedLinkError> {
            probeUringBackend(HostDescriptor(HostRuntime.JVM, rawOs = "Linux", rawArchitecture = "aarch64"),
                "fixture", module)
        }
        assertSame(failure, thrown)
        assertEquals(1, closes)
    }

    @Test
    fun nonLinuxRuntimeSelection() {
        val host = loadPlatformHost().descriptor
        assumeFalse("This real-runtime check covers non-Linux native unavailability", host.os == "linux")
        val expectedState = if (host.os == null) UringProbeState.HOST_UNKNOWN else UringProbeState.HOST_UNSUPPORTED
        val discovery = discoverJvmUringBackend(2)
        assertEquals(host, discovery.report.host)
        assertEquals(expectedState, discovery.report.state)
        assertNull(discovery.report.nativeCapabilities)
        assertNull(discovery.report.errno)
        assertNull(discovery.backend)

        val selected = openUserspaceChannelBackend(2)
        val facade = FunctionalUringFacade(2, selected)
        val path = Path.of(System.getProperty("java.io.tmpdir"), "trikeshed-uring-host-${UUID.randomUUID()}")
        fun completion(submission: UringSubmission): Int {
            facade.enqueue(submission)
            assertEquals(1, facade.submit())
            val completed = facade.peek().single()
            assertEquals(submission.userData, completed.userData)
            return completed.res
        }
        try {
            assertEquals(host, selected.probeReport?.host)
            assertEquals(expectedState, selected.probeReport?.state)
            assertEquals(0L, selected.nativeCapabilities)
            assertTrue(selected.capabilities and UringOp.NOP.mask != 0L)
            assertEquals(0, completion(UringOp.Companion.Submissions.nop(41)))
            val fd = completion(UringOp.Companion.Submissions.openat(path.toString(),
                flags = 2 or 64 or 128, userData = 42))
            assertTrue(fd >= 0, "OPENAT completion: $fd")
            val payload = "selected uring file".encodeToByteArray()
            assertEquals(payload.size, completion(UringOp.Companion.Submissions.write(fd, 0,
                payload.size, 0, 43).copy(buffer = ByteBuffer(payload))))
            val read = ByteBuffer(payload.size)
            assertEquals(payload.size, completion(UringOp.Companion.Submissions.read(fd, 0,
                payload.size, 0, 44).copy(buffer = read)))
            assertContentEquals(payload, read.array())
            assertEquals(0, completion(UringOp.Companion.Submissions.fsync(fd, 45)))
            assertEquals(0, completion(UringOp.Companion.Submissions.close(fd, 46)))
        } finally {
            try { facade.closeNow() } finally { Files.deleteIfExists(path) }
        }

        val capability = currentNioCapabilityReport()
        assertEquals("uring_emulated", capability.backendName)
        assertFalse(capability.ioUringAvailable)
        assertEquals(host, capability.uringProbe?.host)
        assertEquals(expectedState, capability.uringProbe?.state)
        assertEquals("", capability.kernelHint)
        assertTrue("nop" in capability.capabilities)
        println("JVM native discovery: ${discovery.report}")
        println("JVM canonical selection: ${capability.toReadable()}")
        println("JVM canonical facade: NOP, OPENAT, WRITE, READ, FSYNC, CLOSE completed; disposable file removed")
    }
}
