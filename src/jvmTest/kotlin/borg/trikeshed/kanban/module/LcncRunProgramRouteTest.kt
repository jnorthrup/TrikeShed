package borg.trikeshed.kanban.module

import borg.trikeshed.parse.reify
import borg.trikeshed.parse.jsonOf

import borg.trikeshed.couch.Couch
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.dag.ReteProductionRegistry
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.*
import borg.trikeshed.kanban.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import java.lang.System
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.module.ModuleRouteRegistry
import borg.trikeshed.module.ModuleSupervisor
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations

/**
 * Spec §7 route gate: `POST /api/lcnc/run {program}` executes a stored
 * two-level program (procedure → scope call → child) IN the daemon and
 * returns the outer `scope.out` values — imperative program execution as a
 * service. Also proves the offered scope presets run through the default
 * (presets-only) program loader with zero registered runners.
 */
class LcncRunProgramRouteTest {
    @Test fun oroborosInterventionResumesTheAdmittedRunAndRetainsItsEvidence(): Unit = runBlocking {
        val routes = ModuleRouteRegistry()
        val cas = CasStore.inMemory()
        val ctx = newContext(routes, tempDir("oroboros"), cas)
        var calls = 0
        ctx.lcncRunners["pick"] = LcncNodeRunner { _, inputs ->
            calls++
            mapOf("y" to inputs["x"])
        }
        val server = JvmKanbanServer(moduleRoutes = routes)
        val supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())
        val token = java.util.UUID.randomUUID().toString()
        val execution = async(Dispatchers.Default) {
            post(server, "/api/lcnc/run", jsonOf(mapOf(
                "debugToken" to token, "timeoutMs" to 30000,
                "document" to mapOf("nodes" to listOf(mapOf("id" to "effect", "type" to "pick")),
                    "wires" to emptyList<Any>()),
            )))
        }
        suspend fun get(path: String) =
            server.routeHttp("GET $path HTTP/1.1\r\nHost: t\r\n\r\n".encodeToByteArray())
        try {
            val held = withTimeout(10000) {
                var value = json(get("/api/lcnc/run/debug?token=$token"))
                while (value["state"] != "paused") { delay(10); value = json(get("/api/lcnc/run/debug?token=$token")) }
                value
            }
            val runId = held["runId"]
            val listing = get("/api/lcnc/oroboros")
            assertEquals(200, listing.status, listing.body)
            assertTrue((json(listing)["executions"] as List<*>).any { (it as Map<*, *>)["runId"] == runId })
            val identity = mapOf("runId" to runId, "ordinal" to held["ordinal"])
            assertEquals(202, post(server, "/api/lcnc/oroboros",
                jsonOf(identity + mapOf("action" to "monitor-attach"))).status)
            assertEquals(202, post(server, "/api/lcnc/oroboros",
                jsonOf(identity + mapOf("action" to "intervene", "inputs" to mapOf("x" to 42)))).status)
            assertEquals(0, calls)
            assertEquals(409, post(server, "/api/lcnc/oroboros",
                jsonOf(identity + mapOf("ordinal" to 99999, "action" to "continue"))).status)
            assertEquals(202, post(server, "/api/lcnc/oroboros",
                jsonOf(identity + mapOf("action" to "continue"))).status)
            val completed = withTimeout(10000) { execution.await() }
            assertEquals(200, completed.status, completed.body)
            assertEquals(1, calls)
            val receipt = json(completed)
            assertEquals(42, (((receipt["outputs"] as Map<*, *>)["effect"] as Map<*, *>)["y"] as? Number)?.toInt())
            val evidence = content(server, receipt["oroborosCid"])
            assertEquals(runId, evidence["runId"])
            val detail = get("/api/lcnc/oroboros?runId=$runId")
            assertEquals(200, detail.status, detail.body)
            val debug = json(detail)["debug"] as Map<*, *>
            assertEquals("completed", debug["state"])
            assertTrue((debug["interventions"] as List<*>).isNotEmpty())
            assertEquals(409, post(server, "/api/lcnc/oroboros",
                jsonOf(identity + mapOf("action" to "pause"))).status)
        } finally {
            execution.cancelAndJoin()
            supervisor.detach("kanban")
            ctx.scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
    }

    @Test fun nodeDispatchUsesTheSameServicePrecedenceAsProgramExecution(): Unit = runBlocking {
        for ((moduleClock, muxClock, callerClock, expected) in listOf(
            listOf(null, null, null, 1L), listOf(2L, null, null, 2L),
            listOf(2L, 3L, null, 3L), listOf(2L, 3L, 4L, 4L),
        )) {
            fun clock(value: Long?): kotlin.coroutines.CoroutineContext =
                value?.let { LcncClockKey { it } } ?: kotlin.coroutines.EmptyCoroutineContext
            val routes = ModuleRouteRegistry()
            val ctx = newContext(routes, tempDir("node-context"), CasStore.inMemory(),
                moduleContext = clock(moduleClock), muxContext = clock(muxClock))
            ctx.lcncRunners.putAll(PureNodes.registry { 1L })
            val server = JvmKanbanServer(moduleRoutes = routes)
            val supervisor = ModuleSupervisor(ctx)
            supervisor.attach(KanbanModule())
            try {
                kotlinx.coroutines.withContext(clock(callerClock)) {
                    val single = post(server, "/api/lcnc/run", """{"type":"timer","params":{},"inputs":{}}""")
                    assertEquals(200, single.status, single.body)
                    val program = post(server, "/api/lcnc/run",
                        """{"document":{"nodes":[{"id":"clock","type":"timer"}],"wires":[]}}""")
                    assertEquals(200, program.status, program.body)
                    val nodeTick = ((json(single)["outputs"] as Map<*, *>)["tick"] as Number).toLong()
                    val programTick = (((json(program)["outputs"] as Map<*, *>)["clock"] as Map<*, *>)["tick"] as Number).toLong()
                    assertEquals(expected, programTick, "Program context contract")
                    assertEquals(programTick, nodeTick, "Single-node dispatch must use caller > mux > module > default")
                }
            } finally {
                supervisor.detach("kanban")
                ctx.scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
            }
        }
    }

    @Test fun unsupportedSourceCommandLeavesTheNodeHeldAndContinueExecutesTheGuestOnce(): Unit = runBlocking {
        for ((language, trust) in listOf(borg.trikeshed.vm.VmLanguage.JAVA to "OWN",
            borg.trikeshed.vm.VmLanguage.JAVASCRIPT to "UNTRUSTED")) {
            val routes = ModuleRouteRegistry()
            val ctx = newContext(routes, tempDir("node-debug-${language.id}"), CasStore.inMemory())
            val server = JvmKanbanServer(moduleRoutes = routes)
            val host = borg.trikeshed.vm.HypervisorVmHost()
            ctx.lcncRunners[SubVmLegos.POLYGLOT] = SubVmLegos.polyglot(host)
            val supervisor = ModuleSupervisor(ctx)
            supervisor.attach(KanbanModule())
            val token = java.util.UUID.randomUUID().toString()
            val execution = async(Dispatchers.Default) { post(server, "/api/lcnc/run", jsonOf(mapOf(
                "debugToken" to token, "timeoutMs" to 30000,
                "document" to mapOf("nodes" to listOf(mapOf("id" to "source", "type" to SubVmLegos.POLYGLOT,
                    "params" to language.params("return input;") + mapOf("trust" to trust, "input" to "41"))),
                    "wires" to emptyList<Any>())))) }
            suspend fun snapshot() = json(server.routeHttp(
                "GET /api/lcnc/run/debug?token=$token HTTP/1.1\r\nHost: t\r\n\r\n".encodeToByteArray()))
            try {
                val pause = withTimeout(5000) {
                    var value = snapshot()
                    while (value["state"] != "paused") { delay(10); value = snapshot() }
                    value
                }
                assertEquals(listOf("step", "continue"), pause["actions"])
                assertTrue(host.ids().isEmpty(), "The node hold must precede guest creation")
                val command = mapOf("token" to token, "runId" to pause["runId"], "ordinal" to pause["ordinal"])
                assertEquals(409, post(server, "/api/lcnc/run/debug", jsonOf(command + ("action" to "source-into"))).status)
                assertEquals(pause, snapshot(), "Refusing a source command must not consume the held ordinal")
                assertEquals(202, post(server, "/api/lcnc/run/debug", jsonOf(command + ("action" to "continue"))).status)
                val response = withTimeout(20000) { execution.await() }
                assertEquals(200, response.status, response.body)
                val run = json(response)
                assertEquals(pause["runId"], run["runId"])
                assertEquals(41, (((run["outputs"] as Map<*, *>)["source"] as Map<*, *>)["value"] as Number).toInt())
                assertEquals(1, host.ids().size)
                assertTrue(host.ids().none { host.get(it)?.isAlive == true })
            } finally {
                execution.cancelAndJoin(); supervisor.detach("kanban"); host.close()
                ctx.scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
            }
        }
    }

    @Test fun lineBreakpointsResumeTheSameLoopWithoutReplayingItsEffects(): Unit = runBlocking {
        val routes = ModuleRouteRegistry()
        val ctx = newContext(routes, tempDir("line-debug"), CasStore.inMemory())
        val server = JvmKanbanServer(moduleRoutes = routes)
        val host = borg.trikeshed.vm.HypervisorVmHost()
        ctx.lcncRunners[SubVmLegos.POLYGLOT] = SubVmLegos.polyglot(host)
        val supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())
        val token = java.util.UUID.randomUUID().toString()
        val source = "let count = 0;\nfor (let i = 0; i < 3; i++) {\n  count += 1;\n}\nreturn count;"
        val execution = async(Dispatchers.Default) { post(server, "/api/lcnc/run", jsonOf(mapOf(
            "debugToken" to token, "timeoutMs" to 20000,
            "document" to mapOf("nodes" to listOf(mapOf("id" to "source", "type" to SubVmLegos.POLYGLOT,
                "params" to mapOf("language" to "js", "source" to source))), "wires" to emptyList<Any>())))) }
        suspend fun paused(after: Int = 0): Map<String, Any?> = withTimeout(5000) {
            while (true) {
                val value = json(server.routeHttp("GET /api/lcnc/run/debug?token=$token HTTP/1.1\r\nHost: t\r\n\r\n".encodeToByteArray()))
                if (value["state"] == "paused" && (value["ordinal"] as Number).toInt() > after) return@withTimeout value
                delay(10)
            }
            error("unreachable")
        }
        suspend fun command(at: Map<String, Any?>, action: String, line: Int? = null, coordinates: String? = null) =
            post(server, "/api/lcnc/run/debug", jsonOf(mapOf("token" to token, "runId" to at["runId"],
                "ordinal" to at["ordinal"], "action" to action, "line" to line) +
                (if (coordinates == null) emptyMap() else mapOf("coordinates" to coordinates))))
        try {
            val node = paused()
            assertEquals(202, command(node, "source-into").status)
            var stop = paused((node["ordinal"] as Number).toInt())
            for (selector in listOf(null, "", "AUTHORED", "unknown", 1, true)) {
                val refused = post(server, "/api/lcnc/run/debug", jsonOf(mapOf(
                    "token" to token, "runId" to stop["runId"], "ordinal" to stop["ordinal"],
                    "action" to "source-line", "line" to 1, "coordinates" to selector)))
                assertEquals(400, refused.status, refused.body)
                assertEquals("invalid_debug_coordinates", json(refused)["error"])
                assertEquals(stop, paused(), "Invalid coordinates must not consume the source hold")
            }
            for (action in listOf("step", "continue", "source-into", "source-over", "source-out")) {
                for (selector in listOf("generated", "authored")) {
                    val refused = command(stop, action, coordinates = selector)
                    assertEquals(400, refused.status, refused.body)
                    assertEquals("invalid_debug_coordinates", json(refused)["error"])
                    assertEquals(stop, paused(), "Coordinates apply only to source-line")
                }
            }
            for (invalid in listOf(null, 0, -1, 1.5, "4", 2147483648L)) {
                assertEquals(400, post(server, "/api/lcnc/run/debug", jsonOf(mapOf(
                    "token" to token, "runId" to stop["runId"], "ordinal" to stop["ordinal"], "action" to "source-line", "line" to invalid))).status)
                assertEquals(stop["ordinal"], paused()["ordinal"])
            }
            val authored = (stop["site"] as Map<*, *>)["authored"] as Map<*, *>
            val authoredLine = 4 - (authored["generatedStartLine"] as Number).toInt() + 1
            assertTrue(authoredLine in 1..(authored["lineCount"] as Number).toInt())
            val refused = command(stop, "source-line", (authored["lineCount"] as Number).toInt() + 1, "authored")
            assertEquals(400, refused.status, refused.body)
            assertEquals("invalid_debug_line", json(refused)["error"])
            assertEquals(stop, paused(), "Authored bounds must not consume the source hold")
            assertEquals(202, command(stop, "source-line", authoredLine, "authored").status)
            stop = paused((stop["ordinal"] as Number).toInt())
            for (expected in 0..2) {
                val site = stop["site"] as Map<*, *>
                assertEquals(4, (site["line"] as Number).toInt())
                val breakpoint = site["breakpoint"] as Map<*, *>
                assertEquals(true, breakpoint["hit"])
                assertEquals(4, (breakpoint["requestedLine"] as Number).toInt())
                assertEquals(4, (breakpoint["resolvedLine"] as Number).toInt())
                val locals = ((site["frames"] as List<*>).first() as Map<*, *>)["locals"] as List<*>
                assertTrue(locals.any { val row = it as Map<*, *>; row["name"] == "count" && (row["value"] as? Number)?.toInt() == expected })
                assertEquals(400, command(stop, "source-line", 10000).status)
                assertEquals(stop["ordinal"], paused()["ordinal"], "Invalid input must not release the hold")
                if (expected < 2) {
                    val old = stop
                    assertEquals(202, command(stop, "source-line", 4, if (expected == 0) "generated" else null).status)
                    stop = paused((stop["ordinal"] as Number).toInt())
                    assertEquals(409, command(old, "source-line", authoredLine, "authored").status)
                    assertEquals(stop, paused(), "A stale authored command must leave the newer pause held")
                }
            }
            assertEquals(202, command(stop, "continue").status)
            val response = withTimeout(5000) { execution.await() }
            assertEquals(200, response.status, response.body)
            val run = json(response)
            assertEquals(node["runId"], run["runId"])
            assertEquals(3, (((run["outputs"] as Map<*, *>)["source"] as Map<*, *>)["value"] as Number).toInt())
            assertEquals(1, host.ids().size)
            assertTrue(host.ids().none { host.get(it)?.isAlive == true })
        } finally {
            execution.cancelAndJoin(); supervisor.detach("kanban"); host.close()
            ctx.scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
    }

    @Test fun sourceHoldsRespectRunTimeoutCancellationAndModuleDrain(): Unit = runBlocking {
        for (lineHold in listOf(false, true)) for (action in listOf("timeout", "cancel", "drain")) {
            val routes = ModuleRouteRegistry()
            val ctx = newContext(routes, tempDir("source-$action"), CasStore.inMemory())
            val server = JvmKanbanServer(moduleRoutes = routes)
            val host = borg.trikeshed.vm.HypervisorVmHost()
            ctx.lcncRunners[SubVmLegos.POLYGLOT] = SubVmLegos.polyglot(host)
            val supervisor = ModuleSupervisor(ctx)
            supervisor.attach(KanbanModule())
            val token = java.util.UUID.randomUUID().toString()
            val execution = async(Dispatchers.Default) { post(server, "/api/lcnc/run", jsonOf(mapOf(
                "debugToken" to token, "timeoutMs" to if (action == "timeout") 3000 else 20000,
                "document" to mapOf("nodes" to listOf(mapOf("id" to "source", "type" to SubVmLegos.POLYGLOT,
                    "params" to mapOf("language" to "js", "source" to "const result = input + 1;\nreturn result;", "input" to "41"))),
                    "wires" to emptyList<Any>())))) }
            suspend fun paused(kind: String? = null, after: Int = 0): Map<String, Any?> = withTimeout(5000) {
                while (true) {
                    val value = json(server.routeHttp("GET /api/lcnc/run/debug?token=$token HTTP/1.1\r\nHost: t\r\n\r\n".encodeToByteArray()))
                    if (value["state"] == "paused" && (value["site"] as Map<*, *>)["kind"] == kind &&
                        (value["ordinal"] as Number).toInt() > after) return@withTimeout value
                    delay(10)
                }
                error("unreachable")
            }
            try {
                val node = paused()
                assertEquals(202, post(server, "/api/lcnc/run/debug", jsonOf(mapOf(
                    "token" to token, "runId" to node["runId"], "ordinal" to node["ordinal"], "action" to "source-into"))).status)
                var source = paused("source")
                if (lineHold) {
                    assertEquals(202, post(server, "/api/lcnc/run/debug", jsonOf(mapOf(
                        "token" to token, "runId" to source["runId"], "ordinal" to source["ordinal"], "action" to "source-line", "line" to 3))).status)
                    source = paused("source", (source["ordinal"] as Number).toInt())
                    assertEquals(true, ((source["site"] as Map<*, *>)["breakpoint"] as Map<*, *>)["hit"])
                }
                assertEquals(node["runId"], source["runId"])
                assertEquals(1, host.ids().count { host.get(it)?.isAlive == true })
                if (action == "cancel") assertEquals(202, post(server, "/api/lcnc/run/cancel", jsonOf(mapOf("runId" to source["runId"]))).status)
                if (action == "drain") withTimeout(5000) { supervisor.detach("kanban") }
                val response = withTimeout(5000) { execution.await() }
                assertEquals(when (action) { "timeout" -> 504; "cancel" -> 499; else -> 200 }, response.status, response.body)
                val result = json(response)
                assertEquals(when (action) { "timeout" -> "timed_out"; "cancel" -> "cancelled"; else -> "completed" }, result["status"])
                assertTrue(result["receiptCid"] is String)
                assertEquals(1, host.ids().size)
                assertTrue(host.ids().none { host.get(it)?.isAlive == true })
            } finally {
                execution.cancelAndJoin()
                supervisor.detach("kanban")
                host.close()
                ctx.scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
            }
        }
    }

    @Test fun sourceStepsSuspendTheSameGuestAndRetainItsResult(): Unit = runBlocking {
        val routes = ModuleRouteRegistry()
        val ctx = newContext(routes, tempDir("source-debug"), CasStore.inMemory())
        val server = JvmKanbanServer(moduleRoutes = routes)
        val host = borg.trikeshed.vm.HypervisorVmHost()
        ctx.lcncRunners[SubVmLegos.POLYGLOT] = SubVmLegos.polyglot(host)
        val supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())
        val token = java.util.UUID.randomUUID().toString()
        val source = "let count = 0;\nfunction add(value) {\n  count++;\n  return value + 1;\n}\nconst answer = add(input);\nreturn {count: count, answer: answer};"
        val execution = async(Dispatchers.Default) { post(server, "/api/lcnc/run", jsonOf(mapOf(
            "debugToken" to token, "timeoutMs" to 20000,
            "document" to mapOf("nodes" to listOf(mapOf("id" to "source", "type" to SubVmLegos.POLYGLOT,
                "params" to mapOf("language" to "js", "source" to source, "input" to "41"))), "wires" to emptyList<Any>())))) }
        suspend fun paused(after: Int = 0): Map<String, Any?> = withTimeout(5000) {
            while (true) {
                val value = json(server.routeHttp("GET /api/lcnc/run/debug?token=$token HTTP/1.1\r\nHost: t\r\n\r\n".encodeToByteArray()))
                if (value["state"] == "paused" && (value["ordinal"] as Number).toInt() > after) return@withTimeout value
                delay(10)
            }
            error("unreachable")
        }
        suspend fun command(at: Map<String, Any?>, action: String) = post(server, "/api/lcnc/run/debug",
            jsonOf(mapOf("token" to token, "runId" to at["runId"], "ordinal" to at["ordinal"], "action" to action)))
        try {
            val node = paused()
            assertEquals(202, command(node, "source-into").status)
            var stop = paused((node["ordinal"] as Number).toInt())
            var sawLocal = false
            var sawFunction = false
            repeat(12) {
                val site = stop["site"] as Map<*, *>
                assertEquals("source", site["kind"])
                assertEquals("source", site["sourceName"])
                assertTrue((site["line"] as Number).toInt() > 0)
                val frames = (site["frames"] as List<*>).map { it as Map<*, *> }
                sawFunction = sawFunction || frames.any { it["name"] == "add" }
                sawLocal = sawLocal || frames.any { frame -> (frame["locals"] as List<*>).any {
                    val local = it as Map<*, *>
                    local["name"] == "count" && (local["value"] as? Number)?.toInt() == 1
                } }
                if (sawLocal && sawFunction) return@repeat
                assertEquals(409, command(node, "source-into").status, "Old node commands cannot step a source pause")
                assertEquals(202, command(stop, "source-into").status)
                stop = paused((stop["ordinal"] as Number).toInt())
            }
            assertTrue(sawFunction, "Stepping enters the real guest function")
            assertTrue(sawLocal, "A copied local exposes the increment in that same call")
            assertEquals(1, host.ids().size)
            assertEquals(202, command(stop, "continue").status)
            val response = withTimeout(5000) { execution.await() }
            assertEquals(200, response.status, response.body)
            val run = json(response)
            assertEquals(node["runId"], run["runId"])
            val outputs = (run["outputs"] as Map<*, *>)["source"] as Map<*, *>
            val value = outputs["value"] as Map<*, *>
            assertEquals(1, (value["count"] as Number).toInt())
            assertEquals(42, (value["answer"] as Number).toInt())
            assertTrue(host.ids().none { host.get(it)?.isAlive == true })
        } finally {
            execution.cancelAndJoin()
            supervisor.detach("kanban")
            host.close()
            ctx.scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
    }

    @Test fun pausedRunsRespectTimeoutCancellationAndModuleDrain(): Unit = runBlocking {
        for (action in listOf("timeout", "cancel", "drain")) {
            val routes = ModuleRouteRegistry()
            val ctx = newContext(routes, tempDir("debug-$action"), CasStore.inMemory())
            val server = JvmKanbanServer(moduleRoutes = routes)
            var calls = 0
            ctx.lcncRunners["test.effect"] = LcncNodeRunner { _, _ -> calls++; emptyMap() }
            val supervisor = ModuleSupervisor(ctx)
            supervisor.attach(KanbanModule())
            val token = java.util.UUID.randomUUID().toString()
            val execution = async { post(server, "/api/lcnc/run", jsonOf(mapOf("debugToken" to token,
                "timeoutMs" to if (action == "timeout") 1000 else 10000,
                "document" to mapOf("nodes" to listOf(mapOf("id" to "effect", "type" to "test.effect")), "wires" to emptyList<Any>())))) }
            try {
                val pause = withTimeout(5000) {
                    var value: Map<String, Any?>
                    do {
                        delay(10)
                        value = json(server.routeHttp("GET /api/lcnc/run/debug?token=$token HTTP/1.1\r\nHost: t\r\n\r\n".encodeToByteArray()))
                    } while (value["state"] != "paused")
                    value
                }
                assertEquals(0, calls)
                if (action == "cancel") assertEquals(202, post(server, "/api/lcnc/run/cancel", jsonOf(mapOf("runId" to pause["runId"]))).status)
                if (action == "drain") withTimeout(5000) { supervisor.detach("kanban") }
                val response = withTimeout(5000) { execution.await() }
                assertEquals(when (action) { "timeout" -> 504; "cancel" -> 499; else -> 200 }, response.status, response.body)
                val result = json(response)
                assertEquals(when (action) { "timeout" -> "timed_out"; "cancel" -> "cancelled"; else -> "completed" }, result["status"])
                assertEquals(if (action == "drain") 1 else 0, calls)
                assertTrue(result["receiptCid"] is String)
            } finally {
                execution.cancelAndJoin()
                supervisor.detach("kanban")
                ctx.scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
            }
        }
    }

    @Test fun debuggerRecoveryPinsTheRunAcrossTokenReuseAndRetainsTheTerminalReceipt(): Unit = runBlocking {
        val routes = ModuleRouteRegistry()
        val ctx = newContext(routes, tempDir("debug-recovery"), CasStore.inMemory())
        val server = JvmKanbanServer(moduleRoutes = routes)
        var effects = 0
        ctx.lcncRunners["test.effect"] = LcncNodeRunner { _, _ -> effects++; emptyMap() }
        val supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())
        val token = java.util.UUID.randomUUID().toString()
        val request = jsonOf(mapOf("debugToken" to token, "document" to mapOf(
            "nodes" to listOf(mapOf("id" to "effect", "type" to "test.effect")), "wires" to emptyList<Any>())))
        suspend fun observe(runId: String? = null) = server.routeHttp(
            ("GET /api/lcnc/run/debug?token=$token" + (runId?.let { "&runId=$it" } ?: "") + " HTTP/1.1\r\nHost: t\r\n\r\n").encodeToByteArray())
        suspend fun paused(): Map<String, Any?> = withTimeout(5000) {
            while (true) {
                val value = json(observe())
                if (value["state"] == "paused") return@withTimeout value
                delay(10)
            }
            error("unreachable")
        }
        suspend fun advance(at: Map<String, Any?>) = post(server, "/api/lcnc/run/debug",
            jsonOf(mapOf("token" to token, "runId" to at["runId"], "ordinal" to at["ordinal"], "action" to "continue")))
        var execution = async { post(server, "/api/lcnc/run", request) }
        try {
            val first = paused()
            val id = first["runId"] as String
            val before = ctx.blackboard.snapshot()
            repeat(3) { assertEquals(first, json(observe(id)), "Reconnection is observation, not admission or stepping") }
            assertEquals(before, ctx.blackboard.snapshot())
            assertEquals(0, effects)
            assertEquals(409, observe(java.util.UUID.randomUUID().toString()).status)
            assertEquals(409, advance(first + ("runId" to java.util.UUID.randomUUID().toString())).status)
            assertEquals(400, post(server, "/api/lcnc/run/debug", jsonOf(mapOf(
                "token" to token, "ordinal" to first["ordinal"], "action" to "continue"))).status)
            assertEquals(first, json(observe(id)))
            assertEquals(202, advance(first).status)
            val receipt = json(execution.await())
            assertEquals(1, effects)
            val terminal = observe(id)
            assertEquals(404, terminal.status, "Retained evidence must not pretend the controller is live")
            val retained = json(terminal)["receipt"] as Map<*, *>
            assertEquals(id, retained["runId"])
            assertEquals(receipt["receiptCid"], retained["receiptCid"])
            assertEquals("completed", retained["status"])
            assertNull(json(observe(java.util.UUID.randomUUID().toString()))["receipt"])

            execution = async { post(server, "/api/lcnc/run", request) }
            val second = paused()
            assertNotEquals(id, second["runId"])
            assertEquals(first["ordinal"], second["ordinal"], "Pause numbers alone cannot identify a run")
            assertEquals(409, observe(id).status)
            assertEquals(409, advance(first).status, "A saved command cannot advance a new run using the old token")
            assertEquals(second, json(observe(second["runId"] as String)))
            assertEquals(1, effects)
            assertEquals(202, advance(second).status)
            assertEquals("completed", json(execution.await())["status"])
            assertEquals(2, effects)
            supervisor.detach("kanban")
            supervisor.attach(KanbanModule())
            val recovered = observe(id)
            assertEquals(404, recovered.status)
            assertEquals(receipt["receiptCid"], (json(recovered)["receipt"] as Map<*, *>)["receiptCid"],
                "A module restart recovers durable evidence, not the transient controller")
            assertEquals(2, effects)
        } finally {
            execution.cancelAndJoin()
            supervisor.detach("kanban")
            ctx.scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
    }

    @Test fun debuggerStepsTheAdmittedRunAndRejectsStaleCommands(): Unit = runBlocking {
        val routes = ModuleRouteRegistry()
        val ctx = newContext(routes, tempDir("debug-step"), CasStore.inMemory())
        val server = JvmKanbanServer(moduleRoutes = routes)
        val calls = mutableListOf<String>()
        ctx.lcncRunners["test.effect"] = LcncNodeRunner { node, inputs ->
            calls += node.id
            mapOf("value" to inputs["x"])
        }
        val supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())
        val token = java.util.UUID.randomUUID().toString()
        val request = jsonOf(mapOf("name" to "debug-inline", "debugToken" to token,
            "document" to mapOf("nodes" to listOf(
                mapOf("id" to "note", "type" to "note"),
                mapOf("id" to "input", "type" to "scope.in", "params" to mapOf("name" to "x")),
                mapOf("id" to "first", "type" to "test.effect"),
                mapOf("id" to "second", "type" to "test.effect")),
                "wires" to listOf(mapOf("from" to listOf("input", "value"), "to" to listOf("first", "x")),
                    mapOf("from" to listOf("first", "value"), "to" to listOf("second", "x")))),
            "inputs" to mapOf("x" to "synthetic")))
        val execution = async { post(server, "/api/lcnc/run", request) }
        suspend fun state() = server.routeHttp("GET /api/lcnc/run/debug?token=$token HTTP/1.1\r\nHost: t\r\n\r\n".encodeToByteArray())
        suspend fun paused(node: String): Map<String, Any?> = withTimeout(5000) {
            while (true) {
                val value = json(state())
                if (value["state"] == "paused" && (value["site"] as? Map<*, *>)?.get("nodeId") == node) return@withTimeout value
                delay(10)
            }
            error("unreachable")
        }
        suspend fun command(at: Map<String, Any?>, action: String) = post(server, "/api/lcnc/run/debug",
            jsonOf(mapOf("token" to token, "runId" to at["runId"], "ordinal" to at["ordinal"], "action" to action)))
        try {
            val input = paused("input")
            assertTrue(calls.isEmpty())
            val before = ctx.blackboard.snapshot()
            assertEquals(input, json(state()), "Inspection cannot advance the run")
            assertEquals(before, ctx.blackboard.snapshot())
            assertEquals(400, command(input, "restart").status)
            assertEquals(400, post(server, "/api/lcnc/run/debug", jsonOf(mapOf("token" to token, "runId" to input["runId"], "ordinal" to 1.5, "action" to "step"))).status)
            assertEquals(400, post(server, "/api/lcnc/run", request.replace(token, "invalid")).status)
            assertEquals(400, post(server, "/api/lcnc/run", jsonOf(mapOf("type" to "test.effect", "debugToken" to token))).status)
            assertTrue(calls.isEmpty(), "Malformed debug requests never dispatch effects")
            assertEquals(409, post(server, "/api/lcnc/run", request).status, "An active token cannot admit another run")
            assertEquals(202, command(input, "step").status)
            val first = paused("first")
            assertEquals(mapOf("x" to "synthetic"), (first["site"] as Map<*, *>)["inputs"])
            assertTrue(calls.isEmpty(), "Pause precedes the effect")
            assertEquals(409, command(input, "step").status)
            assertEquals(202, command(first, "step").status)
            val second = paused("second")
            assertEquals(listOf("first"), calls)
            assertEquals(202, command(second, "continue").status)
            val result = json(withTimeout(5000) { execution.await() })
            assertEquals("completed", result["status"])
            assertEquals(input["runId"], result["runId"])
            assertEquals(input["programCid"], result["programCid"])
            assertEquals(listOf("first", "second"), calls)
            assertEquals(404, state().status, "Terminal runs retain receipts, not live controllers")
            assertEquals(404, command(second, "step").status)
        } finally {
            execution.cancelAndJoin()
            supervisor.detach("kanban")
            ctx.scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
    }

    @Test fun failedGuestProfilesRemainReadableFromTheImmutableRunReceipt(): Unit = runBlocking {
        val routes = ModuleRouteRegistry()
        val ctx = newContext(routes, tempDir("guest-evidence"), CasStore.inMemory())
        val server = JvmKanbanServer(moduleRoutes = routes)
        val host = borg.trikeshed.vm.HypervisorVmHost()
        ctx.lcncRunners[SubVmLegos.POLYGLOT] = SubVmLegos.polyglot(host)
        val supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())
        try {
            val response = post(server, "/api/lcnc/run", jsonOf(mapOf("document" to mapOf("nodes" to listOf(
                mapOf("id" to "guest-failure", "type" to SubVmLegos.POLYGLOT, "params" to mapOf(
                    "language" to "js", "source" to "function reviewed() { return false; }; reviewed(); throw new Error('source refusal');"))),
                "wires" to emptyList<Any>()))))
            assertEquals(400, response.status, response.body)
            val run = json(response)
            val command = content(server, run["receiptCid"])
            val retained = command["lcncRun"] as Map<*, *>
            val evidence = content(server, retained["invocationsCid"])
            val invocation = (evidence["entries"] as List<*>).single() as Map<*, *>
            assertEquals("failed", invocation["status"])
            val runtime = invocation["runtime"] as Map<*, *>
            val guest = host.get(runtime["vmId"] as String)!!
            assertFalse(guest.isAlive)
            val roots = (runtime["pointcuts"] as Map<*, *>)["roots"] as List<*>
            assertTrue(roots.any { (it as Map<*, *>)["root"] == "reviewed" })
            val counters = guest.stats()
            val before = ctx.blackboard.snapshot().revision
            assertEquals(evidence, content(server, retained["invocationsCid"]))
            assertEquals(counters, guest.stats())
            assertEquals(before, ctx.blackboard.snapshot().revision)
        } finally {
            try { supervisor.detach("kanban") } finally {
                try { host.close() } finally {
                    ctx.scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
                }
            }
        }
    }


    @Test fun failedExecutionRetainsInvocationEvidenceInTheExistingContentStore(): Unit = runBlocking {
        val routes = ModuleRouteRegistry()
        val cas = CasStore.inMemory()
        val ctx = newContext(routes, tempDir("invocations"), cas)
        val server = JvmKanbanServer(moduleRoutes = routes)
        var attempts = 0
        ctx.lcncRunners["test.fail"] = LcncNodeRunner { _, _ ->
            attempts++
            error("deliberate node failure")
        }
        val supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())
        try {
            val response = post(server, "/api/lcnc/run", """{"name":"failed-inline","document":{"nodes":[
                {"id":"input","type":"scope.in","params":{"name":"text"}},
                {"id":"failure","type":"test.fail"},
                {"id":"unreached","type":"test.fail"}],
                "wires":[{"from":["input","value"],"to":["failure","x"]}]},"inputs":{"text":"retained"}}""")
            assertEquals(400, response.status, response.body)
            val run = json(response)
            assertEquals(1, attempts)
            val evidence = content(server, run["invocationsCid"])
            assertEquals(run["runId"], evidence["runId"])
            assertEquals(run["programCid"], evidence["programCid"])
            val calls = (evidence["entries"] as List<*>).map { it as Map<*, *> }
            assertEquals(listOf("completed", "failed"), calls.map { it["status"] })
            assertEquals(mapOf("x" to "retained"), calls.last()["inputs"])
            assertEquals("deliberate node failure", calls.last()["reason"])
            val command = content(server, run["receiptCid"])
            assertEquals("lcnc/run/${run["runId"]}", command["jobId"])
            val saved = command["lcncRun"] as Map<*, *>
            assertEquals(run["runId"], saved["runId"])
            assertEquals(run["programCid"], saved["programCid"])
            assertEquals(run["invocationsCid"], saved["invocationsCid"])
            assertEquals("failed-inline", saved["program"])
            assertEquals(null, saved["programKey"])
            assertEquals(false, saved["ok"])
            assertEquals("failed", saved["status"])
            assertEquals("deliberate node failure", saved["error"])
            assertEquals("failed", run["status"])
            assertEquals(1, attempts, "Inspecting failure evidence must not retry the effect or run the next statement")
        } finally { supervisor.detach("kanban"); ctx.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

    @Test fun invocationLimitsDoNotConsumeTheExistingOutputBudget(): Unit = runBlocking {
        val routes = ModuleRouteRegistry()
        val cas = CasStore.inMemory()
        val ctx = newContext(routes, tempDir("invocation-budget"), cas)
        val value = "x".repeat(120000)
        ctx.lcncRunners["test.large"] = LcncNodeRunner { _, _ -> mapOf("text" to value) }
        val supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())
        try {
            val response = post(JvmKanbanServer(moduleRoutes = routes), "/api/lcnc/run",
                """{"document":{"nodes":[{"id":"n","type":"test.large"}],"wires":[]}}""")
            assertEquals(200, response.status, response.body.take(1000))
            val run = json(response)
            assertEquals("completed", run["status"])
            assertEquals(value, ((run["outputs"] as Map<*, *>)["n"] as Map<*, *>)["text"])
            val evidence = reify(cas.get(borg.trikeshed.job.ContentId(run["invocationsCid"].toString()))!!.decodeToString()) as Map<*, *>
            val call = (evidence["entries"] as List<*>).single() as Map<*, *>
            assertEquals("completed", call["status"])
            assertEquals("payload_limit", call["outputsOmitted"])
            assertFalse(call.containsKey("outputs"))
        } finally { supervisor.detach("kanban"); ctx.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

    @Test
    fun invocationBindingsAndEffectiveConstructionReachDurableReceipt(): Unit = runBlocking {
        val p = program("factory-args", listOf(
            LcncNode("input", "scope.in", mapOf("name" to "construction")),
            LcncNode("factory", "ccek.incarnate"),
            LcncNode("out", "scope.out", mapOf("name" to "instance")),
        ), listOf(LcncWire("input", "value", "factory", "args?"), LcncWire("factory", "node", "out", "value")))
        val routes = ModuleRouteRegistry()
        val server = JvmKanbanServer(moduleRoutes = routes)
        val ctx = newContext(routes, tempDir("factory-args"), CasStore.inMemory(), mapOf(p.name to p))
        ctx.lcncRunners.putAll(borg.trikeshed.lcnc.CcekNodes.registry(borg.trikeshed.lcnc.CcekSeams.live(ctx.scope)))
        val supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())
        try {
            val request = """{"program":"factory-args","inputs":{"construction":{"title":"route-instance","maxConcurrency":3,"record":true}}}"""
            val response = post(server, "/api/lcnc/run", request)
            assertEquals(200, response.status, response.body)
            val result = json(response)
            val instance = (result["returns"] as Map<*, *>)["instance"] as Map<*, *>
            assertEquals(3, (instance["maxConcurrency"] as Number).toInt())
            val bindings = result["bindings"] as List<*>
            assertEquals("invocation", (bindings.single() as Map<*, *>)["source"])
            val receipt = ctx.blackboard.get("lcnc/run/${result["runId"]}") as Map<*, *>
            assertEquals(result["bindings"], reify(jsonOf(receipt["bindings"])))
            assertEquals(result["inputs"], receipt["inputs"])
            val conflict = post(server, "/api/lcnc/run", request.replace("Concurrency\":3", "Concurrency\":4"))
            assertEquals(400, conflict.status, conflict.body)
            assertTrue(json(conflict)["error"].toString().contains("incarnation_conflict"))
            val malformed = post(server, "/api/lcnc/run", """{"program":"factory-args","inputs":[]}""")
            assertEquals(400, malformed.status)
            assertEquals("inputs_must_be_object", json(malformed)["error"])
        } finally {
            supervisor.drainAll()
            ctx.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "lcnc-run-$name-${System.nanoTime()}").apply { mkdirs() }

    private fun newContext(
        routes: ModuleRouteRegistry,
        stateDir: File,
        cas: CasStore,
        programs: Map<String, LcncProgram> = emptyMap(),
        moduleContext: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext,
        muxContext: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext,
    ): ModuleContext {
        val couchStore = CouchStoreFactory.casBacked(cas)
        return ModuleContext(
            couch = Couch("lcnc-run-test", couchStore, cas),
            rete = ReteNetwork(),
            productions = ReteProductionRegistry(),
            beliefBag = null,
            turnReview = null,
            blackboard = ConfixBlackboard.empty(),
            casStore = cas,
            attachments = CouchAttachmentGateway(couchStore, cas),
            routes = routes,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + moduleContext),
            muxContext = muxContext,
            clock = { 1234L },
            stateDir = stateDir,
            // Mirrors production composition: explicit fixtures first, offered presets behind.
            programLoader = { name ->
                programs[name] ?: borg.trikeshed.lcnc.LcncPresets.all()[name]
                    ?.let { borg.trikeshed.lcnc.LcncProgramConfix.fromJson(name, it) }
            },
        )
    }

    private suspend fun post(server: JvmKanbanServer, path: String, body: String): JvmKanbanServer.HttpResponse =
        server.routeHttp(
            "POST $path HTTP/1.1\r\nHost: t\r\nContent-Type: application/json\r\n\r\n$body"
                .toByteArray(StandardCharsets.UTF_8),
        )

    private suspend fun content(server: JvmKanbanServer, cid: Any?): Map<String, Any?> {
        val response = server.routeHttp("GET /api/lcnc/content?cid=$cid HTTP/1.1\r\nHost: t\r\n\r\n".encodeToByteArray())
        assertEquals(200, response.status, response.body)
        assertEquals(cid, ContentId.of(response.body.encodeToByteArray()).value)
        return json(response)
    }

    @Suppress("UNCHECKED_CAST")
    private fun json(resp: JvmKanbanServer.HttpResponse): Map<String, Any?> =
        reify(resp.body) as Map<String, Any?>

    private fun program(name: String, nodes: List<LcncNode>, wires: List<LcncWire>) =
        LcncProgram(name, nodes.toSeries(), wires.toSeries())

    @Test
    fun shakeRouteConfirmsParentAndRefusesUnknownSelection(): Unit = runBlocking {
        val routes = ModuleRouteRegistry()
        val server = JvmKanbanServer(moduleRoutes = routes)
        val ctx = newContext(routes, tempDir("selected-shake"), CasStore.inMemory())
        val supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())
        try {
            val document = borg.trikeshed.lcnc.LcncPresets.all().getValue("preset-scope")
            for (parent in listOf("r2", "missing", "n0")) {
                val response = post(server, "/api/lcnc/treeshake", """{"program":$document,"options":{"parentId":"$parent"}}""")
                assertEquals(if (parent == "r2") 200 else 400, response.status, response.body)
                if (parent == "r2") {
                    assertEquals("r2", json(response)["parentId"])
                    assertTrue((json(response)["made"] as List<*>).isEmpty())
                    assertTrue((json(response)["verdicts"] as List<*>).all { (it as Map<*, *>)["nodeId"] in setOf("b", "p") })
                } else assertEquals("bad_selection", json(response)["error"])
            }
        } finally { supervisor.detach("kanban") }
    }

    @Test
    fun runsAStoredTwoLevelProgramAndReturnsOuterScopeOuts(): Unit = runBlocking {
        val inner = program(
            "inner-shout",
            listOf(
                LcncNode("p1", LcncContracts.SCOPE_IN, params = mapOf("name" to "text")),
                LcncNode("p2", "test.upper"),
                LcncNode("p3", LcncContracts.SCOPE_OUT, params = mapOf("name" to "result")),
            ),
            listOf(
                LcncWire("p1", "value", "p2", "x"),
                LcncWire("p2", "y", "p3", "value"),
            ),
        )
        val outer = program(
            "outer",
            listOf(
                LcncNode("t1", LcncContracts.SCOPE_IN, params = mapOf("name" to "text")),
                LcncNode("s", LcncContracts.SCOPE, subprogram = "inner-shout"),
                LcncNode("r", LcncContracts.SCOPE_OUT, params = mapOf("name" to "result")),
            ),
            listOf(
                LcncWire("t1", "value", "s", "text"),
                LcncWire("s", "result", "r", "value"),
            ),
        )
        val routes = ModuleRouteRegistry()
        val server = JvmKanbanServer(moduleRoutes = routes)
        val ctx = newContext(routes, tempDir("two-level"), CasStore.inMemory(),
            programs = mapOf("outer" to outer, "inner-shout" to inner))
        ctx.lcncRunners["test.upper"] = LcncNodeRunner { _, inputs -> mapOf("y" to inputs["x"]?.toString()?.uppercase()) }
        val supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())

        val resp = post(server, "/api/lcnc/run", """{"program":"outer","inputs":{"text":"hi"}}""")
        assertEquals(200, resp.status, resp.body)
        val body = json(resp)
        assertEquals(true, body["ok"])
        val returns = body["returns"] as Map<*, *>
        assertEquals("HI", returns["result"], "the outer scope.out value came back through the route: $body")
        val receipt = ctx.blackboard.get("lcnc/run/${body["runId"]}") as Map<*, *>
        assertEquals("completed", receipt["status"])
        assertEquals("lcnc/program/outer", receipt["programKey"])
        assertTrue((receipt["sequence"] as Number).toLong() > 0)
        assertTrue((receipt["programVersions"] as Map<*, *>).keys.containsAll(listOf("outer", "inner-shout")))
        val bytes = ctx.casStore.get(borg.trikeshed.job.ContentId(receipt["programCid"].toString()))!!
        assertEquals(borg.trikeshed.job.ContentId.of(bytes).value, receipt["programCid"])

        val missing = post(server, "/api/lcnc/run", """{"program":"ghost"}""")
        assertEquals(404, missing.status, "an unknown program is a loud 404, not an empty run")
        supervisor.detach("kanban")
    }

    @Test
    fun runsAnInlineDocumentWithANestedRingAndSurfacesUnknownTypes(): Unit = runBlocking {
        // The panels canvas posts a scope's children as an inline document —
        // {name, document, inputs} — and the ring runs under the same
        // LcncScopeFrame nesting as a stored program: scope.in binds from
        // inputs through the frame chain (two rings deep), work runs, the
        // warm base comes back per direct-child id.
        val routes = ModuleRouteRegistry()
        val server = JvmKanbanServer(moduleRoutes = routes)
        val ctx = newContext(routes, tempDir("inline"), CasStore.inMemory())
        ctx.lcncRunners["test.upper"] = LcncNodeRunner { _, inputs -> mapOf("y" to inputs["x"]?.toString()?.uppercase()) }
        val supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())

        val doc = """{"nodes":[
            {"id":"a","type":"scope.in","params":{"name":"text"}},
            {"id":"b","type":"test.upper"},
            {"id":"r","type":"scope","children":[
                {"id":"ri","type":"scope.in","params":{"name":"text"}},
                {"id":"ro","type":"scope.out","params":{"name":"result"}}]},
            {"id":"c","type":"scope.out","params":{"name":"result"}}],
          "wires":[{"from":["a","value"],"to":["b","x"]},
                   {"from":["ri","value"],"to":["ro","value"]},
                   {"from":["b","y"],"to":["c","value"]}]}"""
        val resp = post(server, "/api/lcnc/run", """{"name":"ring:n7","document":$doc,"inputs":{"text":"hi"}}""")
        assertEquals(200, resp.status, resp.body)
        val body = json(resp)
        assertEquals(true, body["ok"])
        assertEquals("ring:n7", body["program"], "the inline label rides back for the canvas to reconcile")
        assertEquals("HI", (body["returns"] as Map<*, *>)["result"], "the yield climbed out: $body")
        val outputs = body["outputs"] as Map<*, *>
        assertTrue("b" in outputs, "warm base: direct children paint by id: $outputs")
        assertTrue("r" in outputs, "the nested ring's gathered yield lands on its own id: $outputs")

        // Rings hold daemon-runnable types only: a client-only type FED inside
        // the ring is a loud error on the ring, never a silent flat sweep.
        val bad = post(server, "/api/lcnc/run",
            """{"document":{"nodes":[
                {"id":"a","type":"scope.in","params":{"name":"text","default":"x"}},
                {"id":"d","type":"display"}],
              "wires":[{"from":["a","value"],"to":["d","x"]}]},"inputs":{}}""")
        assertEquals(400, bad.status, bad.body)
        assertTrue("display" in bad.body, "LcncUnknownNodeType surfaces the offending type: ${bad.body}")

        val garbage = post(server, "/api/lcnc/run", """{"document":"not a program"}""")
        assertEquals(400, garbage.status, "a malformed document is a loud 400: ${garbage.body}")
        supervisor.detach("kanban")
    }

    @Test fun inlineDocumentExecutesEffectOnceAndRetainsItsUnsavedVersion() = runBlocking {
        val name = "edited-panel"
        val document = """{"nodes":[
            {"id":"input","type":"scope.in","params":{"name":"text","default":"default"}},
            {"id":"effect","type":"test.effect","params":{"prefix":"unsaved"}},
            {"id":"first","type":"scope.out","params":{"name":"first"}},
            {"id":"second","type":"scope.out","params":{"name":"second"}}],
            "wires":[{"from":["input","value"],"to":["effect","x"]},
                {"from":["effect","value"],"to":["first","value"]},
                {"from":["effect","value"],"to":["second","value"]}],
            "controls":{"humanOversight":true}}"""
        val stored = LcncProgramConfix.fromJson(name, document.replace("\"unsaved\"", "\"stored\""))
        val routes = ModuleRouteRegistry()
        val ctx = newContext(routes, tempDir("inline-receipt"), CasStore.inMemory(), mapOf(name to stored))
        var effects = 0
        ctx.lcncRunners["test.effect"] = LcncNodeRunner { node, inputs ->
            effects++
            mapOf("value" to "${node.params.getValue("prefix")}:${inputs.getValue("x")}")
        }
        val supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())
        val server = JvmKanbanServer(moduleRoutes = routes)
        try {
            val programKey = LcncBlackboard.programKey(name)
            val published = ctx.blackboard.get(programKey)
            val response = post(server, "/api/lcnc/run",
                """{"name":"$name","document":$document,"inputs":{"text":"invoked"}}""")
            assertEquals(200, response.status, response.body)
            val run = json(response)
            assertEquals(true, run["ok"])
            assertEquals("completed", run["status"])
            assertEquals(name, run["program"])
            assertEquals(null, run["programKey"])
            assertEquals(mapOf("text" to "invoked"), run["inputs"])
            assertEquals(mapOf("first" to "unsaved:invoked", "second" to "unsaved:invoked"), run["returns"])
            assertEquals(1, effects, "Two consumers must share the one authored effect invocation")
            assertEquals(1, ctx.blackboard.snapshot().values.keys.count { it.startsWith("lcnc/run/") })

            val frozen = content(server, run["programCid"])
            assertEquals(reify(LcncProgramConfix.toJson(LcncProgramConfix.fromJson(name, document))), frozen)
            assertFalse(run["programCid"] == LcncBlackboard.cidOf(stored))
            assertEquals(published, ctx.blackboard.get(programKey), "Running edits must not publish over the stored program")

            val command = content(server, run["receiptCid"])
            assertEquals("lcnc/run/${run["runId"]}", command["jobId"])
            val receipt = command["lcncRun"] as Map<*, *>
            for (key in listOf("runId", "program", "programKey", "programCid", "inputs", "status", "outputs", "returns", "invocationsCid"))
                assertEquals(run[key], receipt[key], key)
            assertTrue("receiptCid" !in receipt && "timelineRevision" !in receipt && "sequence" !in receipt)
            val evidence = content(server, receipt["invocationsCid"])
            assertEquals(run["runId"], evidence["runId"])
            assertEquals(run["programCid"], evidence["programCid"])
            val calls = (evidence["entries"] as List<*>).map { it as Map<*, *> }
            assertEquals(listOf("input", "effect", "first", "second"), calls.map { it["nodeId"] })
            assertTrue(calls.all { it["status"] == "completed" })
            assertEquals(mapOf("x" to "invoked"), calls.single { it["nodeId"] == "effect" }["inputs"])
            assertEquals(command, content(server, run["receiptCid"]))
            assertEquals(1, effects, "Reading immutable program, receipt and invocation evidence must not execute")
        } finally { supervisor.detach("kanban"); ctx.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

    @Test fun inspectionOnlyInlineDocumentRecordsRefusalBeforeClaimsOrEffects() = runBlocking {
        val routes = ModuleRouteRegistry()
        val ctx = newContext(routes, tempDir("inspection-only"), CasStore.inMemory())
        var effects = 0
        ctx.lcncRunners["test.effect"] = LcncNodeRunner { _, _ -> effects++; emptyMap() }
        val supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())
        val server = JvmKanbanServer(moduleRoutes = routes)
        try {
            val response = post(server, "/api/lcnc/run", """{"name":"inspection","document":{
                "nodes":[{"id":"effect","type":"test.effect"}],"wires":[],
                "controls":{"humanOversight":true,"inspectionOnly":true}},"inputs":{}}""")
            assertEquals(400, response.status, response.body)
            val run = json(response)
            assertEquals(false, run["ok"])
            assertEquals("refused", run["status"])
            assertEquals("admission", run["phase"])
            assertTrue(run["error"].toString().contains("Inspection-only"))
            val receipt = content(server, run["receiptCid"])["lcncRun"] as Map<*, *>
            assertEquals(run["runId"], receipt["runId"])
            assertEquals("refused", receipt["status"])
            assertEquals(null, receipt["programKey"])
            for (key in listOf("claimId", "workgroup", "startedAtMs", "invocationsCid")) assertFalse(receipt.containsKey(key))
            val queued = content(server, receipt["previousReceiptCid"])["lcncRun"] as Map<*, *>
            assertEquals("queued", queued["status"])
            assertEquals(receipt["programCid"], queued["programCid"])
            assertEquals(0, effects)
        } finally { supervisor.detach("kanban"); ctx.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

    @Test fun inlineDocumentRejectsUseBeforeDefinitionWithoutReorderingEffects() = runBlocking {
        val routes = ModuleRouteRegistry()
        val ctx = newContext(routes, tempDir("authored-order"), CasStore.inMemory())
        var effects = 0
        ctx.lcncRunners["test.effect"] = LcncNodeRunner { _, _ -> effects++; emptyMap() }
        val supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())
        val server = JvmKanbanServer(moduleRoutes = routes)
        try {
            val response = post(server, "/api/lcnc/run", """{"name":"authored-order","document":{
                "nodes":[{"id":"effect","type":"test.effect"},
                    {"id":"input","type":"scope.in","params":{"name":"text"}}],
                "wires":[{"from":["input","value"],"to":["effect","x"]}]},"inputs":{"text":"supplied"}}""")
            assertEquals(400, response.status, response.body)
            val run = json(response)
            assertEquals("failed", run["status"])
            assertEquals("execution", run["phase"])
            assertTrue(run["error"].toString().contains("use before def: effect consumes input"))
            val receipt = content(server, run["receiptCid"])["lcncRun"] as Map<*, *>
            assertEquals(run["error"], receipt["error"])
            val evidence = content(server, receipt["invocationsCid"])
            val call = (evidence["entries"] as List<*>).single() as Map<*, *>
            assertEquals("effect", call["nodeId"])
            assertEquals("failed", call["status"])
            assertEquals(0, effects, "A later input must not cause the service to reorder or invoke the effect")
        } finally { supervisor.detach("kanban"); ctx.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

    @Test
    fun offeredScopePresetRunsThroughTheDefaultPresetLoader(): Unit = runBlocking {
        // The default ModuleContext.programLoader resolves presets only —
        // Map and named scope bindings need no registered runners.
        val routes = ModuleRouteRegistry()
        val server = JvmKanbanServer(moduleRoutes = routes)
        val supervisor = ModuleSupervisor(newContext(routes, tempDir("preset"), CasStore.inMemory()))
        supervisor.attach(KanbanModule())

        val resp = post(server, "/api/lcnc/run", """{"program":"preset-scope"}""")
        assertEquals(200, resp.status, resp.body)
        val body = json(resp)
        val returns = body["returns"] as Map<*, *>
        assertEquals("hello", returns["result"],
            "the root default reached two rings deep and climbed all the way back out: $body")
        assertTrue((body["outputs"] as Map<*, *>).isNotEmpty(), "the per-node map is served beside the returns")
        supervisor.detach("kanban")
    }

    @Test fun inlineValidationCannotBorrowANamedProgramsCleanEntry() = runBlocking {
        val routes = ModuleRouteRegistry()
        val server = JvmKanbanServer(moduleRoutes = routes)
        val ctx = newContext(routes, tempDir("inline-validation"), CasStore.inMemory())
        val supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())
        try {
            val named = ctx.blackboard.get("lcnc/program/preset-scope")
            val response = post(server, "/api/lcnc/run", """{"name":"preset-scope","document":{"nodes":[
                {"id":"a","type":"scope.in","params":{"name":"x","default":"hello"}},
                {"id":"b","type":"scope.out","params":{"name":"x"}}],
                "wires":[{"from":["a","missing"],"to":["b","value"]}]}}""")
            assertEquals(400, response.status, response.body)
            val body = json(response)
            assertEquals("refused", body["status"])
            assertEquals("validation", body["phase"])
            assertEquals(null, body["programKey"])
            assertEquals(named, ctx.blackboard.get("lcnc/program/preset-scope"))
        } finally { supervisor.detach("kanban") }
    }

    @Test fun timeoutWorkLimitAndCancellationHaveDistinctDurableOutcomes() = runBlocking {
        val routes = ModuleRouteRegistry()
        val server = JvmKanbanServer(moduleRoutes = routes)
        val ctx = newContext(routes, tempDir("budgets"), CasStore.inMemory())
        ctx.lcncRunners["test.slow"] = LcncNodeRunner { _, _ -> delay(10000); emptyMap() }
        val supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())
        try {
            val timeout = post(server, "/api/lcnc/run", """{"document":{"nodes":[{"id":"n","type":"test.slow"}],"wires":[]},"timeoutMs":10}""")
            assertEquals(504, timeout.status, timeout.body)
            assertEquals("timed_out", json(timeout)["status"])
            val work = post(server, "/api/lcnc/run", """{"program":"preset-scope","maxNodes":1}""")
            assertEquals("failed", json(work)["status"])
            assertTrue(work.body.contains("work_limit"))
            val pending = async { post(server, "/api/lcnc/run", """{"name":"cancel-check","document":{"nodes":[{"id":"n","type":"test.slow"}],"wires":[]}}""") }
            val runId = withTimeout(5000) {
                var id: String? = null
                while (id == null) {
                    id = ctx.blackboard.snapshot().values.values.mapNotNull { it as? Map<*, *> }
                        .firstOrNull { it["program"] == "cancel-check" && it["status"] == "running" }?.get("runId")?.toString()
                    if (id == null) delay(10)
                }
                id
            }
            assertEquals(202, post(server, "/api/lcnc/run/cancel", """{"runId":"$runId"}""").status)
            val cancelled = withTimeout(5000) { pending.await() }
            assertEquals(499, cancelled.status, cancelled.body)
            val body = json(cancelled)
            assertEquals("cancelled", body["status"])
            val raw = reify(ctx.casStore.get(borg.trikeshed.job.ContentId(body["receiptCid"].toString()))!!.decodeToString()) as Map<*, *>
            assertEquals("cancelled", (raw["lcncRun"] as Map<*, *>)["status"])
            assertEquals(body["invocationsCid"], (raw["lcncRun"] as Map<*, *>)["invocationsCid"])
            val evidence = reify(ctx.casStore.get(borg.trikeshed.job.ContentId(body["invocationsCid"].toString()))!!.decodeToString()) as Map<*, *>
            assertTrue((evidence["entries"] as List<*>).all { (it as Map<*, *>)["status"] != "running" })
        } finally { supervisor.detach("kanban") }
    }

    @Test fun drainWaitsForEveryAdmittedRunAndEveryDrainCaller() = runBlocking {
        val dir = tempDir("drain")
        val cas = CasStore.inMemory()
        val ctx = newContext(ModuleRouteRegistry(), dir, cas)
        val entered = List(3) { CompletableDeferred<Unit>() }
        val released = List(3) { CompletableDeferred<Unit>() }
        ctx.lcncRunners["test.held"] = LcncNodeRunner { node, _ ->
            val slot = node.params.getValue("slot").toInt()
            entered[slot].complete(Unit)
            released[slot].await()
            mapOf("finished" to slot)
        }
        val programs = List(3) { slot -> program("held-$slot", listOf(
            LcncNode("work", "test.held", mapOf("slot" to slot.toString())),
        ), emptyList()) }
        val store = borg.trikeshed.kanban.BoardStoreElement(borg.trikeshed.kanban.JvmBoardWal(dir), cas)
        store.open()
        val service = LcncRunService(ctx, store) { emptyMap() }
        try {
            val runs = programs.map { p -> async { service.execute(p.name, p, false, emptyMap(), emptyMap<String, Any?>()) } }
            withTimeout(5000) { entered.forEach { it.await() } }
            assertEquals(429, service.execute("full", programs[0], false, emptyMap(), emptyMap<String, Any?>()).status)
            val firstDrain = async(start = CoroutineStart.UNDISPATCHED) { service.drain() }
            val secondDrain = async(start = CoroutineStart.UNDISPATCHED) { service.drain() }
            assertFalse(firstDrain.isCompleted)
            assertFalse(secondDrain.isCompleted)
            assertEquals(429, service.execute("late", programs[0], false, emptyMap(), emptyMap<String, Any?>()).status)
            firstDrain.cancel()
            released[0].complete(Unit)
            val first = withTimeout(5000) { runs[0].await() }
            assertEquals(200, first.status, first.body)
            assertEquals("completed", json(first)["status"])
            assertFalse(firstDrain.isCompleted)
            assertFalse(secondDrain.isCompleted)
            released.drop(1).forEach { it.complete(Unit) }
            withTimeout(5000) {
                for (run in runs.drop(1)) {
                    val result = run.await()
                    assertEquals(200, result.status, result.body)
                    assertEquals("completed", json(result)["status"])
                }
                firstDrain.join()
                secondDrain.await()
                service.drain()
            }
            assertEquals(429, service.execute("closed", programs[0], false, emptyMap(), emptyMap<String, Any?>()).status)
        } finally {
            released.forEach { it.complete(Unit) }
            service.drain()
            store.drain()
            ctx.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test fun receiptsRebuildFromTheExistingWalAfterModuleRestart() = runBlocking {
        val dir = tempDir("restart")
        val cas = CasStore.inMemory()
        val routes = ModuleRouteRegistry()
        val ctx = newContext(routes, dir, cas)
        val supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())
        val response = post(JvmKanbanServer(moduleRoutes = routes), "/api/lcnc/run", """{"program":"preset-scope"}""")
        assertEquals(200, response.status, response.body)
        val original = json(response)
        supervisor.detach("kanban")
        val restarted = newContext(ModuleRouteRegistry(), dir, cas)
        val next = ModuleSupervisor(restarted)
        next.attach(KanbanModule())
        try {
            val recovered = restarted.blackboard.get("lcnc/run/${original["runId"]}") as Map<*, *>
            assertEquals(original["programCid"], recovered["programCid"])
            assertEquals(original["receiptCid"], recovered["receiptCid"])
            assertEquals("completed", recovered["status"])
            assertEquals(original["returns"], recovered["returns"])
            assertEquals(original["invocationsCid"], recovered["invocationsCid"])
            val evidence = reify(cas.get(borg.trikeshed.job.ContentId(recovered["invocationsCid"].toString()))!!.decodeToString()) as Map<*, *>
            assertEquals(original["runId"], evidence["runId"])
            assertTrue((evidence["entries"] as List<*>).isNotEmpty())
        } finally { next.detach("kanban") }
    }

    @Test fun immutableVersionsAndReceiptLineageUseTheExecutionStore() = runBlocking {
        val cas = CasStore.inMemory()
        val routes = ModuleRouteRegistry()
        val ctx = newContext(routes, tempDir("content"), cas)
        val supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())
        val server = JvmKanbanServer(moduleRoutes = routes)
        suspend fun get(query: String) = server.routeHttp("GET /api/lcnc/content?$query HTTP/1.1\r\nHost: t\r\n\r\n".encodeToByteArray())
        try {
            // Catalog examples are not installed at boot. Explicitly publish this unexecuted
            // fixture so the content route can locate it without inventing durable CAS bytes.
            assertEquals(null, ctx.blackboard.get("lcnc/program/preset-scope-inner"))
            borg.trikeshed.lcnc.LcncPublisher(ctx.blackboard, { ctx.lcncRunners }, ctx.attachments)
                .publishProgram("preset-scope-inner", ctx.programLoader("preset-scope-inner")!!)
            val entry = ctx.blackboard.get("lcnc/program/preset-scope-inner") as Map<*, *>
            val candidate = get("cid=${entry["programCid"]}&key=lcnc/program/preset-scope-inner")
            assertEquals(200, candidate.status, candidate.body)
            assertEquals(entry["programCid"], borg.trikeshed.job.ContentId.of(candidate.body.encodeToByteArray()).value)
            assertEquals(409, get("cid=${borg.trikeshed.job.ContentId.of("different".encodeToByteArray()).value}&key=lcnc/program/preset-scope-inner").status)
            val run = post(server, "/api/lcnc/run", """{"program":"preset-scope"}""")
            assertEquals(200, run.status, run.body)
            val receipt = json(run)
            val cid = receipt["programCid"].toString()
            val content = get("cid=$cid")
            assertEquals(200, content.status)
            assertEquals(cid, borg.trikeshed.job.ContentId.of(content.body.encodeToByteArray()).value)
            val sheets = get("cid=$cid&view=sheet")
            assertEquals(200, sheets.status, sheets.body)
            val family = reify(sheets.body) as List<*>
            assertEquals(cid, (family.first() as Map<*, *>)["id"])
            assertEquals("root-at-queue,subprogram-at-first-use", receipt["versionPolicy"])
            val stored = json(get("cid=${receipt["receiptCid"]}"))["lcncRun"] as Map<*, *>
            assertTrue("receiptCid" !in stored && "sequence" !in stored && "timelineRevision" !in stored)
            val previous = json(get("cid=${stored["previousReceiptCid"]}"))["lcncRun"] as Map<*, *>
            assertEquals("running", previous["status"])
            assertEquals(400, get("cid=invalid").status)
            assertEquals(404, get("cid=${borg.trikeshed.job.ContentId.of("missing".encodeToByteArray()).value}").status)
            assertEquals(400, get("cid=$cid&view=invalid").status)
            val large = cas.put("\"${"x".repeat(1_048_576)}\"".encodeToByteArray())
            assertEquals(413, get("cid=${large.value}").status)
        } finally { supervisor.detach("kanban") }
    }

    @Test fun interruptedRecoveryLinksTheLastDurableReceipt() = runBlocking {
        for (phase in listOf("validating", "running")) {
            val dir = tempDir("interrupted-$phase")
            val cas = CasStore.inMemory()
            val ctx = newContext(ModuleRouteRegistry(), dir, cas)
            val first = borg.trikeshed.kanban.BoardStoreElement(borg.trikeshed.kanban.JvmBoardWal(dir), cas)
            val jobId = "lcnc/run/recover-$phase"
            first.open()
            suspend fun record(op: String, revision: Long, status: String): borg.trikeshed.kanban.BoardApply.Committed {
                val reply = kotlinx.coroutines.CompletableDeferred<borg.trikeshed.kanban.BoardApply>()
                first.intake.send(borg.trikeshed.kanban.BoardIntake(mapOf(
                    "type" to op, "jobId" to jobId, "owner" to "lcnc-runner",
                    "expectedRevision" to revision, "idempotencyKey" to "$jobId-$status",
                    "lcncRun" to mapOf("runId" to "recover-$phase", "program" to "fixture", "status" to status),
                ), reply))
                return reply.await() as borg.trikeshed.kanban.BoardApply.Committed
            }
            var last = record("submit", 0, "validating")
            if (phase == "running") last = record("start", 1, "running")
            first.drain()
            val next = borg.trikeshed.kanban.BoardStoreElement(borg.trikeshed.kanban.JvmBoardWal(dir), cas)
            next.open()
            try {
                LcncRunService(ctx, next) { emptyMap() }.recover()
                val receipt = ctx.blackboard.get(jobId) as Map<*, *>
                assertEquals("interrupted", receipt["status"])
                assertEquals(last.cid.value, receipt["previousReceiptCid"])
                assertEquals(last.revision + 1, (receipt["timelineRevision"] as Number).toLong())
                assertEquals("runtime_restarted", receipt["error"])
            } finally { next.drain() }
        }
    }

    @Test fun durableQueuedBacklogExecutesItsFrozenVersionAfterFreshCasReopen(): Unit = runBlocking {
        val dir = tempDir("queued-backlog")
        val files = JvmFileOperations()
        val packPath = File(dir, "cas").path
        fun version(value: String, subnet: String) = program("queued-version", listOf(
            LcncNode("work", "test.version", mapOf("value" to value)),
            LcncNode("output", "scope.out", mapOf("name" to "value", "kind" to "text")),
        ), listOf(LcncWire("work", "value", "output", "value"))).copy(
            controls = LcncConfixControls(subnet = subnet))
        val original = version("v1", "local.queued")
        val replacement = version("v2", "local.replacement")
        val queuedRefs = mutableListOf<BoardApply.Committed>()
        val count = 70
        val firstCas = PackCasStore(files, packPath)
        val firstCtx = newContext(ModuleRouteRegistry(), dir, firstCas)
        val firstStore = BoardStoreElement(JvmBoardWal(File(dir, ".kanban")), firstCas)
        firstStore.open()
        val firstRuns = LcncRunService(firstCtx, firstStore) { emptyMap() }
        try {
            repeat(count) {
                val prepared = firstRuns.enqueue(original.name, original, true, emptyMap(), emptyMap<String, Any?>())
                assertNull(prepared.a)
                val entry = assertNotNull(prepared.b)
                queuedRefs.add(entry.a)
                val receipt = firstRuns.queuedReceipt(entry)
                assertEquals("queued", receipt["status"])
                assertEquals("DURABLE", receipt["queueDurability"])
                assertFalse(receipt.containsKey("workgroup"))
                assertFalse(receipt.containsKey("startedAtMs"))
            }
        } finally {
            firstRuns.drain()
            firstStore.drain()
            firstCtx.scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
            firstCas.close()
        }
        val refs = queuedRefs.toSeries()
        var executions = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val nextCas = PackCasStore(files, packPath)
        val nextCtx = newContext(ModuleRouteRegistry(), dir, nextCas, mapOf(original.name to replacement))
        nextCtx.blackboard.put(LcncBlackboard.programKey(replacement.name),
            LcncBlackboard.programEntry(replacement.name, replacement, LcncContracts.all().associateBy { it.type }), "test")
        nextCtx.lcncRunners["test.version"] = LcncNodeRunner { node, _ ->
            entered.complete(Unit)
            release.await()
            executions++
            mapOf("value" to node.params.getValue("value"))
        }
        val supervisor = ModuleSupervisor(nextCtx)
        var detached = false
        var completed = false
        val receipts = mutableMapOf<String, Any?>()
        try {
            withTimeout(5_000) { supervisor.attach(KanbanModule()) }
            withTimeout(5_000) { entered.await() }
            assertEquals(0, executions, "Attachment must return before a held recovery backlog completes")
            val draining = async(start = CoroutineStart.UNDISPATCHED) { supervisor.detach("kanban") }
            assertFalse(draining.isCompleted)
            release.complete(Unit)
            assertTrue(withTimeout(30_000) { draining.await() })
            detached = true
            assertEquals(count, executions)
            for (ref in refs.view) {
                val receipt = nextCtx.blackboard.get(ref.jobId) as Map<*, *>
                assertEquals("completed", receipt["status"])
                assertEquals(LcncBlackboard.cidOf(original), receipt["programCid"])
                assertEquals(mapOf("value" to "v1"), receipt["returns"])
                assertEquals("local.queued", receipt["claimedAtSubnet"])
                assertEquals("lcnc/program/${original.name}@${LcncBlackboard.cidOf(original)}", receipt["workgroup"])
                receipts[ref.jobId] = receipt["receiptCid"]
            }
            completed = true
        } finally {
            release.complete(Unit)
            if (!completed) nextCtx.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            if (!detached) supervisor.detach("kanban")
            nextCtx.scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
            nextCas.close()
        }
        val terminalCas = PackCasStore(files, packPath)
        val terminalCtx = newContext(ModuleRouteRegistry(), dir, terminalCas, mapOf(original.name to replacement))
        terminalCtx.lcncRunners["test.version"] = LcncNodeRunner { _, _ -> error("Terminal receipts must not replay effects") }
        val terminalSupervisor = ModuleSupervisor(terminalCtx)
        try {
            withTimeout(5_000) { terminalSupervisor.attach(KanbanModule()) }
            for (ref in refs.view) {
                val receipt = terminalCtx.blackboard.get(ref.jobId) as Map<*, *>
                assertEquals("completed", receipt["status"])
                assertEquals(receipts[ref.jobId], receipt["receiptCid"])
            }
        } finally {
            terminalSupervisor.detach("kanban")
            terminalCtx.scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
            terminalCas.close()
        }
    }


    @Test fun inlineVoteRefusesAnExplicitNullQuorumBeforeDownstreamEffects(): Unit = runBlocking {
        val routes = ModuleRouteRegistry()
        val ctx = newContext(routes, tempDir("inline-null-quorum"), CasStore.inMemory())
        val server = JvmKanbanServer(moduleRoutes = routes)
        val supervisor = ModuleSupervisor(ctx)
        var downstream = 0
        ctx.lcncRunners["test.afterVote"] = LcncNodeRunner { _, inputs ->
            downstream++
            mapOf("accepted" to inputs.getValue("accepted"))
        }
        suspend fun vote(params: Map<String, Any?>): JvmKanbanServer.HttpResponse {
            val document = mapOf("nodes" to listOf(
                mapOf("id" to "ballots", "type" to "scope.in", "params" to mapOf("name" to "ballots", "kind" to "json")),
                mapOf("id" to "vote", "type" to "panel.vote", "params" to params),
                mapOf("id" to "after", "type" to "test.afterVote"),
                mapOf("id" to "result", "type" to "scope.out", "params" to mapOf("name" to "accepted", "kind" to "json")),
            ), "wires" to listOf(
                mapOf("from" to listOf("ballots", "value"), "to" to listOf("vote", "ballots")),
                mapOf("from" to listOf("vote", "accepted"), "to" to listOf("after", "accepted")),
                mapOf("from" to listOf("after", "accepted"), "to" to listOf("result", "value")),
            ))
            return post(server, "/api/lcnc/run", jsonOf(mapOf("name" to "quorum-document", "document" to document,
                "inputs" to mapOf("ballots" to quorumBallots()))))
        }
        try {
            supervisor.attach(KanbanModule())
            for ((params, accepted) in quorumControls().view) {
                val before = downstream
                val response = vote(params)
                assertEquals(200, response.status, response.body)
                val run = json(response)
                assertEquals(true, run["ok"])
                assertEquals("completed", run["status"])
                assertEquals(accepted, (run["returns"] as Map<*, *>)["accepted"])
                assertEquals(expectedQuorumVote(accepted), (run["outputs"] as Map<*, *>)["vote"])
                assertEquals(before + 1, downstream, "Each valid vote reaches its downstream effect once")
            }
            assertEquals(4, downstream)
            val refused = vote(mapOf("quorum" to null))
            assertEquals(400, refused.status, "An explicitly null declared quorum must refuse: ${refused.body}")
            assertEquals(4, downstream, "A malformed quorum cannot run a downstream effect: ${refused.body}")
        } finally {
            try { supervisor.detach("kanban") }
            finally { ctx.scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin() }
        }
    }

    @Test fun singleNodeVoteRefusesAnExplicitNullQuorum(): Unit = runBlocking {
        val routes = ModuleRouteRegistry()
        val ctx = newContext(routes, tempDir("node-null-quorum"), CasStore.inMemory())
        val server = JvmKanbanServer(moduleRoutes = routes)
        val supervisor = ModuleSupervisor(ctx)
        suspend fun vote(params: Map<String, Any?>) = post(server, "/api/lcnc/run", jsonOf(mapOf(
            "type" to "panel.vote", "params" to params, "inputs" to mapOf("ballots" to quorumBallots()))))
        try {
            supervisor.attach(KanbanModule())
            for ((params, accepted) in quorumControls().view) {
                val response = vote(params)
                assertEquals(200, response.status, response.body)
                val result = json(response)
                assertEquals(true, result["ok"])
                assertEquals("panel.vote", result["type"])
                assertEquals(expectedQuorumVote(accepted), result["outputs"])
            }
            val refused = vote(mapOf("quorum" to null))
            assertEquals(400, refused.status, "An explicitly null declared quorum must refuse: ${refused.body}")
        } finally {
            try { supervisor.detach("kanban") }
            finally { ctx.scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin() }
        }
    }

    private fun quorumControls(): Series<Join<Map<String, Any?>, Boolean>> = s_[
        emptyMap<String, Any?>() j true,
        mapOf<String, Any?>("quorum" to " \t ") j true,
        mapOf<String, Any?>("quorum" to 3) j false,
        mapOf<String, Any?>("quorum" to "3") j false,
    ]

    private fun quorumBallots(): List<Map<String, Any?>> = listOf(
        mapOf("seat" to "review-a", "role" to "reviewer", "model" to null, "ok" to true,
            "content" to "explicit approval a", "approve" to true, "error" to null, "weight" to 1.0),
        mapOf("seat" to "review-b", "role" to "reviewer", "model" to null, "ok" to true,
            "content" to "explicit approval b", "approve" to true, "error" to null, "weight" to 1.0),
        mapOf("seat" to "review-c", "role" to "reviewer", "model" to null, "ok" to true,
            "content" to "explicit dissent", "approve" to false, "error" to null, "weight" to 1.0),
    )

    private fun expectedQuorumVote(accepted: Boolean): Map<String, Any?> = mapOf(
        "verdict" to if (accepted) "ACCEPTED" else "REJECTED", "accepted" to accepted,
        "tie" to false, "triage" to if (accepted) "advance" else "abort",
        "tally" to mapOf("approve" to 2.0, "reject" to 1.0, "abstain" to 0.0),
        "dissent" to if (accepted) listOf("explicit dissent") else listOf("explicit approval a", "explicit approval b"),
        "transcript" to quorumBallots(), "seats" to 3.0,
        "quorum" to if (accepted) 1.5000000000000002 else 3.0, "quorumMet" to accepted,
    )

}
