package borg.trikeshed.lcnc

import borg.trikeshed.lib.toSeries
import borg.trikeshed.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The prompt legos over the in-memory reads seam, and their contracts. */
class PromptNodesTest {

    private fun reads(): InMemoryPromptReads = InMemoryPromptReads { 42L }.apply {
        put(PromptDocument("hello", "Say hello in one sentence.", tags = listOf("preset-brain-mux")))
        put(PromptDocument("greet", "Greet {{who}} in {{lang}}.", role = PromptDocument.ROLE_SYSTEM))
    }

    @Test
    fun everyServedTypeHasAContractAndEveryPromptContractHasARunner() {
        val registry = PromptNodes.registry(reads())
        val contracts = LcncContracts.all().associateBy { it.type }
        for (type in PromptNodes.servedTypes()) {
            assertTrue(type in contracts, "served type $type has no contract")
            assertTrue(type in registry, "served type $type has no runner")
        }
        // prompt.save is the write and is registered by the store, not the reads seam.
        assertTrue(PromptNodes.SAVE in contracts)
        assertTrue(PromptNodes.SAVE !in registry)
    }

    @Test
    fun getHonoursTheWireOverTheParamAndIsLoudWhenNothingNames() = runBlocking {
        val get = PromptNodes.registry(reads()).getValue(PromptNodes.GET)
        val byParam = get.run(LcncNode("g", PromptNodes.GET, params = mapOf("name" to "hello")), emptyMap())
        assertEquals("Say hello in one sentence.", byParam["text"])
        assertEquals("hello", byParam["name"])
        assertEquals(PromptDocument.ROLE_USER, byParam["role"])
        assertEquals(PromptDocument("hello", "Say hello in one sentence.", tags = listOf("preset-brain-mux")).cid, byParam["cid"])
        val byWire = get.run(LcncNode("g", PromptNodes.GET, params = mapOf("name" to "hello")), mapOf("name?" to "greet"))
        assertEquals("greet", byWire["name"])
        assertFailsWith<IllegalArgumentException> { get.run(LcncNode("g", PromptNodes.GET), emptyMap()) }
        assertFailsWith<IllegalArgumentException> { get.run(LcncNode("g", PromptNodes.GET, params = mapOf("name" to "nosuch")), emptyMap()) }
    }

    @Test
    fun renderFillsHolesFromAJsonArgsMap() = runBlocking {
        val render = PromptNodes.registry(reads()).getValue(PromptNodes.RENDER)
        val out = render.run(LcncNode("r", PromptNodes.RENDER), mapOf("template" to "Greet {{who}} in {{lang}}.", "args?" to mapOf("who" to "Jim", "lang" to "Kotlin")))
        assertEquals("Greet Jim in Kotlin.", out["text"])
        assertEquals(listOf("who", "lang"), out["variables"])
    }

    @Test
    fun listReportsTheHeads() = runBlocking {
        val out = PromptNodes.registry(reads()).getValue(PromptNodes.LIST).run(LcncNode("l", PromptNodes.LIST), emptyMap())
        assertEquals(2, out["count"])
        val names = (out["prompts"] as List<*>).map { (it as Map<*, *>)["name"] }
        assertEquals(listOf("hello", "greet"), names)
    }

    @Test
    fun aStoredPromptCablesIntoAskAModelWithoutAViolation() {
        val program = LcncProgram(
            "prompt-to-chat",
            listOf(
                LcncNode("pr", PromptNodes.GET, params = mapOf("name" to "hello")),
                LcncNode("ask", "prompt.chat"),
            ).toSeries(),
            listOf(LcncWire("pr", "text", "ask", "prompt?")).toSeries(),
        )
        assertEquals(emptyList(), LcncTypeCheck.check(program))
        assertEquals(listOf("text"), LcncTypeCheck.cableTypes(program))
    }

    @Test
    fun promptVersionsAreCollectedFromRecordedOutputsRingsIncluded() {
        val inner = LcncNode("pr-inner", PromptNodes.GET, params = mapOf("name" to "greet"))
        val ring = LcncNode("ring", LcncContracts.SCOPE, children = listOf(inner).toSeries())
        val program = LcncProgram("p", listOf(LcncNode("pr", PromptNodes.GET, params = mapOf("name" to "hello")), ring).toSeries(), emptyList<LcncWire>().toSeries())
        val outputs = mapOf(
            "pr" to mapOf("name" to "hello", "cid" to "sha256:aa"),
            "ring/pr-inner" to mapOf("name" to "greet", "cid" to "sha256:bb"),
            "other" to mapOf("name" to "ignored", "cid" to "sha256:cc"),
        )
        assertEquals(mapOf("hello" to "sha256:aa", "greet" to "sha256:bb"), PromptNodes.promptVersionsOf(listOf(program), outputs))
    }
}
