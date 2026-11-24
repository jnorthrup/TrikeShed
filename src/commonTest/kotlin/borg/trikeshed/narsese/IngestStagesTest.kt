package borg.trikeshed.narsese

import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.nlp.NlpDependency
import borg.trikeshed.nlp.NlpDocument
import borg.trikeshed.nlp.NlpSentence
import borg.trikeshed.nlp.NlpToken
import borg.trikeshed.rdf.RdfVocab
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

    /** The join's Turtle as the QUAD stage reads it: every statement of the joined constellation typed `forge:Norm`. */
    private fun typedNorms(c: Constellation): String = c.statements.joinToString("\n") { s ->
        "<${RdfVocab.FORGE}constellation/${c.name}/norm/${s.id}> <${RdfVocab.RDF}type> <${RdfVocab.FORGE}Norm> ."
    }

    private fun row(board: Any?, claim: String) = (board as List<*>).map { it as Map<*, *> }.single { it["claim"] == claim }
    private fun stage(row: Map<*, *>, name: String) = (row["stages"] as Map<*, *>)[name] as Map<*, *>

    @Test fun eachClaimIsBlamedOnTheFirstStageThatLosesIt() = runTest {
        val expected = listOf(Expectation("person", parsed(returns).text), Expectation("person", parsed(money).text), Expectation("person", parsed(reversed).text),
            Expectation("person", parsed(holds).text), Expectation("model", parsed(forbidden).text))
        val out = IngestStages.run(passage, expected, emptySet(), listOf("a", "b"), read, emptySet(), { _, _, _ -> -1 }, ::typedNorms)
        assertEquals(listOf("a", "b"), asked, "each parser reads the passage and the claims once")

        val found = row(out["board"], parsed(returns).text)
        assertNull(found["blame"])
        assertTrue(IngestStages.STAGES.all { stage(found, it)["pass"] == true })
        assertEquals(listOf("must|bailee|return|goods|"), stage(found, IngestStages.JOIN)["restated"])
        val belief = (stage(found, IngestStages.JOIN)["truth"] as List<*>).single() as Map<*, *>
        assertEquals("must|bailee|return|goods|", belief["statement"])
        assertEquals(1f, belief["frequency"], "the passage and the claim both state it and nothing opposes it")

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
        val runner = IngestStages.registry(read, { _, _, _ -> -1 }, { _ -> emptySet() }, ::typedNorms).getValue(IngestStages.TYPE)
        val rows = listOf(mapOf("author" to "person", "standing" to "truth", "claim" to parsed(returns).text))
        val out = runner.execute(LcncNode("stages", IngestStages.TYPE, _m["parsers" j "a"]), _m["text" j passage, "expectations" j rows])
        assertEquals(mapOf("none" to listOf(parsed(returns).text)), out["blame"])
        assertEquals(listOf("a"), asked)
        assertFailsWith<IllegalArgumentException> { runner.execute(LcncNode("stages", IngestStages.TYPE, _m["parsers" j "a"]), _m["text" j passage]) }
        assertFailsWith<IllegalArgumentException> { runner.execute(LcncNode("stages", IngestStages.TYPE, _m["parsers" j "a"]), _m["text" j " ", "expectations" j rows]) }
    }

    /** The passage's words are split across two sections: each one is held, but no one section holds all of them. */
    @Test fun sectionStageBlamesAClaimWhoseWordsAreSplitAcrossSections() {
        val source = IngestStages.Reading(listOf("one", "two"), listOf(parsed(holds, edges = false), parsed(money, edges = false)), listOf(emptyList(), emptyList()))
        val claim = IngestStages.Reading(listOf("c"), listOf(parsed(returns, edges = false)), listOf(emptyList()))
        val stages = IngestStages.judge(source, claim, ::typedNorms)
        assertEquals(IngestStages.SECTION, IngestStages.blame(stages))
        assertEquals(listOf("return"), stages.getValue(IngestStages.SECTION)["lacking"], "'return' is in the second section, 'goods' in the first; no section holds the claim's own words together")
        assertEquals(true, stages.getValue(IngestStages.TOKEN)["pass"], "the passage as a whole holds every word")
    }

    /** The words are in the passage and in one section of it, in two sentences: no sentence holds the claim's words together. */
    @Test fun sentenceStageBlamesAClaimWhoseWordsNoOneSentenceHolds() {
        val source = IngestStages.Reading(listOf("one"), listOf(parsed(holds, money, edges = false)), listOf(emptyList()))
        val claim = IngestStages.Reading(listOf("c"), listOf(parsed(returns, edges = false)), listOf(emptyList()))
        val stages = IngestStages.judge(source, claim, ::typedNorms)
        assertEquals(IngestStages.SENTENCE, IngestStages.blame(stages))
        assertEquals(true, stages.getValue(IngestStages.SECTION)["pass"])
        assertEquals(listOf("return"), stages.getValue(IngestStages.SENTENCE)["lacking"], "the first sentence shares bailee and goods, and has no return")
    }

    /** The claim defines a party by a quoted term; the passage does not define it: the party stage lacks it. */
    @Test fun partyStageBlamesAClaimDefiningAPartyTheDocumentDoesNotDefine() {
        val mentioned = parsed("The/the/DT customer/customer/NN holds/hold/VBZ the/the/DT goods/goods/NNS" to "3>2>nsubj 3>5>obj")
        val defining = parsed("You/you/PRP (/(/-LRB- ``/``/`` Customer/customer/NN ''/''/'' )/)/-RRB- hold/hold/VB goods/goods/NNS" to "8>9>obj")
        val source = IngestStages.Reading(listOf("p"), listOf(mentioned), listOf(emptyList()))
        val claim = IngestStages.Reading(listOf("c"), listOf(defining), listOf(emptyList()))
        val party = IngestStages.judge(source, claim, ::typedNorms).getValue(IngestStages.PARTY)
        assertEquals(false, party["pass"])
        assertEquals(listOf("customer"), party["wanted"])
        assertEquals(listOf("customer"), party["lacking"])
    }

    /** The claim's bearer is typed differently by the passage's typer: the bearer stage lacks it. */
    @Test fun bearerStageBlamesAClaimWhoseBearerIsClassedDifferently() {
        val typedAs = { n: Int -> { c: NormClause -> if (c.subject == "bailee") n else -1 } }
        val source = IngestStages.Reading(listOf("p"), listOf(parsed(returns)), listOf(NormClauses.extract(parsed(returns))), typedAs(7))
        val claim = IngestStages.Reading(listOf("c"), listOf(parsed(returns)), listOf(NormClauses.extract(parsed(returns))), typedAs(9))
        val bearer = IngestStages.judge(source, claim, ::typedNorms).getValue(IngestStages.BEARER)
        assertEquals(false, bearer["pass"])
        assertEquals(listOf("bailee=9"), bearer["wanted"])
        assertEquals(listOf("bailee=7"), bearer["lacking"])
        val elsewhere = IngestStages.Reading(listOf("c"), listOf(parsed(money)), listOf(NormClauses.extract(parsed(money))), typedAs(7))
        val unstated = IngestStages.judge(source, elsewhere, ::typedNorms)
        assertEquals(true, unstated.getValue(IngestStages.BEARER)["pass"], "the bearer is classed alike; what the passage does not state is not the typer's miss")
        assertEquals(listOf("must|bailee|return|money|"), unstated.getValue(IngestStages.STATEMENT)["lacking"])
    }

    /** The claim's norm is absent from the join's Turtle (empty here): the quad stage lacks it. */
    @Test fun quadStageBlamesAClaimWhoseNormIsNotInTheJoinsTurtle() {
        val source = IngestStages.Reading(listOf("p"), listOf(parsed(returns)), listOf(NormClauses.extract(parsed(returns))))
        val claim = IngestStages.Reading(listOf("c"), listOf(parsed(returns)), listOf(NormClauses.extract(parsed(returns))))
        val quad = IngestStages.judge(source, claim, { "" }).getValue(IngestStages.QUAD)
        assertEquals(false, quad["pass"])
        assertEquals(listOf("must|bailee|return|goods|"), quad["lacking"])
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

    /** A claim says "you"; the passage defines who "you" is: the claim is read with the passage's parties, and its clause is the passage's. */
    @Test fun aClaimIsReadWithThePartiesThePassageDefines() = runTest {
        val defining = "You/you/PRP (/(/-LRB- ``/``/`` Customer/customer/NN ''/''/'' )/)/-RRB- must/must/MD return/return/VB the/the/DT goods/goods/NNS" to
            "0>8>root 8>1>nsubj 8>7>aux 8>10>obj 10>9>det"
        val you = "You/you/PRP must/must/MD return/return/VB the/the/DT goods/goods/NNS" to "0>3>root 3>1>nsubj 3>2>aux 3>5>obj 5>4>det"
        val they = "They/they/PRP must/must/MD return/return/VB the/the/DT goods/goods/NNS" to you.second
        val docs = listOf(parsed(defining), parsed(you), parsed(they)).associateBy { it.text }
        val reader: suspend (String, List<String>) -> List<NlpDocument> = { _, texts -> texts.map { docs[it] ?: error("no reading of '$it'") } }
        val out = IngestStages.run(parsed(defining).text, listOf(Expectation("person", parsed(you).text), Expectation("person", parsed(they).text)),
            emptySet(), listOf("a"), reader, emptySet(), { _, _, _ -> -1 }, ::typedNorms)
        val bound = stage(row(out["board"], parsed(you).text), IngestStages.CLAUSE)
        assertEquals(true, bound["pass"], "\"you\" is the customer the passage defines")
        assertEquals(listOf("customer"), (bound["claim"] as List<*>).map { (it as Map<*, *>)["subject"] })
        assertEquals(listOf(parsed(defining).text), bound["sentences"], "the passage sentence holding the same clause")
        val unknown = stage(row(out["board"], parsed(they).text), IngestStages.CLAUSE)
        assertEquals(emptyList<Any?>(), unknown["claim"])
        assertEquals(listOf("they"), unknown["unbound"], "no party binds \"they\"")
        assertEquals("The machine read no clause from the claim: it does not know who “they” is.", IngestStages.reason(IngestStages.CLAUSE, unknown, parsed(they).text))
    }

    /** The passage states the claim's rule the other way: the statement stage names the passage sentence that says so. */
    @Test fun theStatementStageNamesThePassageSentenceThatSaysTheOpposite() = runTest {
        val out = IngestStages.run(passage, listOf(Expectation("person", parsed(returns).text), Expectation("model", parsed(forbidden).text)),
            emptySet(), listOf("a"), read, emptySet(), { _, _, _ -> -1 }, ::typedNorms)
        val sentence = "The bailee must return the goods."
        val denied = stage(row(out["board"], parsed(forbidden).text), IngestStages.STATEMENT)
        assertEquals(listOf(sentence), denied["contrary"])
        assertEquals("The passage says the opposite: “The bailee must return the goods.”", IngestStages.reason(IngestStages.STATEMENT, denied, parsed(forbidden).text))
        assertEquals(listOf(sentence), stage(row(out["board"], parsed(returns).text), IngestStages.CLAUSE)["sentences"])
        assertEquals(emptyList<Any?>(), stage(row(out["board"], parsed(returns).text), IngestStages.STATEMENT)["contrary"])
    }

    /** The bearer stage names the categories it compares when the host names them, and adds nothing when it does not. */
    @Test fun theBearerStageNamesTheCategoriesItCompares() {
        val typedAs = { n: Int -> { c: NormClause -> if (c.subject == "bailee") n else -1 } }
        val source = IngestStages.Reading(listOf("p"), listOf(parsed(returns)), listOf(NormClauses.extract(parsed(returns))), typedAs(7))
        val claim = IngestStages.Reading(listOf("c"), listOf(parsed(returns)), listOf(NormClauses.extract(parsed(returns))), typedAs(9))
        val names = mapOf(7 to "Corporation", 9 to "LegalAgent")
        val bearer = IngestStages.judge(source, claim, ::typedNorms) { names[it].orEmpty() }.getValue(IngestStages.BEARER)
        assertEquals(listOf(mapOf("word" to "bailee", "claim" to "LegalAgent", "passage" to "Corporation")), bearer["kinds"])
        assertEquals("The claim puts “bailee” in the category Legal Agent; the passage puts it in Corporation.",
            IngestStages.reason(IngestStages.BEARER, bearer, parsed(returns).text))
        assertNull(IngestStages.judge(source, claim, ::typedNorms).getValue(IngestStages.BEARER)["kinds"], "a host that names no category adds none")
    }

    /** Each stage's one sentence, from evidence written by hand: what it found or lacked, in a person's words. */
    @Test fun eachStageSaysInOneSentenceWhatItFoundOrLacked() {
        fun said(stage: String, claim: String, vararg evidence: Join<String, Any?>) = IngestStages.reason(stage, _m.get(*evidence), claim)
        val title = "Title to Supported Digital Assets shall remain with you."
        val rent = "The bailee must pay rent to the bailor."
        val none = emptyList<String>()
        assertEquals("The passage has every word of the claim: title, supported, digital, assets and remain.",
            said(IngestStages.TOKEN, title, "pass" j true, "wanted" j _l["assets", "digital", "remain", "support", "title"], "lacking" j none))
        assertEquals("The passage never uses the words “pay” and “rent”.",
            said(IngestStages.TOKEN, rent, "pass" j false, "wanted" j _l["bailee", "bailor", "pay", "rent"], "lacking" j _l["pay", "rent"]))
        assertEquals("One section of the passage holds all of these words.", said(IngestStages.SECTION, title, "pass" j true, "lacking" j none))
        assertEquals("No one section holds them all: the section holding the most lacks “bailee”.",
            said(IngestStages.SECTION, rent, "pass" j false, "lacking" j _l["bailee"]))
        val held = "Title to Supported Digital Assets shall at all times remain with you and shall not transfer to Coinbase."
        assertEquals("One sentence holds them all: “$held”", said(IngestStages.SENTENCE, title, "pass" j true, "lacking" j none, "sentence" j held))
        assertEquals("No one sentence holds them all; the closest lacks “bailment”: “A delivery of personal property by one person to another.”",
            said(IngestStages.SENTENCE, "A bailment is a delivery of personal property by one person to another.", "pass" j false, "lacking" j _l["bailment"],
                "sentence" j "A delivery of personal property by one person to another."))
        assertEquals("That sentence joins the words as the claim does: title to remain and assets to title.",
            said(IngestStages.DEPENDENCY, title, "pass" j true, "wanted" j _l["remain nsubj title", "title nmod:to assets"], "lacking" j none))
        assertEquals("That sentence does not join title to change as the claim does.",
            said(IngestStages.DEPENDENCY, "A bailment involves a change in title.", "pass" j false, "wanted" j _l["change nmod:in title"], "lacking" j _l["change nmod:in title"]))
        assertEquals("The claim defines no party of its own.", said(IngestStages.PARTY, title, "pass" j true, "wanted" j none, "lacking" j none))
        assertEquals("The claim defines “customer” as a party; the passage does not.",
            said(IngestStages.PARTY, title, "pass" j false, "wanted" j _l["customer"], "lacking" j _l["customer"]))
        val remain = _m["subject" j "title", "modal" j "shall", "affirmative" j true, "verb" j "remain", "object" j null, "oblique" j "with you", "condition" j null]
        assertEquals("The passage has the same clause: title shall remain with you.", said(IngestStages.CLAUSE, title, "pass" j true, "claim" j _l[remain], "lacking" j none))
        val pay = _m["subject" j "coinbase", "modal" j "must", "affirmative" j true, "verb" j "pay", "object" j "interest", "oblique" j null, "condition" j null]
        assertEquals("The passage has no clause in which coinbase must pay interest.",
            said(IngestStages.CLAUSE, "Coinbase must pay interest.", "pass" j false, "claim" j _l[pay], "lacking" j _l[pay]))
        assertEquals("The machine read no clause from the claim, no one who shall, must or may do something, so it has nothing to look for.",
            said(IngestStages.CLAUSE, "You shall bear all risk of loss.", "pass" j false, "claim" j none, "lacking" j none))
        assertEquals("The claim and the passage put “title” in two different categories.",
            said(IngestStages.BEARER, title, "pass" j false, "wanted" j _l["title=2433"], "lacking" j _l["title=8195"]))
        assertEquals("Nothing the claim names has a category to compare.", said(IngestStages.BEARER, title, "pass" j true, "wanted" j none, "lacking" j none))
        assertEquals("The passage states the claim's rule: title must remain with you.",
            said(IngestStages.STATEMENT, title, "pass" j true, "claim" j _l["must|title|remain|with you|"], "lacking" j none, "opposed" j none))
        val grant = "Coinbase may grant a security interest in the Supported Digital Assets."
        assertEquals("The passage says the opposite: Coinbase may NOT grant a security interest in the Supported Digital Assets.",
            said(IngestStages.STATEMENT, grant, "pass" j false, "claim" j _l["may|coinbase|grant|security interest|"],
                "lacking" j _l["may|coinbase|grant|security interest|"], "opposed" j _l["may|coinbase|grant|security interest|"]))
        assertEquals("The passage does not state that coinbase must pay interest.",
            said(IngestStages.STATEMENT, "Coinbase must pay interest.", "pass" j false, "claim" j _l["must|coinbase|pay|interest|"],
                "lacking" j _l["must|coinbase|pay|interest|"], "opposed" j none))
        assertEquals("Read together, the passage restates the claim's rule; the machine believes it with frequency 1.00 and confidence 0.67.",
            said(IngestStages.JOIN, title, "pass" j true, "restated" j _l["must|title|remain|with you|"],
                "truth" j _l[_m["statement" j "must|title|remain|with you|", "frequency" j 1.0, "confidence" j 0.6666667]]))
        assertEquals("Read together, the passage does not restate the claim's rule.", said(IngestStages.JOIN, title, "pass" j false, "restated" j none))
        assertEquals("The record keeps the claim's rule as a norm.", said(IngestStages.QUAD, title, "pass" j true, "lacking" j none))
        assertEquals("The record does not keep the claim's rule as a norm.", said(IngestStages.QUAD, title, "pass" j false, "lacking" j _l["must|title|remain|with you|"], "error" j null))
    }
}
