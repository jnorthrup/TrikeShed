package borg.trikeshed.reactor.openapi

import borg.trikeshed.forge.server.ForgeHostRoutes
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.platform.CommonResources
import borg.trikeshed.platform.text
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * The OpenAPI sink: the spec is written FROM the routes, so spec ↔ route list ↔ live server must agree.
 * Fails the build when a route is added without the spec (or vice versa).
 */
class ForgeHostSpecParityTest {
    private fun specRoutes(): Set<Pair<String, String>> {
        val text = assertNotNull(CommonResources.text("openapi/forge-host.openapi.yaml"), "spec is baked into the bundle")
        val doc = OpenApiReactorResolver.resolve(OpenApiRawParser.parse(text))
        return doc.operations.map { it.method.uppercase() to it.path }.toSet()
    }

    @Test
    fun specMatchesTheRouteTableBothWays() {
        val spec = specRoutes()
        val table = ForgeHostRoutes.ALL.toSet()
        assertEquals(emptySet(), table - spec, "routes the server has but the spec does not")
        assertEquals(emptySet(), spec - table, "routes the spec promises but the server lacks")
    }

    @Test
    fun neighborsContractExposesSnapshotSelectionAndFailureResponses() {
        val text = assertNotNull(CommonResources.text("openapi/forge-host.openapi.yaml"))
        val doc = OpenApiReactorResolver.resolve(OpenApiRawParser.parse(text))
        val operation = assertNotNull(doc.operationsById["blackboardNeighbors"])
        assertEquals("GET", operation.method.uppercase())
        assertEquals("/blackboard/neighbors", operation.path)
        val parameters = operation.parameters.associateBy { it.name }
        assertEquals(setOf("key", "revision", "limit", "corpus"), parameters.keys)
        assertTrue(parameters.values.all { it.location == "query" })
        assertTrue(parameters.getValue("key").required)
        assertIs<ResolvedSchema.Str>(parameters.getValue("key").schema)
        assertIs<ResolvedSchema.Str>(parameters.getValue("corpus").schema)
        assertEquals("int64", assertIs<ResolvedSchema.Int>(parameters.getValue("revision").schema).format)
        assertIs<ResolvedSchema.Int>(parameters.getValue("limit").schema)
        for (name in listOf("revision", "limit", "corpus")) assertFalse(parameters.getValue(name).required)
        assertEquals(setOf(200, 400, 404, 409), operation.responses.map { it.statusCode }.toSet())
    }

    @Test
    fun everyBuiltInGetRouteIsServed() = runBlocking {
        val server = JvmKanbanServer()
        for ((method, path) in ForgeHostRoutes.BUILT_IN) {
            if (method != "GET" || path.endsWith("/events")) continue   // SSE needs the streaming transport
            val req = "GET $path HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()
            assertNotEquals(404, server.routeHttp(req).status, "built-in $path must not 404")
        }
    }
}
