package borg.trikeshed.lcnc

import borg.trikeshed.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Corpus digest preset, end to end over the in-memory corpus: every
 * matching document is read, the stored prompt is the question, one summary
 * per document comes back, and the digest lists them.
 */
class CorpusPresetExecutionTest {

    private val asked = mutableListOf<String>()

    private fun runners(corpus: ProjectCorpus, prompts: PromptReads): Map<String, LcncNodeRunner> =
        PureNodes.registry { 0L } + ProjectNodes.registry(corpus) + PromptNodes.registry(prompts) + mapOf(
            "note" to LcncNodeRunner { _, _ -> emptyMap() },
            "display" to LcncNodeRunner { _, inputs -> mapOf("shown" to inputs["x"]) },
            "text.fold" to LcncNodeRunner { node, inputs ->
                val brief = (inputs["brief"] ?: inputs["brief?"])?.toString().orEmpty()
                val parts = (inputs["parts"] as? List<*>)?.map { it.toString() } ?: listOfNotNull(inputs["parts"]?.toString())
                mapOf("text" to (listOf(brief) + parts).joinToString(node.params["separator"] ?: "\n\n"))
            },
            "prompt.chat" to LcncNodeRunner { _, inputs ->
                val prompt = (inputs["prompt"] ?: inputs["prompt?"]).toString()
                asked.add(prompt)
                mapOf("content" to "summary of <" + prompt.substringAfterLast("\n\n") + ">", "model" to "stub", "ok" to true, "error" to "", "cached" to false)
            },
        )

    @Test
    fun theDigestSummarisesEveryMatchingDocumentWithTheStoredPrompt() = runBlocking {
        val corpus = InMemoryProjectCorpus().apply {
            put("notes", "a.md", "alpha".encodeToByteArray())
            put("notes", "sub/b.md", "beta".encodeToByteArray())
            put("notes", "c.md", "gamma".encodeToByteArray())
            put("notes", "scan.pdf", byteArrayOf(0x25, 0x50, 0x44, 0x46))
        }
        val seed = LcncPromptSeeds.byName(LcncPromptSeeds.SUMMARIZE)!!
        val prompts = InMemoryPromptReads().apply { put(seed) }
        val program = LcncProgramConfix.fromJson("preset-corpus", LcncPresets.all().getValue("preset-corpus"))
        assertEquals(emptyList(), LcncTypeCheck.check(program), "the preset's cables are exact")
        val ledger = LcncConsumedLedger()
        val res = LcncRunner(runners(corpus, prompts)).apply { this.ledger = ledger }.runProcedure(program, mapOf("project" to "notes"))
        val summaries = res.returns["summaries"] as List<*>
        assertEquals(3, summaries.size, "three Markdown documents, the PDF skipped by the glob")
        assertEquals(listOf("a.md", "c.md", "sub/b.md"), summaries.map { (it as Map<*, *>)["id"] })
        assertEquals("summary of <alpha>", (summaries[0] as Map<*, *>)["summary"])
        val ring = res.nodeOutputs.getValue("n-ring")
        assertEquals(3, ring["count"]); assertEquals(3, (ring["summary"] as List<*>).size)
        assertTrue(asked.all { it.startsWith(seed.text) }, "every question began with the stored prompt: $asked")
        val lines = res.nodeOutputs.getValue("n-lines")["lines"] as List<*>
        assertEquals("a.md: summary of <alpha>", lines[0].toString())
        // Inside a ring, a node's outputs stay in the ring's frame; the ledger is how a receipt
        // names what the run read, at any depth.
        assertEquals(mapOf(LcncPromptSeeds.SUMMARIZE to seed.cid), ledger.promptVersions(), "the receipt names the prompt version read inside the ring")
        val kinds = ledger.entries().groupBy { it.kind }.mapValues { it.value.size }
        assertEquals(mapOf(LcncConsumedLedger.PROJECT_INDEX to 1, LcncConsumedLedger.PROJECT to 3, LcncConsumedLedger.PROMPT to 1), kinds, "the listing, three documents, one prompt")
        assertEquals(res.consumed.size, ledger.entries().size)
        assertEquals(ledger.fingerprint(), res.inputFingerprint)
    }

    @Test
    fun theSameCorpusTwiceYieldsTheSameDigest() = runBlocking {
        val corpus = InMemoryProjectCorpus().apply { put("notes", "a.md", "alpha".encodeToByteArray()) }
        val prompts = InMemoryPromptReads().apply { put(LcncPromptSeeds.byName(LcncPromptSeeds.SUMMARIZE)!!) }
        val program = LcncProgramConfix.fromJson("preset-corpus", LcncPresets.all().getValue("preset-corpus"))
        val a = LcncRunner(runners(corpus, prompts)).apply { ledger = LcncConsumedLedger() }.runProcedure(program, mapOf("project" to "notes"))
        val b = LcncRunner(runners(corpus, prompts)).apply { ledger = LcncConsumedLedger() }.runProcedure(program, mapOf("project" to "notes"))
        assertEquals(a.returns, b.returns)
        assertEquals(a.inputFingerprint, b.inputFingerprint, "same inputs, same fingerprint")
        corpus.put("notes", "a.md", "alpha changed".encodeToByteArray())
        val c = LcncRunner(runners(corpus, prompts)).apply { ledger = LcncConsumedLedger() }.runProcedure(program, mapOf("project" to "notes"))
        assertTrue(c.inputFingerprint != a.inputFingerprint, "a changed document moves the fingerprint")
    }
}
