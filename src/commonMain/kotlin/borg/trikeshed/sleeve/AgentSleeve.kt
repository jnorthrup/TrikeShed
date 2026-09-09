package borg.trikeshed.sleeve

import borg.trikeshed.pointcut.VmFacet

/**
 * How a polyglot incompatibility was resolved at the import waist.
 *
 * The distinction is the whole ethos: a sandbox bound is not a missing module, and the two fail
 * differently. `graalpy-sleeve/hermes/README.md` records the measured case — withholding
 * `logging.handlers.QueueListener` alone let boot "succeed" while every record piled up in a queue
 * nobody drained, so that trap had to be PORTED rather than WITHHELD.
 */
enum class TrapShape {
    /** The capability cannot exist here; the twin raises what the donor raises when the OS refuses. */
    WITHHELD,

    /** The capability has a faithful meaning under the bound, so the twin implements it. */
    PORTED,

    /** Trapped and reproduced, but not yet resolved either way. This is what keeps a sleeve RED. */
    UNRESOLVED,
}

/**
 * Whether the guest can even observe the failure.
 *
 * A HOST_UNCATCHABLE trap is the dangerous kind: GraalPy raises a host `IllegalStateException` that no
 * guest `except BaseException` sees, the isolate is classified DEAD, and everything downstream of it is
 * never observed. Those are ranked first because one library's background worker takes the whole VM.
 */
enum class TrapCatchability { GUEST_CATCHABLE, HOST_UNCATCHABLE }

/**
 * One trapped polyglot incompatibility, carrying the three artifacts the ethos demands:
 * a forked source under the sleeve, a red test that reproduces the break, and a card id.
 *
 * @param forkPath sleeve-relative path of the source fork that shadows [donorModule]. The fork is how a
 *   trap becomes editable: the donor checkout is never modified, the sleeve shadows it by module identity.
 * @param redTest the test that must fail before the fork exists and pass after. An [UNRESOLVED] trap
 *   whose red test already passes is a lie, and [SleeveTddRedKanban] refuses to card it as work.
 */
data class PolyglotTrap(
    val id: String,
    val donorModule: String,
    val symbol: String,
    val shape: TrapShape,
    val catchability: TrapCatchability,
    val forkPath: String,
    val redTest: String,
    /** What was actually observed — a host exception class, an AttributeError, a measured queue depth. */
    val evidence: String,
) {
    val resolved: Boolean get() = shape != TrapShape.UNRESOLVED

    /** Ranked first: an uncatchable trap downs the isolate and hides every trap behind it. */
    val severity: Int get() = when {
        !resolved && catchability == TrapCatchability.HOST_UNCATCHABLE -> 0
        !resolved -> 1
        else -> 2
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "donorModule" to donorModule,
        "symbol" to symbol,
        "shape" to shape.name.lowercase(),
        "catchability" to catchability.name.lowercase(),
        "forkPath" to forkPath,
        "redTest" to redTest,
        "evidence" to evidence,
        "resolved" to resolved,
    )
}

/**
 * An agent sleeve: one donor agent, one guest facet, and the traps found running the donor inside it.
 *
 * @param donorRoot where the donor checkout lives. `null` means the root is not pinned yet, which is
 *   itself a RED condition — a sleeve cannot trap what it cannot run.
 * @param sleeveRoot repo-relative directory whose files shadow donor modules by module identity
 *   (`pydantic/__init__.py` shadows `pydantic`), so the donor checkout is never forked in place.
 */
data class AgentSleeveManifest(
    val id: String,
    val facet: VmFacet,
    val entryModule: String,
    val sleeveRoot: String,
    val donorRoot: String?,
    val traps: List<PolyglotTrap>,
) {
    val pinned: Boolean get() = donorRoot != null
    val unresolved: List<PolyglotTrap> get() = traps.filter { !it.resolved }.sortedBy { it.severity }

    /** RED until the donor root is pinned and every trap is either ported or withheld. */
    val isRed: Boolean get() = !pinned || unresolved.isNotEmpty()
    val isGreen: Boolean get() = !isRed

    val verdict: String get() = when {
        !pinned -> "RED — $id donor root not pinned; nothing can be trapped until the checkout is named"
        unresolved.isEmpty() -> "GREEN — $id runs on ${facet.id} with ${traps.size} traps resolved"
        else -> "RED — $id has ${unresolved.size} unresolved trap(s), " +
            "${unresolved.count { it.catchability == TrapCatchability.HOST_UNCATCHABLE }} uncatchable"
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "facet" to facet.id,
        "entryModule" to entryModule,
        "sleeveRoot" to sleeveRoot,
        "donorRoot" to donorRoot,
        "pinned" to pinned,
        "red" to isRed,
        "green" to isGreen,
        "verdict" to verdict,
        "traps" to traps.map { it.toMap() },
    )
}
