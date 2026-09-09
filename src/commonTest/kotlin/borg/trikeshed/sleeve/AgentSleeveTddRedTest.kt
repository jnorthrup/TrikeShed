package borg.trikeshed.sleeve

import borg.trikeshed.kanban.CardPriority
import borg.trikeshed.pointcut.VmFacet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TDD-RED for the agent sleeves.
 *
 * These are red on purpose and stay red until the ports land. Each assertion names the work that turns
 * it green, so the failure message is the instruction.
 */
class AgentSleeveTddRedTest {

    @Test
    fun hermesIsRedUntilTheHttpAndSqliteWaistsLand() {
        val hermes = AgentSleeveRegistry.HERMES
        assertTrue(hermes.pinned, "hermes donor root is pinned — it has measured history")
        assertTrue(
            hermes.isRed,
            "expected RED until the HTTP and sqlite3 waists land. Unresolved: ${hermes.unresolved.map { it.id }}",
        )
        assertEquals(
            setOf("hermes/http-client", "hermes/sqlite3"),
            hermes.unresolved.map { it.id }.toSet(),
            "the triage doc ranks exactly these two as what stands between the console and a working turn",
        )
        // The five resolved traps are the ones that took imports 1075 → 1255 and isolate kills 1 → 0.
        assertEquals(5, hermes.traps.count { it.resolved })
    }

    @Test
    fun piIsRedUntilItsDonorRootIsPinned() {
        val pi = AgentSleeveRegistry.PI
        assertFalse(pi.pinned, "pi donor root must stay unpinned until Jim names the checkout")
        assertTrue(pi.isRed, pi.verdict)
        assertTrue(
            pi.verdict.contains("donor root not pinned"),
            "an unpinned sleeve must say so rather than reporting a trap count it cannot know: ${pi.verdict}",
        )
    }

    @Test
    fun ohmypiIsRedUntilItsDonorRootIsPinned() {
        val ohmypi = AgentSleeveRegistry.OHMYPI
        assertFalse(ohmypi.pinned)
        assertTrue(ohmypi.isRed, ohmypi.verdict)
        assertEquals(VmFacet.GRAAL_JS, ohmypi.facet, "the shell surface is JS-hosted; GRAAL_JS has no sleeve yet")
    }

    @Test
    fun uncatchableTrapsOutrankCatchableOnes() {
        // A host-uncatchable trap downs the isolate and hides every trap behind it, so it must sort first.
        val ranked = AgentSleeveRegistry.PI.unresolved
        assertTrue(ranked.isNotEmpty())
        val firstCatchable = ranked.indexOfFirst { it.catchability == TrapCatchability.GUEST_CATCHABLE }
        val lastUncatchable = ranked.indexOfLast { it.catchability == TrapCatchability.HOST_UNCATCHABLE }
        assertTrue(lastUncatchable < firstCatchable, "uncatchable traps must sort ahead: ${ranked.map { it.id }}")
    }

    @Test
    fun everyUnresolvedTrapBecomesOneRedCardNamingItsForkAndRedTest() {
        for (manifest in AgentSleeveRegistry.ALL) {
            val cards = SleeveTddRedKanban.cards(manifest)
            val trapCards = cards.filter { it.metadata["kind"] == "polyglot-trap" }
            assertEquals(
                manifest.unresolved.size, trapCards.size,
                "${manifest.id}: one card per unresolved trap, no more and no fewer",
            )
            for (card in trapCards) {
                assertEquals(SleeveTddRedKanban.COLUMN, card.columnId, "a trap card is never born claimable")
                assertTrue(card.metadata.getValue("forkPath").startsWith(manifest.sleeveRoot),
                    "the fork must live under the sleeve, not in the donor checkout: ${card.metadata["forkPath"]}")
                assertTrue(card.metadata.getValue("redTest").isNotBlank(), "a trap with no red test is not work")
            }
        }
    }

    @Test
    fun anUnpinnedSleeveBlocksItsOwnTrapCards() {
        val cards = SleeveTddRedKanban.cards(AgentSleeveRegistry.PI)
        val pin = cards.single { it.metadata["kind"] == "pin-donor-root" }
        val trapCards = cards.filter { it.metadata["kind"] == "polyglot-trap" }
        assertTrue(trapCards.isNotEmpty())
        assertTrue(
            trapCards.all { pin.id in it.dependencies },
            "you cannot reproduce a break in a checkout you have not named",
        )
    }

    @Test
    fun cardIdsAreStableSoReRunningNeverDuplicates() {
        val first = SleeveTddRedKanban.cards().map { it.id.value }
        val second = SleeveTddRedKanban.cards().map { it.id.value }
        assertEquals(first, second)
        assertEquals(first.size, first.toSet().size, "duplicate card ids: ${first.groupBy { it }.filter { it.value.size > 1 }.keys}")
    }

    @Test
    fun cardsLandInAColumnTheBoardActuallyHas() {
        // The live board is triage/todo/ready/running/blocked/review/done/archived. A card addressed to
        // a column that does not exist is not on the board at all.
        val columns = setOf("triage", "todo", "ready", "running", "blocked", "review", "done", "archived")
        assertTrue(SleeveTddRedKanban.COLUMN.value in columns, "invented column: ${SleeveTddRedKanban.COLUMN.value}")
        assertTrue(SleeveTddRedKanban.cards().all { it.columnId.value in columns })
        // Not `ready`: the claim loop dispatches Ready to a model, and a trap nobody has watched fail
        // would be handed to a model on the strength of a description alone.
        assertTrue(SleeveTddRedKanban.cards().none { it.columnId.value == "ready" })
    }

    @Test
    fun everySubmissionCarriesAJudgeableSpec() {
        for (submission in SleeveTddRedKanban.submissions()) {
            val spec = submission["spec"] as String
            assertTrue(spec.startsWith("GOAL:"), "a spec opens with its goal: $spec")
            assertTrue(spec.lines().any { it.startsWith("MUST:") }, "without a MUST the judge has nothing to close on")
            assertTrue(spec.lines().any { it.startsWith("OUT-OF-SCOPE:") }, "the donor checkout must be fenced off")
            assertTrue((submission["priority"] as Int) in 0..3, "board priority is an int, lower is more urgent")
            assertTrue((submission["jobId"] as String).isNotBlank())
        }
    }

    @Test
    fun noSpecAsksAnAgentToEditTheDonorCheckout() {
        // The whole point of a sleeve is that the donor stays pristine and is shadowed by module identity.
        for (submission in SleeveTddRedKanban.submissions()) {
            val spec = submission["spec"] as String
            assertTrue(
                spec.contains("OUT-OF-SCOPE: editing the donor checkout") || spec.contains("OUT-OF-SCOPE: resolving any trap"),
                "spec for ${submission["jobId"]} does not fence the donor checkout",
            )
        }
    }

    @Test
    fun uncatchableTrapCardsAreCritical() {
        val cards = SleeveTddRedKanban.cards().filter { it.metadata["catchability"] == "host_uncatchable" }
        assertTrue(cards.isNotEmpty())
        assertTrue(cards.all { it.priority == CardPriority.CRITICAL }, "an isolate-killing trap is CRITICAL")
    }

    @Test
    fun theFleetIsRedWhileAnySleeveIsRed() {
        val summary = AgentSleeveRegistry.summarize()
        assertEquals(true, summary["anyRed"])
        assertEquals(false, summary["allGreen"])
        assertTrue((summary["uncatchable"] as Int) > 0, "pi and ohmypi both carry unreproduced thread/process bounds")
    }
}
