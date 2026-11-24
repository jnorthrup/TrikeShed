package borg.trikeshed.narsese

import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.nlp.NlpDependency
import borg.trikeshed.nlp.NlpDocument
import borg.trikeshed.nlp.NlpSentence
import borg.trikeshed.nlp.NlpToken
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IngestStagesTest {
    /** A parse written out by hand: sentences of `word/lemma/TAG` tokens and `governor>dependent>relation` edges, token 1 first. */
    private fun parsed(vararg sentences: Pair<String, String>, edges: Boolean = true): NlpDocument {
        val text = sentences.joinToString(" ") { s -> s.first.split(' ').joinToString(" ") { it.substringBefore('/') } + "." }
        var at = 0
        return NlpDocument(text, sentences.mapIndexed { index, (words, drawn) ->
            val begin = at
            val tokens = words.split(' ').mapIndexed { i, t ->
                val (word, lemma, tag) = t.split('/')
                NlpToken(i + 1, at, at + word.length, word, lemma, tag, "O").also { at += word.length + 1 }
            }
            at += 1
            NlpSentence(index, begin, at - 1, tokens.toSeries(),
                (if (edges) drawn.split(' ').map { e -> e.split('>').let { NlpDependency(it[0].toInt(), it[1].toInt(), it[2]) } } else emptyList()).toSeries())
        }.toSeries())
    }

    private val returns = "The/the/DT bailee/bailee/NN must/must/MD return/return/VB the/the/DT goods/goods/NNS" to "0>4>root 4>2>nsubj 4>3>aux 2>1>det 4>6>obj 6>5>det"
    private val holds = "A/a/DT bailee/bailee/NN holds/hold/VBZ the/the/DT goods/goods/NNS" to "0>3>root 3>2>nsubj 2>1>det 3>5>obj 5>4>det"
    private val money = "The/the/DT bailee/bailee/NN must/must/MD return/return/VB the/the/DT money/money/NN" to returns.second
    private val reversed = "The/the/DT goods/goods/NNS must/must/MD return/return/VB the/the/DT bailee/bailee/NN" to returns.second
    private val forbidden = "The/the/DT bailee/bailee/NN must/must/MD not/not/RB return/return/VB the/the/DT goods/goods/NNS" to "0>5>root 5>2>nsubj 5>3>aux 5>4>advmod 2>1>det 5>7>obj 7>6>det"

    private val asked = ArrayList<String>()
    private val read: suspend (String, List<String>) -> List<NlpDocument> = { parser, texts ->
        asked.add(parser)
        val readings = listOf(parsed(returns, holds, edges = parser == "a"), parsed(returns, edges = parser == "a"), parsed(holds, edges = parser == "a"),
            parsed(money, edges = parser == "a"), parsed(reversed, edges = parser == "a"), parsed(forbidden, edges = parser == "a")).associateBy { it.text }
        texts.map { readings[it] ?: error("no reading of '$it'") }
    }
    private val passage = parsed(returns, holds).text

    private fun row(board: Any?, claim: String) = (board as List<*>).map { it as Map<*, *> }.single { it["claim"] == claim }
    private fun stage(row: Map<*, *>, name: String) = (row["stages"] as Map<*, *>)[name] as Map<*, *>

    @Test fun eachClaimIsBlamedOnTheFirstStageThatLosesIt() = runTest {
        val expected = listOf(Expectation("person", parsed(returns).text), Expectation("person", parsed(money).text), Expectation("person", parsed(reversed).text),
            Expectation("person", parsed(holds).text), Expectation("model", parsed(forbidden).text))
        val out = IngestStages.run(passage, expected, emptySet(), listOf("a", "b"), read)
        assertEquals(listOf("a", "b"), asked, "each parser reads the passage and the claims once")

        val found = row(out["board"], parsed(returns).text)
        assertNull(found["blame"])
        assertTrue(IngestStages.STAGES.all { stage(found, it)["pass"] == true })
        assertEquals(listOf("must|bailee|return|goods|"), stage(found, IngestStages.JOIN)["restated"])

        val absent = row(out["board"], parsed(money).text)
        assertEquals(IngestStages.TOKEN, absent["blame"])
        assertEquals(listOf("money"), stage(absent, IngestStages.TOKEN)["lacking"])

        val turned = row(out["board"], parsed(reversed).text)
        assertEquals(IngestStages.DEPENDENCY, turned["blame"], "the words are there, related the other way")
        assertEquals(listOf("return nsubj goods", "return obj bailee"), stage(turned, IngestStages.DEPENDENCY)["lacking"])
        assertEquals(emptyList<String>(), turned["further"], "a parser that draws nothing does not get it further")

        val present = row(out["board"], parsed(holds).text)
        assertEquals(IngestStages.CLAUSE, present["blame"], "a present tense states no rule until its subject is generic")
        assertEquals(emptyList<Any?>(), stage(present, IngestStages.CLAUSE)["claim"])
        assertEquals(listOf("generic: bailee, goods"), present["further"])
        assertEquals(mapOf("generic: bailee, goods" to "none", "parser: b" to IngestStages.DEPENDENCY), present["variants"])

        val denied = row(out["board"], parsed(forbidden).text)
        assertEquals(IngestStages.STATEMENT, denied["blame"], "the clause is the passage's, the force is not")
        assertEquals(listOf("must-not|bailee|return|goods|"), stage(denied, IngestStages.STATEMENT)["opposed"])
        assertEquals(listOf(listOf("must|bailee|return|goods|", "must-not|bailee|return|goods|")), stage(denied, IngestStages.JOIN)["conflicts"])
        assertEquals(listOf("opposes a person's claim"), denied["against"], "a model's claim is held against the persons'")
        assertNull(found["against"])

        assertEquals(mapOf("none" to listOf(parsed(returns).text), IngestStages.TOKEN to listOf(parsed(money).text), IngestStages.DEPENDENCY to listOf(parsed(reversed).text),
            IngestStages.CLAUSE to listOf(parsed(holds).text), IngestStages.STATEMENT to listOf(parsed(forbidden).text)), out["blame"])
        assertEquals(listOf("must|bailee|return|goods|"), (out["passage"] as Map<*, *>)["statements"], "the passage's own reading, under the first parser and no generic subject")
    }

    @Test fun theNodeTakesThePassageAndTheRowsAndRefusesARunWithNothingToFind() = runTest {
        val runner = IngestStages.registry(read).getValue(IngestStages.TYPE)
        val rows = listOf(mapOf("author" to "person", "standing" to "truth", "claim" to parsed(returns).text))
        val out = runner.run(LcncNode("stages", IngestStages.TYPE, _m["parsers" j "a"]), _m["text" j passage, "expectations" j rows])
        assertEquals(mapOf("none" to listOf(parsed(returns).text)), out["blame"])
        assertEquals(listOf("a"), asked)
        assertFailsWith<IllegalArgumentException> { runner.run(LcncNode("stages", IngestStages.TYPE, _m["parsers" j "a"]), _m["text" j passage]) }
        assertFailsWith<IllegalArgumentException> { runner.run(LcncNode("stages", IngestStages.TYPE, _m["parsers" j "a"]), _m["text" j " ", "expectations" j rows]) }
    }

    @Test fun thePrefabIsOfferedTypedAndKeyed() {
        val document = LcncPresets.all().getValue(IngestStages.NAME)
        val program = LcncProgramConfix.fromJson(IngestStages.NAME, document)
        assertEquals(document, LcncProgramConfix.toJson(program))
        assertTrue(LcncTypeCheck.check(program, LcncContracts.all().associateBy { it.type }, strict = true).isEmpty())
        assertEquals(IngestStages.info, LcncPresets.info(IngestStages.NAME))
        assertSame(LcncNodeKey.INGEST_STAGES, LcncNodeKey.of(IngestStages.TYPE))
        assertSame(LcncNodeKey.PREREQUISITE_PASSAGE, LcncNodeKey.of(BookReview.PASSAGE))
        assertNotNull(LcncContracts.find(BookReview.PASSAGE))
    }
}
