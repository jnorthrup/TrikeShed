package borg.trikeshed.reactor.openapi

import borg.trikeshed.forge.server.ForgeHostRoutes
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.platform.CommonResources
import borg.trikeshed.platform.text
import kotlinx.coroutines.runBlocking
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * KMFSM-009, the status-code half: "OpenAPI schemas and live route registries
 * fail tests when status codes, paths, fields, or capabilities drift."
 *
 * [ForgeHostSpecParityTest] already gates the PATHS both ways, which is why the
 * route lists agree. It never compared what the routes actually answer, and
 * that gap is not hypothetical: the spec documented `POST /api/invoke` as
 * `200` while both the module and the host fallback have always returned `202`
 * — the marketability audit called it out as finding 10, and it survived
 * precisely because path parity looked green.
 *
 * A spec nobody checks is worse than no spec: it is a confident description of
 * behaviour that does not exist. This test exercises representative routes and
 * insists the live status is one the spec actually promises.
 */
class ForgeHostSpecStatusParityTest {

    /**
     * Routes exercised against the spec, with a body where the route needs one.
     *
     * Deliberately not every route: a generated probe would have to invent
     * request bodies, and a wrong body proves nothing about the happy path. This
     * is the set whose success shape is unambiguous. Adding a row is cheap and
     * is the right response to finding another drift.
     */
    private val probes: List<Triple<String, String, String?>> = listOf(
        Triple("GET", "/api/board", null),
        Triple("POST", "/api/invoke", """{"userId":"t","commands":[]}"""),
        Triple("GET", "/api/cap", null),
    )

    private fun spec(): List<ResolvedOperation> {
        val text = assertNotNull(
            CommonResources.text("openapi/forge-host.openapi.yaml"),
            "spec is baked into the bundle",
        )
        return OpenApiReactorResolver.resolve(OpenApiRawParser.parse(text)).operations
    }

    private fun request(method: String, path: String, body: String?): ByteArray =
        if (body == null) {
            "$method $path HTTP/1.1\r\nHost: t\r\n\r\n".toByteArray(StandardCharsets.UTF_8)
        } else {
            "$method $path HTTP/1.1\r\nHost: t\r\nContent-Type: application/json\r\n\r\n$body"
                .toByteArray(StandardCharsets.UTF_8)
        }

    @Test
    fun everyProbedRouteAnswersWithAStatusTheSpecPromises() = runBlocking {
        val operations = spec()
        // No module attached: this is the HOST tier answering, which is the tier
        // forge-host.openapi.yaml describes. Module-claimed behaviour is gated
        // separately by RouteManifestParityTest and KanbanModuleHttpTest.
        val server = JvmKanbanServer()
        val drift = mutableListOf<String>()

        for ((method, path, body) in probes) {
            val op = operations.firstOrNull { it.method.equals(method, ignoreCase = true) && it.path == path }
            if (op == null) {
                drift += "$method $path is probed here but absent from the spec"
                continue
            }
            val documented = op.responses.map { it.statusCode }.toSet()
            if (documented.isEmpty()) {
                drift += "$method $path documents no response codes at all"
                continue
            }
            val live = server.routeHttp(request(method, path, body)).status
            if (live !in documented) {
                drift += "$method $path returns $live but the spec promises only " +
                    documented.sorted().joinToString(", ")
            }
        }

        assertTrue(
            drift.isEmpty(),
            "OpenAPI status codes disagree with the live server:\n  " + drift.joinToString("\n  "),
        )
    }

    /**
     * The sweep the three hand-picked probes cannot be: every BUILT-IN GET, which
     * needs no invented request body — so there is no excuse for not checking it,
     * and until the resolver stopped reporting every status as 0, no one could.
     *
     * Scoped to [ForgeHostRoutes.BUILT_IN] rather than the whole spec on purpose.
     * The spec also describes routes served by wires — the blackboard and graal
     * families, and `/api/vm` — which a bare [JvmKanbanServer] does not mount, so
     * sweeping everything reports eight 404s that are the harness's fault and not
     * the spec's. [ForgeHostSpecParityTest.everyBuiltInGetRouteIsServed] draws the
     * same line for the same reason.
     */
    @Test
    fun everyBuiltInGetMatchesItsDocumentedStatus() = runBlocking {
        val operations = spec()
        val server = JvmKanbanServer()
        val drift = mutableListOf<String>()
        var checked = 0

        for ((method, path) in ForgeHostRoutes.BUILT_IN) {
            if (method != "GET" || path.endsWith("/events") || '{' in path) continue
            val op = operations.firstOrNull { it.method.equals("GET", ignoreCase = true) && it.path == path }
            if (op == null) {
                drift += "GET $path is a built-in but the spec does not describe it"
                continue
            }
            val documented = op.responses.map { it.statusCode }.filter { it != 0 }.toSet()
            if (documented.isEmpty()) {
                drift += "GET $path documents no usable response code"
                continue
            }
            checked++
            val live = server.routeHttp(request("GET", path, null)).status
            if (live !in documented) {
                drift += "GET $path returns $live but the spec promises only " +
                    documented.sorted().joinToString(", ")
            }
        }

        assertTrue(checked > 0, "the sweep probed nothing — the spec or the filter is wrong")
        assertTrue(
            drift.isEmpty(),
            "$checked built-in GET routes swept; these disagree with the live server:\n  " +
                drift.joinToString("\n  "),
        )
    }

    @Test
    fun theInvokeDriftTheAuditFoundStaysFixed() {
        // A named regression, because this exact cell was wrong in the corpus and
        // a general gate is easy to weaken by quietly deleting a probe row.
        val invoke = spec().firstOrNull { it.method.equals("POST", ignoreCase = true) && it.path == "/api/invoke" }
            ?: fail("/api/invoke vanished from the spec")
        val codes = invoke.responses.map { it.statusCode }.toSet()
        assertTrue(
            202 in codes,
            "POST /api/invoke returns 202 (ForgeRoutes.invokeJson and KanbanModule both do); " +
                "the spec promises $codes",
        )
        assertTrue(
            200 !in codes,
            "the spec still promises 200 for /api/invoke — that was audit finding 10 and it is not what the server does",
        )
    }
}
