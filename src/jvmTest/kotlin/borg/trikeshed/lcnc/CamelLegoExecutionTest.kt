package borg.trikeshed.lcnc

import borg.trikeshed.graal.subvm.GuestModules
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `vm.camel` executed for real, over a MOUNTED GUEST MODULE.
 *
 * This lego could never have run before, for two independent reasons, and the tests below fail if
 * either comes back:
 *
 *  1. Camel was not a dependency of anything — not `build.gradle.kts`, not the Gradle cache. There
 *     were no classes to resolve. It is now `utils/subvm/camel`, mounted through [VmSpec.module].
 *  2. The script body was Java/Groovy (`new org.apache.camel.impl.DefaultCamelContext()` and an
 *     anonymous `RouteBuilder() { void configure() }` subclass) inside what is a GraalJS guest.
 *     GraalJS cannot parse that, and cannot subclass an abstract Java class at all — hence the
 *     static `RouteBuilder.addRoutes(CamelContext, LambdaRouteBuilder)` seam, whose second
 *     parameter is a functional interface a plain JS function coerces to.
 *
 * The point of asserting on the REPLY rather than on "route up" is that a started CamelContext with
 * a broken route still reports RUNNING; only a message that comes back out proves dispatch.
 */
class CamelLegoExecutionTest {

    private fun requireModule() {
        assertTrue(
            GuestModules.isInstalled("camel"),
            "guest module 'camel' is not installed — run: ./gradlew -p utils/subvm installCamel",
        )
    }

    @Test
    fun camelStartsAContextAndNamesItsRoute() = runTest {
        requireModule()
        val host = borg.trikeshed.vm.HypervisorVmHost()
        try {
            val runner = SubVmLegos.camel(host)
            val node = LcncNode("camel-up", SubVmLegos.CAMEL, params = mapOf("from" to "direct:probe", "to" to "log:probe"))
            val out = runner.run(node, emptyMap())
            val payload = (out["text"] as? String).orEmpty()
            assertTrue(payload.isNotBlank(), "camel lego produced no payload")
            val obj = JsonSupport.parse(payload) as? Map<*, *>
                ?: error("camel lego payload was not a JSON object: $payload")
            assertEquals("Started", obj["status"]?.toString(), "CamelContext should be Started: $payload")
            val routes = obj["routes"] as? List<*> ?: emptyList<Any?>()
            assertEquals(1, routes.size, "exactly the one declared route should exist: $payload")
        } finally {
            host.close()
        }
    }

    @Test
    fun camelCarriesABodyThroughTheRoute() = runTest {
        requireModule()
        val host = borg.trikeshed.vm.HypervisorVmHost()
        try {
            val runner = SubVmLegos.camel(host)
            // A body distinctive enough that an echo of the SOURCE rather than the routed
            // message would be visible.
            val body = "curation-signal-7f3a"
            val node = LcncNode(
                "camel-dispatch", SubVmLegos.CAMEL,
                params = mapOf("from" to "direct:probe", "to" to "log:probe", "body" to body),
            )
            val out = runner.run(node, emptyMap())
            val payload = (out["text"] as? String).orEmpty()
            val obj = JsonSupport.parse(payload) as? Map<*, *>
                ?: error("camel lego payload was not a JSON object: $payload")
            assertEquals(body, obj["reply"]?.toString(), "the body must come back out of the route: $payload")
        } finally {
            host.close()
        }
    }

    @Test
    fun anAbsentModuleFailsAtTheLegoBoundaryNotInsideTheGuest() = runTest {
        // The whole value of checking installation in the lego is the error the operator sees.
        val host = borg.trikeshed.vm.HypervisorVmHost()
        try {
            val runner = SubVmLegos.camel(host)
            val node = LcncNode(
                "camel-missing", SubVmLegos.CAMEL,
                params = mapOf("module" to "definitely-not-installed", "from" to "direct:x", "to" to "log:x"),
            )
            val failure = runCatching { runner.run(node, emptyMap()) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException, "expected IllegalStateException, got $failure")
            val message = failure.message.orEmpty()
            assertTrue("not installed" in message, "message should say what is wrong: $message")
            assertTrue("utils/subvm" in message, "message should say how to fix it: $message")
        } finally {
            host.close()
        }
    }
}
