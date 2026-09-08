package borg.trikeshed.kanban.module

import borg.trikeshed.couch.Couch
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.dag.ReteProductionRegistry
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.CasStore
import borg.trikeshed.lcnc.InMemoryPromptReads
import borg.trikeshed.lcnc.LcncContracts
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lcnc.LcncProgram
import borg.trikeshed.lcnc.LcncPromptSeeds
import borg.trikeshed.lcnc.LcncWire
import borg.trikeshed.lcnc.PromptNodes
import borg.trikeshed.lib.toSeries
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.module.ModuleRouteRegistry
import borg.trikeshed.module.ModuleSupervisor
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A run names its prompts the way it names its program: the completed receipt
 * carries `promptVersions` — every stored prompt a `prompt.get` read, by name
 * and cid — on the blackboard entry and in the durable payload alike.
 */
class PromptProvenanceReceiptTest {

    @Test
    fun theCompletedReceiptRecordsWhichPromptVersionRan(): Unit = runBlocking {
        val seed = LcncPromptSeeds.byName(LcncPromptSeeds.HELLO)!!
        val reads = InMemoryPromptReads().apply { put(seed) }
        val program = LcncProgram(
            "ask-hello",
            listOf(
                LcncNode("pr", PromptNodes.GET, params = mapOf("name" to LcncPromptSeeds.HELLO)),
                LcncNode("ask", "prompt.chat"),
                LcncNode("out", LcncContracts.SCOPE_OUT, params = mapOf("name" to "answer")),
            ).toSeries(),
            listOf(LcncWire("pr", "text", "ask", "prompt?"), LcncWire("ask", "content", "out", "value")).toSeries(),
        )
        val routes = ModuleRouteRegistry()
        val server = JvmKanbanServer(moduleRoutes = routes)
        val cas = CasStore.inMemory()
        val couchStore = CouchStoreFactory.casBacked(cas)
        val ctx = ModuleContext(
            couch = Couch("prompt-provenance", couchStore, cas),
            rete = ReteNetwork(), productions = ReteProductionRegistry(),
            beliefBag = null, turnReview = null,
            blackboard = ConfixBlackboard.empty(), casStore = cas,
            attachments = CouchAttachmentGateway(couchStore, cas), routes = routes,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default), clock = { 5L },
            stateDir = File(System.getProperty("java.io.tmpdir"), "prompt-provenance-${System.nanoTime()}").apply { mkdirs() },
            programLoader = { name -> if (name == program.name) program else null },
        )
        ctx.lcncRunners.putAll(PromptNodes.registry(reads))
        // A stub model: echoes what reached it on the wire — the wire is keyed by its literal to-port.
        ctx.lcncRunners["prompt.chat"] = LcncNodeRunner { _, inputs ->
            mapOf("content" to "echo: " + (inputs["prompt?"] ?: inputs["prompt"] ?: ""), "model" to "stub", "ok" to true, "error" to "", "cached" to false)
        }
        val supervisor = ModuleSupervisor(ctx)
        supervisor.attach(KanbanModule())
        try {
            val response = server.routeHttp(
                "POST /api/lcnc/run HTTP/1.1\r\nHost: t\r\nContent-Type: application/json\r\n\r\n{\"program\":\"ask-hello\"}".toByteArray(StandardCharsets.UTF_8),
            )
            assertEquals(200, response.status, response.body)
            @Suppress("UNCHECKED_CAST")
            val result = JsonSupport.parse(response.body) as Map<String, Any?>
            assertEquals("echo: " + seed.text, (result["returns"] as Map<*, *>)["answer"])
            assertEquals(mapOf(LcncPromptSeeds.HELLO to seed.cid), result["promptVersions"])
            val receipt = ctx.blackboard.get("lcnc/run/${result["runId"]}") as Map<*, *>
            assertEquals(mapOf(LcncPromptSeeds.HELLO to seed.cid), receipt["promptVersions"])
            assertEquals("completed", receipt["status"])
        } finally {
            supervisor.drainAll()
            ctx.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }
}
