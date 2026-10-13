package borg.trikeshed.lcnc

import borg.trikeshed.kanban.module.WorkspaceService
import borg.trikeshed.parse.*
import borg.trikeshed.vm.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class WorkspaceServiceTest {
    @Test fun languageConstructionsRequireCapabilitiesAndBothRunnersWithoutExecuting() = runTest {
        val ctx = vmRuntimeTestContext(this)
        val service = WorkspaceService(ctx)
        var executions = 0
        val runner = LcncNodeRunner { _, _ -> executions++; emptyMap() }
        val available = VmLanguageCapability(VmLanguage.JAVA, true, true, "test capability")
        assertNull(service.language(available)["construction"])
        ctx.lcncRunners[SubVmLegos.POLYGLOT] = runner
        assertNull(service.language(available)["construction"])
        ctx.lcncRunners[SubVmLegos.CAMEL] = runner
        val revision = ctx.blackboard.snapshot().revision
        val construction = assertIs<Map<*, *>>(service.language(available)["construction"])
        val name = assertIs<String>(construction["name"])
        val program = LcncProgramConfix.fromJson(name, jsonOf(construction["document"]))
        assertEquals("preset-polyglot-camel-java", name)
        assertTrue(LcncTypeCheck.check(program).isEmpty())
        assertNull(service.language(available.copy(available = false))["construction"])
        assertEquals(revision, ctx.blackboard.snapshot().revision)
        assertEquals(0, executions)
    }

    @Test fun inspectionDoesNotRunSaveOrPublishAndRejectsMalformedWindows() = runTest {
        val ctx = vmRuntimeTestContext(this)
        var executions = 0
        ctx.lcncRunners["text.value"] = LcncNodeRunner { _, _ -> executions++; emptyMap() }
        val service = WorkspaceService(ctx)
        val revision = ctx.blackboard.snapshot().revision
        val request = mapOf("name" to "canvas", "document" to mapOf(
            "nodes" to listOf(mapOf("id" to "n", "type" to "text.value", "params" to mapOf("value" to "雪"))),
            "wires" to emptyList<Any>()), "path" to listOf("nodes", 0, "params"), "limit" to 2)
        val response = service.route("POST", jsonOf(request))
        assertEquals(200, response.status, response.body)
        val confix = (reify(response.body) as Map<*, *>)["confix"] as Map<*, *>
        assertEquals(mapOf("value" to "雪"), confix["value"])
        assertEquals(2, (confix["rows"] as List<*>).size)
        val root = (confix["rows"] as List<*>).first() as Map<*, *>
        val children = assertIs<List<*>>(root["children"])
        assertEquals((root["childCount"] as Number).toInt(), children.size)
        assertTrue(children.isNotEmpty() && children.all { it is Number })
        assertEquals(0, executions)
        assertEquals(revision, ctx.blackboard.snapshot().revision)
        val binding = ((reify(response.body) as Map<*, *>)["bindings"] as List<*>).single() as Map<*, *>
        assertEquals("KOTLIN", binding["binding"])
        assertEquals("LcncNodeKey.TEXT_VALUE", (binding["context"] as Map<*, *>)["key"])
        assertEquals(400, service.route("POST", jsonOf(request + ("offset" to 0.5))).status)
        assertEquals(400, service.route("POST", jsonOf(request + ("path" to "nodes"))).status)
        assertEquals(405, service.route("DELETE", "").status)
        assertEquals(400, service.route("POST", jsonOf(request) + " trailing").status)
        val cursorRequest = request + ("cursor" to mapOf("path" to listOf(0), "axes" to listOf(listOf(2), listOf(0, 1))))
        val indexed = service.route("POST", jsonOf(cursorRequest))
        assertEquals(200, indexed.status, indexed.body)
        val cursor = ((reify(indexed.body) as Map<*, *>)["confix"] as Map<*, *>)["cursor"] as Map<*, *>
        assertEquals(2, (cursor["indexes"] as List<*>).size)
        for (index in cursor["indexes"] as List<*>) {
            for (group in (index as Map<*, *>)["groups"] as List<*>) {
                val ordinals = assertIs<List<*>>((group as Map<*, *>)["ordinals"])
                assertTrue(ordinals.isNotEmpty() && ordinals.size <= 2)
                assertTrue(ordinals.all { it is Number && it.toInt() in 0 until (cursor["rowCount"] as Number).toInt() })
            }
        }
        for (invalid in listOf(mapOf("sourceCid" to "stale"), mapOf("path" to listOf(0.5)),
            mapOf("selections" to listOf(listOf(0))))) {
            assertEquals(400, service.route("POST", jsonOf(request + ("cursor" to invalid))).status)
        }
        assertEquals(0, executions)
        assertEquals(revision, ctx.blackboard.snapshot().revision)
        assertEquals(400, service.route("POST", jsonOf(mapOf("document" to mapOf(
            "nodes" to listOf(mapOf("type" to "text.value")), "wires" to emptyList<Any>())))).status)
    }
}
