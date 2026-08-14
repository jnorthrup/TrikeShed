package borg.trikeshed.memory.ace

/**
 * ACE (Agentic Context Engineering) Roles as marker objects.
 * Maps onto TrikeShed CCEK elements.
 * 
 * Simulation Lies Removed from engine.ts:
 * - Removed `rnd()` gates.
 * - Removed `Hold.p` Bernoulli success probabilities.
 * - Removed token-count proxies for context windows.
 * - Removed `salience` as a drawn random float.
 * - Removed retries as an invented cost.
 * - All of these are replaced by deterministic, pure functions operating on Series of Join-composed bullets.
 * 
 * See arXiv:2510.04618 for the architectural mapping:
 * - Generator: emits the next cell/action.
 * - Reflector: distills the evicted window slice into itemized delta-bullets.
 * - Curator: merges deltas, de-dups, tracks helpfulness, decays unused rules.
 * 
 * Note: DC-CU full cheatsheet rewrite, GEPA monolithic prompt evolve+brevity bias, Reflexion 3-slot critique, Unbounded 94% emergency compact, and SlidingWindow FIFO are recognized as competitors and are explicitly not implemented here.
 */
sealed interface AceRole {
    object Generator : AceRole
    object Reflector : AceRole
    object Curator : AceRole
}
