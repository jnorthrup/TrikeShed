package borg.trikeshed.lcnc

import borg.trikeshed.lcnc.ccek.*
import borg.trikeshed.lib.*
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import kotlinx.coroutines.*
import kotlin.test.*

/** Production Panels, contract hydration and evaluator; isolated from the user's daemon. */
class AssumptionsBrowserTest {
    @Test
    fun rowPresentationIsPublishedByTheCommonContract() {
        val publisher = LcncPublisher(borg.trikeshed.graal.ConfixBlackboard.empty(), { emptyMap() }, null)
        val contracts = publisher.vocabularyPayload()["contracts"] as List<*>
        val literal = contracts.map { it as Map<*, *> }.single { it["type"] == "json.value" }
        val params = literal["params"] as Map<*, *>
        assertEquals("columns", (params["value"] as Map<*, *>)["colsParam"])
        assertEquals(true, (params["columns"] as Map<*, *>)["optional"])
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
    fun editRowsBeforeExplicitRunAndPreserveUnrepresentedValues() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(SystemOperations.default.getenv("TRIKESHED_ASSUMPTIONS_UI") == "1")
        val posts = java.util.concurrent.CopyOnWriteArrayList<Map<*, *>>()
        val responses = java.util.concurrent.CopyOnWriteArrayList<Map<String, Any?>>()
        lateinit var bound: ModuleContext
        val program = LcncProgram("assumption-rows", s_[
            LcncNode("assumptions", "json.value", mapOf("columns" to "subject,obj", "value" to
                """[{"subject":"Delays","obj":"party-a","hypothetical":true,"extra":{"score":0.75}}]"""),
                x = 0.0, y = 0.0, width = 420.0),
            LcncNode("simulation", "nars.reteFire", mapOf("antecedent" to "Delays", "consequent" to "cancellations"),
                x = 470.0, y = 0.0),
            LcncNode("malformed", "json.value", mapOf("columns" to "subject,obj", "value" to "["),
                x = 0.0, y = 410.0, width = 420.0),
            LcncNode("typed", "json.value", mapOf("columns" to "subject,obj", "value" to
                """[{"subject":42,"obj":null,"extra":[false,{"n":2}]}]"""),
                x = 470.0, y = 410.0, width = 420.0),
        ], s_[LcncWire("assumptions", "value", "simulation", "assertions?")],
            view = LcncView(x = 20.0, y = 20.0, zoom = 0.85))
        val binding = ccekReactorBinding(coroutineContext)
        try {
            binding.reactor.open()
            val context = vmRuntimeTestContext(this)
            context.lcncRunners.putAll(PureNodes.registry { 0L })
            retainedSimulationBrowser(context, null, binding, program,
                opened = { bound = it },
                testRoute = { method, path, body ->
                    if (method == "POST" && path == "/api/lcnc/run") {
                        posts += reifyMap(body)
                        val response = requireNotNull(bound.routes.match(path)?.route(method, path, body, null))
                        assertEquals(200, response.status, response.body)
                        responses += reifyMap(response.body)
                        response
                    } else null
                }, observations = { mapOf("posts" to posts.toList(), "responses" to responses.toList()) })
            assertEquals(2, posts.size, "Editing, row removal and JSON repair do not execute; only two explicit Runs")
            val evaluations = responses.map { response ->
                assertEquals(true, response["ok"])
                assertEquals("completed", response["status"])
                assertIs<String>(response["receiptCid"])
                assertIs<Map<*, *>>(assertIs<Map<*, *>>(response["outputs"])["simulation"])
            }
            assertEquals(2, evaluations.size)
            val first = (evaluations[0]["assertions"] as List<*>).map { it as Map<*, *> }
            assertEquals(listOf("party-b", "reviewer"), first.map { it["obj"] })
            assertEquals(true, first[0]["hypothetical"])
            assertEquals(mapOf("score" to 0.75), first[0]["extra"])
            assertEquals(2, (evaluations[0]["firings"] as List<*>).size)
            val firings = (evaluations[0]["firings"] as List<*>).map { it as Map<*, *> }
            assertEquals(2, firings.map { it["firingCid"] }.distinct().size,
                "The retained receipt distinguishes the two party applications")
            assertEquals(emptyList<Any?>(), evaluations[1]["firings"])
            for (post in posts) {
                val document = post["document"] as Map<*, *>
                val nodes = (document["nodes"] as List<*>).map { it as Map<*, *> }
                val typed = nodes.single { it["id"] == "typed" }["params"] as Map<*, *>
                assertEquals(reify(program.nodes.view.single { it.id == "typed" }.params.getValue("value")),
                    reify(typed["value"] as String), "A row view never stringifies numbers/null/hidden fields")
            }
        } finally { withContext(NonCancellable) { binding.reactor.drain() } }
    }
}
