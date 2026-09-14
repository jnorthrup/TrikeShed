package borg.trikeshed.forge.server

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.ontology.SumoClassifier
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BlackboardNeighborsWireTest {
    @Test fun routeUsesTheWholeRevisionAndKeepsTheMiddleCorpus() = runTest {
        val board = ConfixBlackboard.empty()
        board.put("listing/a&b", "Engineer", "source")
        board.put("profile/c", "Developer", "user")
        val middle = SumoClassifier.parse("(subclass Engineer SoftwareRole) (subclass Developer SoftwareRole) (subclass SoftwareRole Entity)")
        val wire = BlackboardWire(board, backgroundScope) { middle }
        suspend fun query(): Map<*, *> {
            val response = wire.route("GET", "/blackboard/neighbors?key=listing%2Fa%26b", "")!!
            assertEquals(200, response.status)
            return JsonSupport.parse(response.body) as Map<*, *>
        }
        val first = query()
        assertEquals("middle", first["corpus"])
        assertEquals(2, (first["indexedKeys"] as Number).toInt())
        assertEquals(board.snapshot().revision, (first["revision"] as Number).toLong())
        assertEquals("profile/c", ((first["neighbors"] as List<*>).single() as Map<*, *>)["key"])
        val cached = wire.neighborIndex
        query()
        assertSame(cached, wire.neighborIndex)
        board.remove("profile/c")
        assertEquals(0, (query()["neighbors"] as List<*>).size)
        assertNotSame(cached, wire.neighborIndex)
        assertEquals(400, wire.route("GET", "/blackboard/neighbors", "")?.status)
        assertEquals(400, wire.route("GET", "/blackboard/neighbors?key=listing%2Fa%26b&corpus=other", "")?.status)
        assertEquals(400, wire.route("GET", "/blackboard/neighbors?key=listing%2Fa%26b&corpus=full", "")?.status)
        assertEquals(404, wire.route("GET", "/blackboard/neighbors?key=profile%2Fc", "")?.status)
        assertEquals(409, wire.route("GET", "/blackboard/neighbors?key=listing%2Fa%26b&revision=0", "")?.status)
    }
}
