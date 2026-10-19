package borg.trikeshed.userspace

import borg.trikeshed.lib.j
import borg.trikeshed.lib.get
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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.cancelAndJoin
import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.spi.currentNioCapabilityReport

/** Linux modules below are synthetic fixtures; they do not execute kernel io_uring. */
class NodeUringDiscoveryTest {
    private val linux = HostDescriptor(HostRuntime.NODE_JS, rawOs = "linux", rawArchitecture = "x64", napiVersion = 10)
    private val process: dynamic get() = js("({env:{}})")

    @Test
    fun fixtureEmulationBypassesNativeModuleAccess() {
        val process: dynamic = js("({env:{TRIKESHED_URING_MODE:'emulated'}})")
        val discovery = discoverNodeUringBackend(8, linux, process, { error("Native module access under emulation") })
        assertNull(discovery.backend)
        assertTrue("emulated" in discovery.report.detail)
    }

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
        abi.abiVersion = js("function(){return 1}")
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
        module.size = js("function(fd){return BigInt(7)}")
        val discovery = discover(module)
        // Descriptor metadata is emulated even when this synthetic module claims every opcode.
        assertEquals(UringProbeState.AVAILABLE_LIMITED, discovery.report.state)
        assertTrue(discovery.report.available)
        assertEquals(UringProbePhase.EXECUTION, discovery.report.phase)
        assertEquals(1, module.opened as Int)
        assertEquals(1, module.executed as Int)
        assertEquals(1, module.lastTransportId as Int)
        val backend = assertNotNull(discovery.backend)
        assertEquals(0L, backend.nativeCapabilities and UringOp.STATX.mask)
        val fd = backend.submitBatch(listOf(Submissions.openat("fixture", userData = 12))).single().res
        val stat = Submissions.statx(fd, "", UringOp.AT_EMPTY_PATH, UringOp.STATX_BASIC_STATS, 13)
        assertEquals(0, backend.submitBatch(listOf(stat)).single().res)
        assertEquals(7uL, Statx.of(stat).stx_size)
        assertEquals(0, Statx.of(stat).stx_mode, "size-only adapter does not establish a file kind")
        backend.close()
        assertEquals(1, module.closed as Int)
    }

    @Test
    fun fixtureNativeBatchOrdersDescriptorBarriers() = runTest {
        val module = module()
        val backend = nodeNativeChannelBackend(module, js("BigInt(7)"))
        val fd = backend.submitBatch(listOf(Submissions.openat("fixture", userData = 1))).single().res
        module.batches = js("[]")
        val first = Submissions.write(fd, 0, 2, 0, 2).copy(buffer = ByteBuffer(byteArrayOf(1, 2)))
        val second = Submissions.write(fd, 0, 2, 2, 3).copy(buffer = ByteBuffer(byteArrayOf(3, 4)))
        val submissions = arrayOf(first, second, Submissions.fsync(fd, 4))
        val cqes = backend.batchEnqueue(submissions.size j { submissions[it] })
        assertEquals(3, cqes.a)
        assertEquals(2, module.batches.length as Int)
        assertEquals(2, module.batches[0] as Int)
        assertEquals(1, module.batches[1] as Int)
        assertEquals(2, first.buffer!!.position())
        assertEquals(2, second.buffer!!.position())
        backend.close()
    }

    @Test
    fun fixtureNativeWakeupCancellationRetainsAdmission() = runTest {
        val module = module()
        module.reapCompletions = js("function(){if(!this.released)return [];var q=this.cq;this.cq=[];return q}")
        module.awaitCompletions = js("function(){var self=this;this.waits=(this.waits||0)+1;return new Promise(function(resolve){self.wake=resolve})}")
        module.cancelWait = js("function(){this.cancelled=(this.cancelled||0)+1;if(this.wake)this.wake()}")
        val backend = nodeNativeChannelBackend(module, js("BigInt(7)"))
        assertTrue(backend.submitBatch(listOf(Submissions.nop(99))).isEmpty())
        val waiter = launch { backend.awaitCompletions() }
        yield()
        waiter.cancelAndJoin()
        assertEquals(1, module.cancelled as Int)
        assertTrue(backend.reapCompletions().isEmpty(), "Cancelling readiness does not cancel admitted effects")
        module.released = true
        val cqes = backend.reapCompletions()
        assertEquals(99L, cqes.single().userData)
        assertEquals(0, cqes.single().res)
        backend.close()
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
    fun actualEmulatedSynchronousExecuteKeepsCompatibilityCompletion() {
        if (loadPlatformHost().descriptor.runtime != HostRuntime.NODE_JS) return
        val filesystem: dynamic = fs ?: return
        val directory = filesystem.mkdtempSync((os.tmpdir() as String) + "/trikeshed-uring-sync-") as String
        val facade = FunctionalUringFacade(4, JsUserspaceChannelBackend())
        try {
            val fd = facade.execute(Submissions.openat("$directory/probe", 2 or 64 or 128, 1))
            assertTrue(fd >= 0)
            val bytes = ByteBuffer(byteArrayOf(9, 8))
            assertEquals(2, facade.execute(Submissions.write(fd, 0, 2, 0, 2).copy(buffer = bytes)))
            assertEquals(2, bytes.position())
            assertEquals(0, facade.execute(Submissions.close(fd, 3)))
        } finally {
            try { facade.closeNow() }
            finally { filesystem.rmSync(directory, js("({recursive:true,force:true})")) }
        }
    }

    @Test
    fun actualEmulatedRawCloseCannotReleaseAnActiveCallbackDescriptor() = runTest {
        if (loadPlatformHost().descriptor.runtime != HostRuntime.NODE_JS) return@runTest
        val filesystem: dynamic = fs ?: return@runTest
        val directory = filesystem.mkdtempSync((os.tmpdir() as String) + "/trikeshed-uring-borrow-") as String
        val backend = JsUserspaceChannelBackend()
        var deferred = false
        try {
            val fd = backend.submitBatch(listOf(Submissions.openat("$directory/probe", 2 or 64 or 128, 1))).single().res
            assertTrue(fd >= 0)
            val write = Submissions.write(fd, 0, 2, 0, 2).copy(buffer = ByteBuffer(byteArrayOf(9, 8)))
            assertTrue(backend.submitDeferredBatch(listOf(write)).isEmpty())
            deferred = true
            assertEquals(-16, backend.submitBatch(listOf(Submissions.close(fd, 3))).single().res)
            assertTrue(FileImpl(fd).isOpen())
            backend.awaitCompletions()
            assertEquals(2, backend.reapCompletions().single().res)
            deferred = false
            assertEquals(0, backend.submitBatch(listOf(Submissions.close(fd, 4))).single().res)
        } finally {
            if (deferred) { backend.awaitCompletions(); backend.reapCompletions() }
            try { backend.close() }
            finally { filesystem.rmSync(directory, js("({recursive:true,force:true})")) }
        }
    }

    @Test
    fun actualSelectedBackendFileOperations() = runTest {
        if (loadPlatformHost().descriptor.runtime != HostRuntime.NODE_JS) return@runTest
        val filesystem: dynamic = fs ?: return@runTest
        val directory = filesystem.mkdtempSync((os.tmpdir() as String) + "/trikeshed-uring-node-") as String
        val path = "$directory/probe.bin"
        val backend = openUserspaceChannelBackend(8)
        val facade = FunctionalUringFacade(8, backend)
        suspend fun complete(submission: UringOp.Companion.UringSubmission): Int {
            val completion = facade.batchEnqueue(1 j { submission })[0]
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
            val stat = Submissions.statx(fd, "", UringOp.AT_EMPTY_PATH, UringOp.STATX_BASIC_STATS, 60)
            assertEquals(0, complete(stat))
            assertEquals(3uL, Statx.of(stat).stx_size)
            assertEquals(UringOp.S_IFREG, Statx.of(stat).stx_mode and UringOp.S_IFMT)
            assertEquals(-22, complete(stat.copy(buffer = ByteBuffer(1 + UringOp.STATX_SIZE).asReadOnlyBuffer())))
            assertEquals(-22, complete(stat.copy(buffer = ByteBuffer(UringOp.STATX_SIZE))))
            assertEquals(-22, complete(stat.copy(buffer = ByteBuffer(1 + UringOp.STATX_SIZE), addr = 1)))
            assertEquals(0, complete(Submissions.close(fd, 6)))
            println("Node selected backend: OPENAT/WRITE/FSYNC/READ/EOF/STATX/CLOSE passed; ${backend.availability}")
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
        opened:0, closed:0, executed:0, cq:[], batches:[],
        abiVersion:function(){return 2}, platform:function(){return 'linux'},
        architecture:function(){return 'x86_64'}, napiVersion:function(){return 8},
        open:function(){this.opened++;return BigInt(7)}, supports:function(){return true},
        execute:function(handle,opcode,fd,bytes,start,length,offset,userData){
            this.executed++;this.lastOpcode=opcode;this.lastUserData=userData;return 0
        },
        submitBatch:function(handle,count,control,arrays,results){
            this.batches.push(count);
            for(var i=0;i<count;i++){
                this.executed++;this.lastOpcode=control[i*6];this.lastTransportId=control[i*6+5];
                results[i]=0;this.cq.push(control[i*6+5],control[i*6]===22||control[i*6]===23?control[i*6+3]:0)
            }
            return 0
        },
        reapCompletions:function(){var cq=this.cq;this.cq=[];return cq},
        awaitCompletions:function(){return Promise.resolve()},cancelWait:function(){},cancelPending:function(){},
        close:function(){this.closed++;return 0}
    })""")
}
