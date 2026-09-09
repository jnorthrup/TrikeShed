package borg.trikeshed.userspace

import borg.trikeshed.lib.processObj
import borg.trikeshed.lib.fs
import borg.trikeshed.lib.os
import borg.trikeshed.platform.HostDescriptor
import borg.trikeshed.platform.HostRuntime
import borg.trikeshed.platform.loadPlatformHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals
import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.spi.currentNioCapabilityReport

/** Linux modules below are synthetic fixtures; they do not execute kernel io_uring. */
class NodeUringDiscoveryTest {
    private val linux = HostDescriptor(HostRuntime.NODE_JS, rawOs = "linux", rawArchitecture = "x64", napiVersion = 10)
    private val process: dynamic get() = js("({env:{}})")

    @Test
    fun actualHostProbe() {
        val host = loadPlatformHost().descriptor
        val discovery = discoverNodeUringBackend(8)
        assertEquals(host, discovery.report.host)
        if (host.runtime == HostRuntime.NODE_JS && host.os != "linux") {
            assertEquals(UringProbeState.HOST_UNSUPPORTED, discovery.report.state)
            assertNull(discovery.backend)
            assertNull(discovery.report.nativeCapabilities)
        }
        println("Node uring host probe: ${discovery.report}")
        discovery.backend?.close()
        val nioReport = currentNioCapabilityReport()
        assertEquals(host, nioReport.uringProbe?.host)
        if (host.runtime == HostRuntime.NODE_JS && host.os != "linux") {
            assertEquals(UringProbeState.HOST_UNSUPPORTED, nioReport.uringProbe?.state)
            assertEquals(false, nioReport.ioUringAvailable)
        }
    }

    @Test
    fun fixtureHostConstraintsPrecedeModuleAccess() {
        val require: () -> dynamic = { error("Host constraints must precede module access") }
        assertEquals(UringProbeState.ARCH_UNSUPPORTED,
            discoverNodeUringBackend(8, linux.copy(rawArchitecture = "riscv64"), process, require).report.state)
        assertEquals(UringProbeState.HOST_UNKNOWN,
            discoverNodeUringBackend(8, linux.copy(rawArchitecture = null), process, require).report.state)
        assertEquals(UringProbeState.RUNTIME_RESTRICTED,
            discoverNodeUringBackend(8, linux.copy(runtime = HostRuntime.HOSTED_JS), process, require).report.state)
    }

    @Test
    fun fixtureAbsenceDiffersFromMissingDependency() {
        val absent: dynamic = js("(function(){var r=function(){}; r.resolve=function(){var e=new Error('candidate missing');e.code='MODULE_NOT_FOUND';throw e};return r})()")
        val dependency: dynamic = js("(function(){var r=function(){var e=new Error('dependency missing');e.code='MODULE_NOT_FOUND';throw e};r.resolve=function(){return '/fixture/uring.node'};return r})()")
        assertEquals(UringProbeState.MODULE_ABSENT,
            discoverNodeUringBackend(8, linux, process, { absent }).report.state)
        val failed = discoverNodeUringBackend(8, linux, process, { dependency }).report
        assertEquals(UringProbeState.MODULE_LOAD_FAILED, failed.state)
        assertEquals(UringProbePhase.LOAD, failed.phase)
    }

    @Test
    fun fixtureRestrictedResolver() {
        assertEquals(UringProbeState.RUNTIME_RESTRICTED,
            discoverNodeUringBackend(8, linux, process, { null }).report.state)
        val denied: dynamic = js("(function(){var r=function(){};r.resolve=function(){var e=new Error('permission denied');e.code='ERR_ACCESS_DENIED';throw e};return r})()")
        assertEquals(UringProbeState.RUNTIME_RESTRICTED,
            discoverNodeUringBackend(8, linux, process, { denied }).report.state)
    }

    @Test
    fun fixtureMetadataMismatchNeverOpens() {
        val architecture = module()
        architecture.architecture = js("function(){return 'arm64'}")
        assertEquals(UringProbeState.ARCH_MISMATCH, discover(architecture).report.state)
        assertEquals(0, architecture.opened as Int)
        val abi = module()
        abi.abiVersion = js("function(){return 2}")
        assertEquals(UringProbeState.ABI_MISMATCH, discover(abi).report.state)
        assertEquals(0, abi.opened as Int)
        val napi = module()
        napi.napiVersion = js("function(){return 11}")
        assertEquals(UringProbeState.ABI_MISMATCH, discover(napi).report.state)
        assertEquals(0, napi.opened as Int)
        val missing = module()
        missing.execute = null
        assertEquals(UringProbeState.ABI_MISMATCH, discover(missing).report.state)
        assertEquals(0, missing.opened as Int)
    }

    @Test
    fun fixtureBoundedErrnoDecoding() {
        assertEquals(1, nodeUringErrno(js("BigInt(-1)")))
        assertEquals(4095, nodeUringErrno(js("BigInt(-4095)")))
        assertNull(nodeUringErrno(js("BigInt(-4096)")))
        assertNull(nodeUringErrno(js("BigInt(\"-9007199254740993\")")))
        assertNull(nodeUringErrno(-1))
        assertNull(nodeUringErrno(js("BigInt(0)")))
    }

    @Test
    fun fixtureSetupDenialAndUnsupportedKernel() {
        val denied = module()
        denied.open = js("function(){return BigInt(-13)}")
        assertEquals(UringProbeState.SETUP_DENIED, discover(denied).report.state)
        assertEquals(13, discover(denied).report.errno)
        val kernel = module()
        kernel.open = js("function(){return BigInt(-38)}")
        assertEquals(UringProbeState.KERNEL_UNSUPPORTED, discover(kernel).report.state)
        val malformed = module()
        malformed.open = js("function(){return 7}")
        assertEquals(UringProbeState.PROBE_FAILED, discover(malformed).report.state)
        malformed.open = js("function(){return BigInt(-4096)}")
        val result = discover(malformed).report
        assertEquals(UringProbeState.PROBE_FAILED, result.state)
        assertNull(result.errno)
    }

    @Test
    fun fixtureLimitedSupportExecutesNop() {
        val module = module()
        module.supports = js("function(handle,opcode){return opcode===0}")
        val discovery = discover(module)
        assertEquals(UringProbeState.AVAILABLE_LIMITED, discovery.report.state)
        assertEquals(UringOp.NOP.mask, discovery.report.nativeCapabilities)
        assertEquals(1, module.executed as Int)
        assertEquals(0, module.lastOpcode as Int)
        assertNotNull(discovery.backend).close()
        assertEquals(1, module.closed as Int)
    }

    @Test
    fun fixturePositiveProbeUsesSharedAdapter() {
        val module = module()
        val discovery = discover(module)
        assertEquals(UringProbeState.AVAILABLE, discovery.report.state)
        assertTrue(discovery.report.available)
        assertEquals(UringProbePhase.EXECUTION, discovery.report.phase)
        assertEquals(1, module.opened as Int)
        assertEquals(1, module.executed as Int)
        assertEquals(0x7472696b65L.toString(), module.lastUserData.toString() as String)
        assertNotNull(discovery.backend).close()
        assertEquals(1, module.closed as Int)
    }

    @Test
    fun fixtureNoNopClosesHandle() {
        val module = module()
        module.supports = js("function(){return false}")
        val discovery = discover(module)
        assertEquals(UringProbeState.OPERATIONS_UNSUPPORTED, discovery.report.state)
        assertEquals(0, module.executed as Int)
        assertEquals(1, module.closed as Int)
        assertNull(discovery.backend)
    }

    @Test
    fun fixtureAdapterFailureClosesHandle() {
        val module = module()
        val discovery = discoverNodeUringBackend(8, linux, process, { moduleRequire(module) }) { _, _ ->
            error("Adapter construction failed")
        }
        assertEquals(UringProbeState.PROBE_FAILED, discovery.report.state)
        assertEquals(1, module.opened as Int)
        assertEquals(1, module.closed as Int)
    }

    @Test
    fun fixtureAdapterAndCloseFailuresRemainVisible() {
        val module = module()
        module.close = js("function(){throw new Error('close rejected')}")
        val discovery = discoverNodeUringBackend(8, linux, process, { moduleRequire(module) }) { _, _ ->
            error("Adapter construction failed")
        }
        assertEquals(UringProbeState.PROBE_FAILED, discovery.report.state)
        assertTrue("Adapter construction failed" in discovery.report.detail)
        assertTrue("close rejected" in discovery.report.detail)
    }

    @Test
    fun actualNodeResolverSeesAbsentModule() {
        if (loadPlatformHost().descriptor.runtime != HostRuntime.NODE_JS) return
        val host = linux // Synthetic Linux descriptor; resolution runs in actual Node on the current host.
        val process: dynamic = js("({env:{TRIKESHED_URING_MODULE:'@trikeshed/absent-uring-fixture-01a081d6'}})")
        val result = discoverNodeUringBackend(8, host, process, { nodeUringRequire(processObj) })
        assertEquals(UringProbeState.MODULE_ABSENT, result.report.state)
    }

    @Test
    fun actualSelectedBackendFileOperations() {
        if (loadPlatformHost().descriptor.runtime != HostRuntime.NODE_JS) return
        val filesystem: dynamic = fs ?: return
        val directory = filesystem.mkdtempSync((os.tmpdir() as String) + "/trikeshed-uring-node-") as String
        val path = "$directory/probe.bin"
        val backend = openUserspaceChannelBackend(8)
        val facade = FunctionalUringFacade(8, backend)
        fun complete(submission: UringOp.Companion.UringSubmission): Int {
            facade.enqueue(submission)
            facade.submit()
            val completion = facade.wait().single()
            assertEquals(submission.userData, completion.userData)
            return completion.res
        }
        try {
            if (loadPlatformHost().descriptor.os != "linux") {
                assertEquals(0L, backend.nativeCapabilities)
                assertEquals(UringProbeState.HOST_UNSUPPORTED, backend.probeReport?.state)
            }
            val fd = complete(Submissions.openat(path, 2 or 64 or 128, 1))
            assertTrue(fd >= 0)
            val source = ByteBuffer(byteArrayOf(9, 11, 22, 33, 8), 1, 3)
            assertEquals(3, complete(Submissions.write(fd, 0, 3, 0, 2).copy(buffer = source)))
            assertEquals(3, source.position())
            assertEquals(0, complete(Submissions.fsync(fd, 3)))
            val destination = ByteBuffer(5)
            assertEquals(3, complete(Submissions.read(fd, 0, 5, 0, 4).copy(buffer = destination)))
            assertEquals(3, destination.position())
            assertContentEquals(byteArrayOf(11, 22, 33, 0, 0), destination.array())
            assertEquals(0, complete(Submissions.read(fd, 0, 2, 3, 5).copy(buffer = destination)))
            assertEquals(0, complete(Submissions.close(fd, 6)))
            println("Node selected backend: OPENAT/WRITE/FSYNC/READ/EOF/CLOSE passed; ${backend.availability}")
        } finally {
            try { facade.closeNow() } finally {
                filesystem.rmSync(directory, js("({recursive:true,force:true})"))
            }
        }
    }

    private fun discover(module: dynamic): UringBackendDiscovery =
        discoverNodeUringBackend(8, linux, process, { moduleRequire(module) })

    private fun moduleRequire(value: dynamic): dynamic =
        js("(function(candidate){var r=function(){return candidate};r.resolve=function(){return '/fixture/uring.node'};return r})")(value)

    private fun module(): dynamic = js("""({
        opened:0, closed:0, executed:0,
        abiVersion:function(){return 1}, platform:function(){return 'linux'},
        architecture:function(){return 'x86_64'}, napiVersion:function(){return 8},
        open:function(){this.opened++;return BigInt(7)}, supports:function(){return true},
        execute:function(handle,opcode,fd,bytes,start,length,offset,userData){
            this.executed++;this.lastOpcode=opcode;this.lastUserData=userData;return 0
        },
        close:function(){this.closed++}
    })""")
}
