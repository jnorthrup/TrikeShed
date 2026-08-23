package borg.trikeshed.platform

/**
 * The one chokepoint for per-target feature discontinuation.
 *
 * Platform rule: a commonMain *interface* declares every operation with a default body of
 * `discontinued("feature.op")`; a target's implementation overrides only what it provides. Anything
 * left unoverridden is dead code by construction — no `expect/actual` pair, no build-file exclusion —
 * and every attempt to reach it is recorded here so the Forge host view can list what is dead on
 * this host. Grep for `discontinued(` to find every dead path in the tree.
 */
object Discontinued {
    private val hits = LinkedHashSet<String>()
    private val declared = LinkedHashSet<String>()

    /** Features whose chokepoint has actually been hit in this process. */
    val features: Set<String> get() = hits.toSet()

    /** Features a provider/host reported as not provided without anyone calling them (see [declare]). */
    val declaredDead: Set<String> get() = declared.toSet()

    /** Report a feature as not provided on this host without throwing — used by supervisors at probe time. */
    fun declare(feature: String) { declared += feature }

    internal fun hit(feature: String) { hits += feature }
}

/** Interface default body for an operation this target does not provide. Always throws. */
fun discontinued(feature: String): Nothing {
    Discontinued.hit(feature)
    TODO("$feature is not provided on this target")
}
