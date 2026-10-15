package borg.trikeshed.lcnc

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BlackboardSurfaceNodesTest {
    @Test fun neighborsKeepAnEmptyProjectionAndEncodeTheExactInputKey() = runTest {
        val key = "case / water \u6c34 & evidence"
        val projection = mapOf("key" to key, "revision" to 7L, "epoch" to "board",
            "corpus" to "middle", "neighbors" to emptyList<Any?>())
        val requests = mutableListOf<Triple<String, String, Any?>>()
        val runner = SurfaceNodes.registry { method, path, body ->
            requests.add(Triple(method, path, body))
            projection
        }.getValue("blackboard.neighbors")
        val output = runner.run(LcncNode("neighbors", "blackboard.neighbors", params = mapOf("key" to "wrong")),
            mapOf("key" to key))
        assertSame(projection, output["projection"])
        assertEquals(emptyList<Any?>(), output["neighbors"])
        assertEquals(listOf<Triple<String, String, Any?>>(Triple("GET",
            "/blackboard/neighbors?key=case%20%2F%20water%20%E6%B0%B4%20%26%20evidence", null)), requests)
    }

    @Test fun neighborsRefuseMissingMalformedAndErrorResponses() = runTest {
        for (reply in listOf(null, "unavailable", emptyMap<String, Any?>(),
            mapOf("neighbors" to null), mapOf("neighbors" to "[]"),
            mapOf("error" to "snapshot_changed", "neighbors" to emptyList<Any?>()))) {
            val runner = SurfaceNodes.registry { _, _, _ -> reply }.getValue("blackboard.neighbors")
            assertFailsWith<IllegalStateException> {
                runner.run(LcncNode("neighbors", "blackboard.neighbors", params = mapOf("key" to "source")), emptyMap())
            }
        }
    }

    @Test fun aMissingKeyIsRejectedBeforeCallingTheHandler() = runTest {
        var calls = 0
        val runner = SurfaceNodes.registry { _, _, _ -> calls++; emptyMap<String, Any?>() }
            .getValue("blackboard.neighbors")
        assertFailsWith<IllegalArgumentException> {
            runner.run(LcncNode("neighbors", "blackboard.neighbors"), emptyMap())
        }
        assertEquals(0, calls)
    }
}
