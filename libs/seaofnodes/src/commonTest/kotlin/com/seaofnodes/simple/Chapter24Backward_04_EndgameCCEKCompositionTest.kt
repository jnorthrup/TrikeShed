package com.seaofnodes.simple

import borg.literbike.ccek.core.Context
import borg.literbike.ccek.core.Element
import borg.literbike.ccek.core.Key
import borg.literbike.endgame.EndgameCapabilities
import borg.literbike.endgame.FeatureGates
import borg.literbike.endgame.SimdLevel
import borg.literbike.reactor.ReactorConfig
import borg.literbike.reactor.ReactorService
import com.seaofnodes.simple.ccek.DagNode
import com.seaofnodes.simple.ccek.LexerElement
import com.seaofnodes.simple.ccek.LexerKey
import com.seaofnodes.simple.ccek.ParserElement
import com.seaofnodes.simple.ccek.ParserKey
import com.seaofnodes.simple.ccek.compilerContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Endgame CCEK composition: the registry elevation midpoint.
 *
 * Architecture target: `borg.ccek.registry.*` becomes its own Gradle module.
 * literbike's `Context` (CoW chain) is the prototype. The elevated registry
 * generalizes it into a service locator that all subsystems (reactor, endgame,
 * compiler pipeline, WAM, transport) register into — with no subsystem owning it.
 *
 * TrikeShed types (Join, Series, Manifold, Cursor) are pure algebra; they do
 * NOT import the registry. The registry wraps them as ServiceElements.
 */
class Chapter24Backward_04_EndgameCCEKCompositionTest {
    // RED: borg.ccek.registry.Registry does not exist — target is its own Gradle module
    private fun createRegistry(): borg.ccek.registry.Registry =
        borg.ccek.registry.Registry
            .create()

    @Test
    fun `compiler CCEK pipeline is the prototype that the elevated registry generalizes`() {
        // The existing compiler context uses literbike's CoW Context directly.
        // This test proves the prototype works — the registry must subsume it.
        val ctx =
            compilerContext()
                .plus(LexerKey, LexerElement(LexerKey, emptyList()))
                .plus(
                    ParserKey,
                    ParserElement(
                        ParserKey,
                        listOf(
                            DagNode(0, "Start", emptyList(), emptyList(), "tuple"),
                            DagNode(1, "Con", emptyList(), listOf(0), "int"),
                        ),
                    ),
                )

        val parser = ctx.get<ParserElement>()
        assertNotNull(parser)
        assertEquals(2, parser!!.dagNodes.size)

        // RED: Registry does not exist to receive the compiler context as a subsystem
        val registry = createRegistry()
        registry.registerSubsystem("compiler", ctx)
        val retrieved: Context? = registry.lookupSubsystem("compiler")
        assertNotNull(retrieved)
    }

    @Test
    fun `ReactorService and EndgameCapabilities register as service elements`() {
        val reactor = ReactorService(ReactorConfig())
        val caps =
            EndgameCapabilities(
                ioUringAvailable = true,
                ebpfCapable = false,
                kernelModuleLoaded = false,
                simdLevel = SimdLevel.Avx2,
                featureGates = FeatureGates(),
            )

        // RED: Registry, RegistryKey, ServiceElement do not exist
        val registry = createRegistry()
        registry.register(borg.ccek.registry.RegistryKey("reactor"), reactor)
        registry.register(borg.ccek.registry.RegistryKey("endgame"), caps)

        val r: ReactorService? = registry.lookup(borg.ccek.registry.RegistryKey("reactor"))
        val e: EndgameCapabilities? = registry.lookup(borg.ccek.registry.RegistryKey("endgame"))
        assertNotNull(r)
        assertNotNull(e)
        assertTrue(e!!.ioUringAvailable)
    }

    @Test
    fun `two context systems must unify — literbike CoW chain versus Kotlin CoroutineContext`() {
        // literbike's Context is a sealed class (Empty/Cons) — NOT CoroutineContext.
        // TrikeShed's ChannelizationService.Key implements CoroutineContext.Key.
        // The registry must bridge both, or pick one.
        val literbikeCtx: Context =
            compilerContext()
                .plus(LexerKey, LexerElement(LexerKey, emptyList()))
        assertTrue(literbikeCtx.isNotEmpty())

        // RED: No bridge exists. Registry.toCoroutineContext() would unify both systems.
        val registry = createRegistry()
        registry.registerSubsystem("compiler", literbikeCtx)
        val coroutineCtx: kotlin.coroutines.CoroutineContext = registry.toCoroutineContext("compiler")
        assertTrue(coroutineCtx.isNotEmpty())
    }

    @Test
    fun `WAM table service and handler registry install into the service locator`() {
        // RED: WamTableService does not exist; HandlerRegistry is in TrikeShed but the
        // registry wrapping it as ServiceElement does not exist
        val registry = createRegistry()

        val wamKey = borg.ccek.registry.RegistryKey("wam")
        val handlerKey = borg.ccek.registry.RegistryKey("handlers")

        registry.register(wamKey, WamTableService())
        registry.register(handlerKey, HandlerRegistry())

        val wam: WamTableService? = registry.lookup(wamKey)
        val handlers: HandlerRegistry? = registry.lookup(handlerKey)
        assertNotNull(wam)
        assertNotNull(handlers)
    }

    @Test
    fun `CouchDB store manages compiler intermediaries as documents`() {
        // Compiler DAG nodes, token streams, and codegen output should be stored
        // as CouchDB documents instead of .o files. The registry provides the store.
        val dag = DagNode(0, "Add", listOf(1, 2), listOf(0), "int")

        // RED: CouchDbStore does not exist in borg.ccek.registry
        val store =
            borg.ccek.registry.CouchDbStore
                .inMemory()
        val docId = store.putDagNode("pipeline-001", dag)
        assertNotNull(docId)

        val retrieved: DagNode? = store.getDagNode(docId)
        assertNotNull(retrieved)
        assertEquals("Add", retrieved!!.label)
        assertEquals(listOf(1, 2), retrieved.inputs)
    }

    @Test
    fun `ChannelizationService registers via CoroutineContext Key bridge`() {
        // TrikeShed's ChannelizationService has companion Key : CoroutineContext.Key
        // The registry must accept CoroutineContext.Element-based services alongside
        // literbike Element-based services.

        // RED: ChannelizationService.Key exists in TrikeShed but the registry's
        // acceptCoroutineElement bridge does not exist
        val registry = createRegistry()
        val channelization = FakeChannelizationService()
        registry.acceptCoroutineElement(
            borg.trikeshed.net.channelization.ChannelizationService.Key,
            channelization,
        )
        val looked: Any? =
            registry.lookupByCoroutineKey(
                borg.trikeshed.net.channelization.ChannelizationService.Key,
            )
        assertNotNull(looked)
    }
}

// RED: borg.ccek.registry.* does not exist — entire package is the target
private object WamTableService {
    fun lookup(clause: String): List<String> = emptyList()
}

private object HandlerRegistry {
    fun keys(): Set<String> = emptySet()
}

// RED: stands in for TrikeShed's ChannelizationService which has CoroutineContext.Key
private class FakeChannelizationService : kotlin.coroutines.CoroutineContext.Element {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<FakeChannelizationService>

    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = Key
}
