package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import kotlin.test.*

class CamelCorpusRoutingPresetTest {
    @Test fun offeredRouteHasOnlyExplicitReceiptAndModeAndFixedLocalChoice(): Unit {
        val encoded = LcncPresets.all().getValue(CamelCorpusRouting.NAME)
        val program = LcncProgramConfix.fromJson(CamelCorpusRouting.NAME, encoded)
        assertEquals(encoded, LcncProgramConfix.toJson(program))
        assertNotNull(LcncPresets.info(CamelCorpusRouting.NAME))
        assertTrue(LcncTypeCheck.check(program, LcncContracts.all().associateBy { it.type }, strict = true).isEmpty())
        val contract = assertNotNull(LcncContracts.find(CamelCorpusRouting.TYPE))
        assertTrue(contract.isEffect); assertFalse(contract.isSink)
        assertEquals(_l["receiptCid", "mode"], contract.inputs)
        assertEquals(LcncNodeKey.ENGLISH_CORPUS_ROUTE, LcncNodeKey.of(contract.type))
        assertEquals(_s["note", "text.value", CamelCorpusRouting.TYPE, "display", LcncContracts.SCOPE_OUT], program.nodes.view.map { it.type }.toSet())
        assertEquals(_s["receiptCid", "mode"], program.wires.view.filter { it.toNode == "route" }.map { it.toPort }.toSet())
        val id = "sha256:" + "a".repeat(64)
        val plan = CamelCorpusRouting.plan(id, id, id, null)
        plan.validate(CamelLinkage.Reach.LOCAL, _l["camel"])
        assertEquals(CamelCorpusRouting.FROM, plan.from)
        val choice = plan.steps.view.filterIsInstance<CamelRouteStep.Choice>().single()
        assertEquals(_s[CamelCorpusRouting.MATCHES, CamelCorpusRouting.EMPTY], choice.branches.view.map { it.bodyEquals }.toSet())
        assertFalse(plan.steps.view.any { it is CamelRouteStep.To })
        assertFailsWith<IllegalStateException> { CamelCorpusRouting.lane("unknown") }
        assertFailsWith<IllegalArgumentException> { CamelCorpusRouting.build(mode = "automatic") }
        assertFailsWith<IllegalArgumentException> { CamelCorpusRouting.build("sha256:short") }
    }
}
