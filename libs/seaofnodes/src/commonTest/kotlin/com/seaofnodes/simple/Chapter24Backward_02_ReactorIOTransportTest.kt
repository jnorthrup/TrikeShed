package com.seaofnodes.simple

import borg.literbike.couchdb.DatabaseInstance
import borg.literbike.couchdb.Document
import borg.literbike.endgame.EndgameCapabilities
import borg.literbike.endgame.FeatureGates
import borg.literbike.endgame.ProcessingPath
import borg.literbike.reactor.ContextElement
import borg.literbike.reactor.ManualSelector
import borg.literbike.reactor.Reactor
import borg.literbike.reactor.ReactorConfig
import borg.literbike.reactor.ReactorService
import borg.literbike.reactor.ReactorTickResult
import borg.trikeshed.ccek.KeyedService
import borg.trikeshed.net.channelization.ChannelGraphId
import borg.trikeshed.net.channelization.ChannelGraphState
import borg.trikeshed.net.channelization.ChannelizationPlan
import borg.trikeshed.net.channelization.SimpleChannelGraph
import borg.trikeshed.net.channelization.WorkerKey
import borg.trikeshed.net.channelization.activateGraphJobs
import borg.trikeshed.net.spi.TransportBackendKind
import com.seaofnodes.simple.ccek.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Chapter24Backward_02_ReactorIOTransportTest {
    @Test
    fun `ReactorService should implement KeyedService not string-keyed ContextElement`() {
        val service = ReactorService(ReactorConfig())
        assertIs<ContextElement>(service)

        // RED: ReactorService extends ContextElement (string-keyed), not KeyedService
        val keyed: KeyedService = service as KeyedService
        assertNotNull(keyed.key)
    }

    @Test
    fun `EndgameCapabilities ProcessingPath maps to ChannelizationPlan backendKind`() {
        val caps =
            EndgameCapabilities(
                ioUringAvailable = true,
                ebpfCapable = true,
                kernelModuleLoaded = false,
                simdLevel = borg.literbike.endgame.SimdLevel.Avx2,
                featureGates = FeatureGates(ioUringNative = true, ebpfOffload = true),
            )
        assertEquals(ProcessingPath.EbpfIoUring, caps.selectOptimalPath())

        // RED: no bridge function from ProcessingPath to TransportBackendKind/ChannelizationPlan
        val backend: TransportBackendKind = caps.selectOptimalPath().toTransportBackendKind()
        assertEquals(TransportBackendKind.LINUX_NATIVE, backend)
    }

    @Test
    fun `Reactor tick result handler count matches ChannelJob activation count`() {
        val reactor = Reactor.manual(ReactorConfig(selectTimeoutMs = 10))
        val tick: ReactorTickResult = reactor.runOnce().getOrThrow()
        assertEquals(0, tick.handlerCallbacks)

        val graph =
            SimpleChannelGraph(
                id = ChannelGraphId("reactor-test"),
                owner = WorkerKey("worker-1"),
                activationRules = emptyList(),
            )
        graph.transitionTo(ChannelGraphState.Active)

        // RED: no bridge between reactor handler count and channel graph job activation count
        val jobs = activateGraphJobs(graph)
        assertEquals(tick.handlerCallbacks, jobs.size)
    }

    @Test
    fun `Compiler codegen bytes stored in CouchDB instead of filesystem intermediaries`() {
        val machineCode = byteArrayOf(0xB8, 0x01, 0x00, 0x00, 0x00, 0xC3)
        val codeGenEl = CodeGenElement(CodeGenKey, machineCode)
        val sourceHash = codeGenEl.machineCode.contentHashCode().toString(16)

        val db = DatabaseInstance(name = "compiler_codegen")
        val doc =
            Document(
                id = "codegen:$sourceHash",
                rev = "",
                data =
                    kotlinx.serialization.json.buildJsonObject {
                        put("phase", kotlinx.serialization.json.JsonPrimitive("codegen"))
                        put("sourceHash", kotlinx.serialization.json.JsonPrimitive(sourceHash))
                    },
            )
        val putResult = db.putDocument(doc)
        assertTrue(putResult.isSuccess)

        // RED: no round-trip verification from CodeGenElement to CouchDB Document exists
        val retrieved = db.getDocument("codegen:$sourceHash")
        assertTrue(retrieved.isSuccess)
        val storedBytes =
            retrieved
                .getOrNull()
                ?.toCodeGenElement()
        assertEquals(machineCode.toList(), storedBytes?.machineCode?.toList())
    }

    @Test
    fun `io_uring availability drives LINUX_NATIVE channelization with low cost`() {
        val caps =
            EndgameCapabilities(
                ioUringAvailable = true,
                ebpfCapable = false,
                kernelModuleLoaded = false,
                simdLevel = borg.literbike.endgame.SimdLevel.None,
                featureGates = FeatureGates(ioUringNative = true),
            )
        assertEquals(ProcessingPath.IoUringUserspace, caps.selectOptimalPath())

        // RED: no endgame-to-channelization bridge selecting plan from capabilities
        val plan: ChannelizationPlan = caps.toChannelizationPlan()
        assertEquals(TransportBackendKind.LINUX_NATIVE, plan.backendKind)
        assertTrue(plan.estimatedCost < 15)
    }
}

private fun ProcessingPath.toTransportBackendKind(): TransportBackendKind = TODO()

private fun EndgameCapabilities.toChannelizationPlan(): ChannelizationPlan = TODO()

private fun Document.toCodeGenElement(): CodeGenElement = TODO()
