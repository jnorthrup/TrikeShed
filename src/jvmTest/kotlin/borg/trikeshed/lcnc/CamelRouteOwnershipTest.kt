package borg.trikeshed.lcnc

import borg.trikeshed.graal.subvm.CamelRuntime
import kotlinx.coroutines.*
import java.util.UUID
import kotlin.test.*

/** Registered daemon runners must not adopt or stop another owner's live context. */
class CamelRouteOwnershipTest {
    @Test
    fun downAllStopsOnlyRoutesStartedThroughThisRegistration() = runBlocking {
        fixture { ctx, foreign, owned ->
            val out = ctx.lcncRunners.getValue(CamelRouteLegos.DOWN).run(
                LcncNode("down", CamelRouteLegos.DOWN, mapOf("all" to "true")), emptyMap())
            assertEquals(listOf(owned), out["stopped"])
            assertEquals(true, out["ok"], out["error"].toString())
            assertTrue(CamelRuntime.isRunning(foreign), "DocumentFeed/other owners keep their own routes")
            assertEquals("borrowed-live", CamelRuntime.send(foreign, "borrowed-live"))
            assertNull(ctx.blackboard.get(CamelRouteLegos.routeKey(foreign)))
        }
    }

    @Test
    fun explicitDownCannotStopABorrowedRoute() = runBlocking {
        fixture { ctx, foreign, _ ->
            val out = ctx.lcncRunners.getValue(CamelRouteLegos.DOWN).run(
                LcncNode("down", CamelRouteLegos.DOWN, mapOf("id" to foreign)), emptyMap())
            assertEquals(false, out["ok"], "Naming a borrowed route does not transfer its lifetime")
            assertTrue(CamelRuntime.isRunning(foreign))
            assertNull(ctx.blackboard.get(CamelRouteLegos.routeKey(foreign)))
        }
    }

    @Test
    fun exactReplayCannotAdoptABorrowedRoute() = runBlocking {
        fixture { ctx, foreign, _ ->
            val retained = CamelRuntime.route(foreign)
            val error = runCatching {
                ctx.lcncRunners.getValue(CamelRouteLegos.UP).run(
                    LcncNode(foreign, CamelRouteLegos.UP, mapOf(
                        "id" to foreign, "from" to "direct:$foreign", "to" to "mock:$foreign")), emptyMap())
            }
            assertTrue(error.isFailure || error.getOrThrow()["ok"] == false,
                "Identity equality is not ownership; replay must refuse another owner's context")
            assertSame(retained, CamelRuntime.route(foreign))
        }
    }

    private suspend fun fixture(body: suspend (borg.trikeshed.module.ModuleContext, String, String) -> Unit) {
        val parent = SupervisorJob(currentCoroutineContext()[Job])
        val ctx = vmRuntimeTestContext(CoroutineScope(currentCoroutineContext().minusKey(Job) + parent + Dispatchers.Default))
        val registration = CamelRouteLegos.register(ctx)
        val suffix = UUID.randomUUID().toString()
        val foreign = "foreign-$suffix"
        val owned = "owned-$suffix"
        CamelRuntime.start(foreign, "direct:$foreign", "mock:$foreign")
        try {
            val up = ctx.lcncRunners.getValue(CamelRouteLegos.UP).run(
                LcncNode(owned, CamelRouteLegos.UP, mapOf(
                    "id" to owned, "from" to "direct:$owned", "to" to "mock:$owned")), emptyMap())
            assertEquals(true, up["ok"], up["error"].toString())
            body(ctx, foreign, owned)
        } finally {
            registration.drain()
            CamelRuntime.stop(owned)
            CamelRuntime.stop(foreign)
            parent.cancelAndJoin()
        }
    }
}
