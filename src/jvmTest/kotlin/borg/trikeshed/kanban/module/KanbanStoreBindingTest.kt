package borg.trikeshed.kanban.module

import borg.trikeshed.context.ElementState
import borg.trikeshed.couch.Couch
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.dag.ReteProductionRegistry
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.CasStore
import borg.trikeshed.kanban.BoardStoreElement
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncServiceBinding
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.module.ModuleHandle
import borg.trikeshed.module.ModuleRouteRegistry
import borg.trikeshed.narsese.BeliefBagElement
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class KanbanStoreBindingTest {
    @Test
    fun moduleAttentionAndBoardGarnishResolveCallerStore() = runBlocking {
        val stateDir = Files.createTempDirectory("kanban-store-binding-").toFile()
        val cas = CasStore.inMemory()
        val couchStore = CouchStoreFactory.casBacked(cas)
        val bag = BeliefBagElement(capacity = 16)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val caller = BoardStoreElement(null, CasStore.inMemory())
        val ctx = ModuleContext(
            couch = Couch("kanban-store-binding", couchStore, cas),
            rete = ReteNetwork(),
            productions = ReteProductionRegistry(),
            beliefBag = bag,
            turnReview = null,
            blackboard = ConfixBlackboard.empty(),
            casStore = cas,
            attachments = CouchAttachmentGateway(couchStore, cas),
            routes = ModuleRouteRegistry(),
            scope = scope,
            clock = { 1234L },
            stateDir = stateDir,
        )
        var handle: ModuleHandle? = null
        try {
            bag.open()
            caller.open()
            withTimeout(20_000) {
                handle = KanbanModule().open(ctx)
                suspend fun submit(id: String): Map<String, Any?> = ctx.lcncRunners.getValue("kanban.submit").run(
                    LcncNode("submit-$id", "kanban.submit", mapOf("jobId" to id, "title" to "$id card")),
                    emptyMap(),
                )
                assertEquals(true, submit("host")["accepted"])
                val attention = ctx.lcncRunners.getValue("kanban.attention")
                val binding = assertIs<LcncServiceBinding>(attention)
                assertEquals(1, binding.requiredKeys.size)
                assertEquals(1, binding.providedKeys.size)
                assertSame(BoardStoreElement.Key, binding.requiredKeys[0])
                assertSame(BoardStoreElement.Key, binding.providedKeys[0])
                val node = LcncNode("attention", "kanban.attention")
                assertEquals(listOf("host"), attention.run(node, emptyMap())["ordered"])

                withContext(caller) {
                    assertEquals(true, submit("caller")["accepted"])
                    val result = attention.run(node, emptyMap())
                    assertEquals(setOf("caller"), (result["cards"] as Map<*, *>).keys)
                    assertEquals(listOf("caller"), result["ordered"])
                    for ((type, port) in mapOf("board.get" to "json", "board.view" to "board")) {
                        val board = ctx.lcncRunners.getValue(type).run(LcncNode(type, type), emptyMap())[port] as Map<*, *>
                        val item = (board["items"] as List<*>).single() as Map<*, *>
                        assertEquals("caller", item["id"])
                        assertEquals(0f, item["attention"])
                        assertEquals(false, item["contested"])
                    }
                }
                assertEquals(listOf("host"), attention.run(node, emptyMap())["ordered"])
                assertEquals(ElementState.ACTIVE, caller.state)
                assertEquals(ElementState.ACTIVE, bag.state)
                assertEquals(true, withContext(caller) { submit("caller-after") }["accepted"])
            }
        } finally {
            handle?.drain()
            handle?.close()
            caller.close()
            bag.close()
            scope.cancel()
            stateDir.deleteRecursively()
        }
    }
}
