package borg.trikeshed.memory.ace

import kotlin.jvm.JvmInline
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.job.ContentId

/**
 * ACE (Agentic Context Engineering) Desimulated Types.
 * 
 * Documentation of Simulation Lies Removed from engine.ts:
 * 1. `rnd()` gates: Probabilistic branching has been removed entirely in favor of deterministic pure functions.
 * 2. `Hold.p` Bernoulli success probabilities: Replaced by explicit helpfulness tracking and thresholds.
 * 3. Token-count proxies: Window eviction is now based on logical series slices rather than simulated string token counts.
 * 4. `salience` as drawn float: Removed. Eviction uses a deterministic utility function based on use-count and recency.
 * 5. Retries as invented cost: Removed. Action results are definitive.
 * 
 * See arXiv:2510.04618 for detailed formal architecture mappings.
 */

@JvmInline
value class BulletId(val raw: Int)

@JvmInline
value class HelpfulCount(val raw: Int)

/**
 * PlaybookBullet as Join composition, avoiding data class heap objects.
 * Structure: id j contentId j helpful j bornOrdinal
 */
typealias PlaybookBullet = Join<BulletId, Join<ContentId, Join<HelpfulCount, Int>>>

/**
 * A series of playbook bullets. Never a MutableList.
 */
typealias Playbook = Series<PlaybookBullet>

/**
 * DeltaBullet emitted by Reflector.
 */
typealias DeltaBullet = Join<BulletId, ContentId>
