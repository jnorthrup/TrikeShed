package borg.trikeshed.confix

import borg.trikeshed.cursor.*

// ── ConfixOracleFacade — collapsed type algebra ───────────────────
//
// Unified entry point for all typedef resolution:
//   resolveTypedefs()  → CBOR syntactic substitution
//   getTypedefChain()   → oracle algebraic chain (single source of truth)
//
// The old parallel path (getStepTarget → resolveTypedefs → getLattice → sentinel)
// collapses to a single getTypedefChain() call. isA() is unchanged.
//
// Usage from xvm Java:
//   int[] chain = facade.getTypedefChain("org/xvm/asm", "Join");
//   // chain[0] = poolIdx of "Join"
//   // chain[1..n] = poolIdx of each typedef hop
//   // chain[n+1] = poolIdx of terminal (no more hops)

interface ConfixOracleFacade {
    fun addSource(sourceText: String, modulePath: String): Int

    /**
     * Typedef staircase chain — single source of truth for typedef resolution.
     *
     * Returns pool indices from the typedef alias to its terminal type,
     * in order. The caller uses these indices to reconstruct the staircase.
     *
     * @param modulePath  e.g. "org/xvm/asm"
     * @param typeName    e.g. "Join" or "Cursor"
     * @return pool indices: [alias, step1, step2, ..., terminal]
     *         Empty array if the type is not a typedef or not in the oracle.
     */
    fun getTypedefChain(modulePath: String, typeName: String): IntArray

    /**
     * Ternary IS-A query — used by TypeConstant.calculateRelation().
     *   return 2  — YES: oracle has a direct or transitive IS-A edge
     *   return 1  — NO:  oracle knows child is NOT a subtype of parent
     *   return 0  — UNKNOWN: neither token in lattice, or no opinion
     *
     * @param childPoolIdx  pool index of the more-specific type
     * @param parentPoolIdx pool index of the less-specific type
     * @return 2 = YES, 1 = NO, 0 = UNKNOWN
     */
    fun isA(childPoolIdx: Int, parentPoolIdx: Int): Integer?

    fun setListener(listener: TypeDefListener?)
    fun edgeCount(): Int
}

// ── TypeDefListener ─────────────────────────────────────────────────

interface TypeDefListener {
    fun onTypeDefChanged(modulePath: String, typeName: String)
    fun onCompilationUnitResolved(modulePath: String, typedefCount: Int)
}