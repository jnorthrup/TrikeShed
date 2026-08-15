/**
 * Line CAS merge strategy — the external interface, specified entirely as
 * typealiases and value-class brands. Specification only; no runtime code.
 * Gate: tsgo --noEmit (TS 7.0).
 *
 * Pipeline — the N-way residual merge; every stage a pure function:
 *
 *   sources ─spine→ LineSpines ─residualsOf→ atoms (master-funnel misses)
 *          ─topologyOf→ clusters by mini64 ─gradeClusters→ graded topology
 *          ─mergeResiduals→ MergeReceipt { kept: NOVEL|RELOCATED,
 *                                          dropped: INHERITED* }
 *
 * Mostly idempotent semantics (the contract):
 *   - spine: content-addressed — same text ⇒ same spineCid, always.
 *   - trim: idempotent at line and spine level (law L1).
 *   - funnel: frozen after build; contains is a pure query; rebuild from
 *     the same (keys, seed) is extensionally equal (law M2).
 *   - settlement: re-merging the kept set against the same frozen funnel
 *     regrades nothing — dropped clusters never resurrect (law M3).
 *   - convergence: applying NOVEL survivors into master makes them funnel
 *     hits (INHERITED) on the next wave — waves converge (law M4).
 */
export {};
