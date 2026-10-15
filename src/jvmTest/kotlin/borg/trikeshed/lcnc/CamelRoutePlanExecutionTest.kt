package borg.trikeshed.lcnc

import borg.trikeshed.graal.subvm.CamelRuntime
import borg.trikeshed.graal.subvm.GuestModules
import borg.trikeshed.lib.*
import java.lang.System
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** Real installed Camel, including its choice scopes and context lifecycle. No guest callbacks. */
class CamelRoutePlanExecutionTest {
    @Test
    fun bodyAndHeadersAreLiteralAndTheReplyContainsTheExecutedPlan() = contexts { observed ->
        val id = id()
        val literal = "'quoted' \"double\" \\ \${body}\nconstant"
        val plan = CamelRoutePlan("direct:$id", s_[
            CamelRouteStep.SetHeader("result", literal),
            CamelRouteStep.SetBody(literal),
            CamelRouteStep.To("mock:$id"),
        ])
        val result = CamelRuntime.execute(id, plan, CamelRuntime.Request("input", mapOf("input" to "kept")))

        assertEquals(setOf("status", "routes", "from", "reply", "headers", "plan"), result.keys)
        assertEquals("Started", result["status"])
        assertEquals(listOf(id), result["routes"])
        assertEquals(plan.from, result["from"])
        assertEquals(literal, result["reply"])
        assertEquals(literal, headers(result)["result"])
        assertEquals("kept", headers(result)["input"])
        assertEquals(plan.toValue(), result["plan"])
        assertEquals(1, observed.size)
        assertStopped(observed.single())
        assertFalse(CamelRuntime.isRunning(id))
    }

    @Test
    fun choiceUsesTheFirstExactBodyMatchAndOtherwise() = contexts { observed ->
        val id = id()
        val literal = "\${body} == 'quoted'"
        val plan = CamelRoutePlan("direct:$id", s_[
            CamelRouteStep.Choice(
                s_[
                    CamelRouteBranch(literal, s_[CamelRouteStep.SetBody("first")]),
                    CamelRouteBranch(literal, s_[CamelRouteStep.SetBody("duplicate")]),
                    CamelRouteBranch("second", s_[CamelRouteStep.SetBody("second-branch")]),
                ],
                s_[CamelRouteStep.SetBody("otherwise")],
            ),
            CamelRouteStep.SetHeader("continued", "yes"),
            CamelRouteStep.To("mock:$id"),
        ])

        for ((body, expected) in s_[literal j "first", "second" j "second-branch", "SECOND" j "otherwise"].view) {
            val result = CamelRuntime.execute(id, plan, CamelRuntime.Request(body))
            assertEquals(expected, result["reply"])
            assertEquals("yes", headers(result)["continued"])
            assertStopped(observed.last())
        }
        assertEquals(3, observed.size)
    }

    @Test
    fun nestedChoicesContinueInsideTheirBranchAndThenOutsideTheirChoice() = contexts { observed ->
        val id = id()
        val nested = CamelRouteStep.Choice(
            s_[CamelRouteBranch("inner", s_[CamelRouteStep.SetBody("inner-when")])],
            s_[CamelRouteStep.SetBody("inner-otherwise")],
        )
        val plan = CamelRoutePlan("direct:$id", s_[
            CamelRouteStep.Choice(
                s_[
                    CamelRouteBranch("outer", s_[
                        CamelRouteStep.SetBody("inner"),
                        nested,
                        CamelRouteStep.SetHeader("outer-when-continued", "yes"),
                        CamelRouteStep.Choice(
                            s_[CamelRouteBranch("inner-when", s_[CamelRouteStep.SetBody("when-continuation")])],
                            s_[CamelRouteStep.SetBody("wrong-inner-scope")],
                        ),
                    ]),
                    CamelRouteBranch("sibling", s_[CamelRouteStep.SetBody("sibling-when")]),
                ],
                s_[
                    nested,
                    CamelRouteStep.SetHeader("outer-otherwise-continued", "yes"),
                    CamelRouteStep.Choice(
                        s_[CamelRouteBranch("inner-otherwise", s_[CamelRouteStep.SetBody("otherwise-continuation")])],
                        s_[CamelRouteStep.SetBody("wrong-otherwise-scope")],
                    ),
                ],
            ),
            CamelRouteStep.Choice(
                s_[
                    CamelRouteBranch("when-continuation", s_[CamelRouteStep.SetBody("when-result")]),
                    CamelRouteBranch("otherwise-continuation", s_[CamelRouteStep.SetBody("otherwise-result")]),
                    CamelRouteBranch("sibling-when", s_[CamelRouteStep.SetBody("sibling-result")]),
                ],
                s_[CamelRouteStep.SetBody("wrong-outer-scope")],
            ),
            CamelRouteStep.SetHeader("route-continued", "yes"),
        ])

        for ((body, expected) in s_["outer" j "when-result", "unmatched" j "otherwise-result", "sibling" j "sibling-result"].view) {
            val result = CamelRuntime.execute(id, plan, CamelRuntime.Request(body))
            assertEquals(expected, result["reply"], "continuation for $body")
            val headers = headers(result)
            assertEquals("yes", headers["route-continued"])
            assertEquals(if (body == "outer") "yes" else null, headers["outer-when-continued"])
            assertEquals(if (body == "unmatched") "yes" else null, headers["outer-otherwise-continued"])
            assertStopped(observed.last())
        }
        assertEquals(3, observed.size)
    }

    @Test
    fun stopInANestedChoiceStopsItsBranchAndTheEnclosingRoute() = contexts { observed ->
        val id = id()
        val plan = CamelRoutePlan("direct:$id", s_[
            CamelRouteStep.Choice(
                s_[CamelRouteBranch("halt", s_[
                    CamelRouteStep.Choice(
                        s_[CamelRouteBranch("halt", s_[
                            CamelRouteStep.SetHeader("stopped", "yes"),
                            CamelRouteStep.SetBody("halted"),
                            CamelRouteStep.Stop,
                            CamelRouteStep.SetBody("after-stop"),
                        ])],
                        s_[CamelRouteStep.SetBody("inner-otherwise")],
                    ),
                    CamelRouteStep.SetHeader("branch-continued", "wrong"),
                ])],
                s_[CamelRouteStep.SetBody("continue")],
            ),
            CamelRouteStep.SetHeader("route-continued", "yes"),
            CamelRouteStep.SetBody("completed"),
        ])
        val halted = CamelRuntime.execute(id, plan, CamelRuntime.Request("halt"))
        assertEquals("halted", halted["reply"])
        assertEquals("yes", headers(halted)["stopped"])
        assertNull(headers(halted)["branch-continued"])
        assertNull(headers(halted)["route-continued"])

        val continued = CamelRuntime.execute(id, plan, CamelRuntime.Request("go"))
        assertEquals("completed", continued["reply"])
        assertEquals("yes", headers(continued)["route-continued"])
        assertNull(headers(continued)["stopped"])
        assertEquals(2, observed.size)
        observed.forEach(::assertStopped)
    }

    @Test
    fun noRequestHasNoReplyAndStopsTheStartedConsumerBeforeReturning() = contexts { observed ->
        val id = id()
        val plan = CamelRoutePlan("timer:$id?delay=3600000", s_[CamelRouteStep.To("mock:$id")])
        val result = CamelRuntime.execute(id, plan)
        assertEquals("Started", result["status"])
        assertNull(result["reply"])
        assertEquals(emptyMap<String, Any?>(), headers(result))
        assertEquals(1, observed.size)
        assertStopped(observed.single())
        assertFalse(CamelRuntime.isRunning(id))
    }

    @Test
    fun nestedUnselectedAndPostStopEndpointsAreDeniedBeforeContextCreation() = contexts { observed ->
        for (denied in s_["smtp:localhost", "unlisted:destination", "rest:service"].view) {
            val id = id()
            val unreachable = CamelRouteStep.Choice(
                s_[CamelRouteBranch("never", s_[CamelRouteStep.To(denied)])],
                s_[CamelRouteStep.To("mock:$id")],
            )
            val plans = s_[
                CamelRoutePlan("direct:$id", s_[
                    CamelRouteStep.Choice(
                        s_[CamelRouteBranch("selected", s_[CamelRouteStep.SetBody("ok")])],
                        s_[unreachable],
                    ),
                ]),
                CamelRoutePlan("direct:$id", s_[CamelRouteStep.Stop, unreachable]),
            ]
            for (plan in plans.view) {
                val failure = assertFailsWith<IllegalArgumentException> {
                    CamelRuntime.execute(id, plan, CamelRuntime.Request("selected"))
                }
                assertTrue(failure.message.orEmpty().contains(denied.substringBefore(':')), failure.message)
                assertTrue(observed.isEmpty(), "denial must precede even context construction")
                assertFalse(CamelRuntime.isRunning(id))
            }
        }
    }

    @Test
    fun endpointChangedDuringSnapshotIsDeniedBeforeContextCreation() = contexts { observed ->
        val id = id()
        var reads = 0
        val steps: Series<CamelRouteStep> = 1 j { _: Int ->
            CamelRouteStep.To(if (++reads == 1) "mock:$id" else "unlisted:$id")
        }
        val failure = assertFailsWith<IllegalArgumentException> {
            CamelRuntime.execute(id, CamelRoutePlan("direct:$id", steps), CamelRuntime.Request("input"))
        }
        assertTrue("unlisted" in failure.message.orEmpty(), failure.message)
        assertEquals(2, reads, "the gate must inspect the serialized endpoint, not the earlier oracle result")
        assertTrue(observed.isEmpty(), "a changing oracle must not bypass admission")
        assertFalse(CamelRuntime.isRunning(id))
    }

    @Test
    fun nestedEndpointOracleIsNotRevisitedByLoweringOrTheReceipt() = contexts { observed ->
        val id = id()
        var reads = 0
        val steps: Series<CamelRouteStep> = 1 j { _: Int ->
            CamelRouteStep.To(if (++reads <= 2) "mock:$id" else "unlisted:$id")
        }
        val plan = CamelRoutePlan("direct:$id", s_[
            CamelRouteStep.Choice(
                s_[CamelRouteBranch("input", steps)],
                s_[CamelRouteStep.SetBody("otherwise")],
            ),
        ])
        val expected = CamelRoutePlan("direct:$id", s_[
            CamelRouteStep.Choice(
                s_[CamelRouteBranch("input", s_[CamelRouteStep.To("mock:$id")])],
                s_[CamelRouteStep.SetBody("otherwise")],
            ),
        ])
        val result = CamelRuntime.execute(id, plan, CamelRuntime.Request("input"))
        assertEquals("input", result["reply"])
        assertEquals(expected.toValue(), result["plan"], "receipt must describe the admitted, executed snapshot")
        assertEquals(2, reads, "only snapshot construction may read the caller's nested oracle")
        assertEquals(1, observed.size)
        assertStopped(observed.single())
        assertFalse(CamelRuntime.isRunning(id))
    }

    @Test
    fun nonDirectRequestsAndMissingModulesAreRefusedBeforeContextCreation() = contexts { observed ->
        val id = id()
        for (from in s_["timer:$id", "seda:$id", "file:camel-plan-must-not-exist"].view) {
            val failure = assertFailsWith<IllegalStateException> {
                CamelRuntime.execute(id, CamelRoutePlan(from, s_[CamelRouteStep.Stop]), CamelRuntime.Request("body"))
            }
            assertTrue("direct:" in failure.message.orEmpty(), failure.message)
            assertTrue(from in failure.message.orEmpty(), failure.message)
        }
        val missing = assertFailsWith<IllegalStateException> {
            CamelRuntime.execute(id, CamelRoutePlan("direct:$id", s_[CamelRouteStep.Stop]), module = "absent-$id")
        }
        assertTrue("not installed" in missing.message.orEmpty())
        assertTrue("utils/subvm" in missing.message.orEmpty())
        assertTrue(observed.isEmpty())
        assertFalse(CamelRuntime.isRunning(id))
    }

    @Test
    fun startupAndRequestFailuresStopTheirContextAndRestoreTheCallerLoader() = contexts { observed ->
        val id = id()
        val loader = Thread.currentThread().contextClassLoader
        val invalid = CamelRoutePlan("direct:$id?unknownPlanOption=true", s_[CamelRouteStep.To("mock:$id")])
        val startup = assertFails { CamelRuntime.execute(id, invalid) }
        assertTrue("unknownPlanOption" in messages(startup), messages(startup))
        assertEquals(1, observed.size)
        assertStopped(observed.single())
        assertSame(loader, Thread.currentThread().contextClassLoader)

        val absentConsumer = CamelRoutePlan("direct:$id", s_[
            CamelRouteStep.To("direct:absent-$id?block=false&failIfNoConsumers=true"),
        ])
        val request = assertFails { CamelRuntime.execute(id, absentConsumer, CamelRuntime.Request("body")) }
        assertTrue("No consumers" in messages(request), messages(request))
        assertEquals(2, observed.size)
        observed.forEach(::assertStopped)
        assertSame(loader, Thread.currentThread().contextClassLoader)
        assertFalse(CamelRuntime.isRunning(id))
    }

    @Test
    fun oneShotDoesNotReplaceOrStopAnExistingRegisteredRouteWithTheSameId() = contexts { observed ->
        val id = id()
        val running = CamelRuntime.start(id, "direct:registered-$id", "mock:registered-$id")
        try {
            val result = CamelRuntime.execute(
                id, CamelRoutePlan("direct:$id", s_[CamelRouteStep.SetBody("one-shot")]), CamelRuntime.Request("input"),
            )
            assertEquals("one-shot", result["reply"])
            assertSame(running, CamelRuntime.route(id))
            assertEquals("Started", running.status())
            assertEquals("still-running", CamelRuntime.send(id, "still-running"))
            assertEquals(2, observed.size)
            assertStopped(observed.last())
        } finally {
            CamelRuntime.stop(id)
        }
        observed.forEach(::assertStopped)
    }

    @Test
    fun cleanupFailurePreventsSuccessAndBothStopsAreAttempted() {
        val templateFailure = IllegalStateException("template stop")
        val contextFailure = IllegalStateException("context stop")
        val stops = mutableListOf<String>()
        val failure = assertFails {
            CamelRuntime.withCleanup({ stops += "context"; throw InvocationTargetException(contextFailure) }) {
                CamelRuntime.withCleanup({ stops += "template"; throw InvocationTargetException(templateFailure) }) { "reply" }
            }
        }
        assertSame(templateFailure, failure)
        assertEquals(listOf("template", "context"), stops)
        assertEquals(listOf(contextFailure), failure.suppressed.toList())

        assertSame(contextFailure, assertFails {
            CamelRuntime.withCleanup({ throw contextFailure }) { "no-request result" }
        })
    }

    @Test
    fun cleanupFailuresAreSuppressedOnTheOriginalRequestFailure() {
        val requestFailure = IllegalStateException("request")
        val templateFailure = IllegalStateException("template stop")
        val contextFailure = IllegalStateException("context stop")
        val failure = assertFails {
            CamelRuntime.withCleanup({ throw InvocationTargetException(contextFailure) }) {
                CamelRuntime.withCleanup({ throw InvocationTargetException(templateFailure) }) {
                    throw InvocationTargetException(requestFailure)
                }
            }
        }
        assertSame(requestFailure, failure)
        assertEquals(listOf(templateFailure, contextFailure), failure.suppressed.toList())
    }

    private fun id(): String = "plan-${UUID.randomUUID()}"

    private fun headers(result: Map<String, Any?>): Map<*, *> =
        assertIs<Map<*, *>>(result["headers"])

    private fun assertStopped(context: Any) {
        assertEquals("Stopped", context.javaClass.getMethod("getStatus").invoke(context).toString())
    }

    private fun messages(failure: Throwable): String =
        generateSequence(failure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }

    /** Capture the actual contexts through Camel's own tracker, before route construction. */
    private fun contexts(block: (List<Any>) -> Unit) {
        assertTrue(GuestModules.isInstalled(CamelRuntime.MODULE),
            "guest module 'camel' is not installed — run: ./gradlew -p utils/subvm installCamel")
        val loader = assertNotNull(GuestModules.loaderFor(CamelRuntime.MODULE))
        val trackerClass = loader.loadClass("org.apache.camel.spi.CamelContextTracker")
        val filterClass = loader.loadClass("org.apache.camel.spi.CamelContextTracker\$Filter")
        val observed = CopyOnWriteArrayList<Any>()
        val thread = Thread.currentThread()
        val previousLoader = thread.contextClassLoader
        val filter = Proxy.newProxyInstance(loader, arrayOf(filterClass)) { proxy, method, args ->
            when (method.name) {
                "accept", "test" -> {
                    if (Thread.currentThread() === thread) {
                        args?.firstOrNull()?.let { context ->
                            if (observed.none { it === context }) observed += context
                        }
                    }
                    false
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "CamelRoutePlanExecutionTest.Filter"
                else -> null
            }
        }
        val tracker = trackerClass.getConstructor(filterClass).newInstance(filter)
        trackerClass.getMethod("open").invoke(tracker)
        try {
            block(observed)
            assertSame(previousLoader, thread.contextClassLoader)
        } finally {
            trackerClass.getMethod("close").invoke(tracker)
            // Assertions above inspect state before this fallback, which only contains a failing test.
            observed.forEach { context -> runCatching { context.javaClass.getMethod("stop").invoke(context) } }
        }
    }
}
