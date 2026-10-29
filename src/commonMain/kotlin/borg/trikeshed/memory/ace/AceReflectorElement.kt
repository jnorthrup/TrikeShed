package borg.trikeshed.memory.ace

import kotlin.coroutines.CoroutineContext
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.memory.MemoryFile
import borg.trikeshed.memory.content
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.size
import borg.trikeshed.lib.get
import borg.trikeshed.lib.α

/**
 * ACE (Agentic Context Engineering) Reflector Element.
 * Simulated generator lies removed: rnd() gates, Hold.p Bernoulli success, token-count proxies, salience as a drawn float, retries as invented cost.
 * Desimulated from engine.ts where behavior was probabilistic. Now processes deterministic evicted slices into deltas.
 */
class AceReflectorElement : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<AceReflectorElement>
    override val key: CoroutineContext.Key<AceReflectorElement> = Key

    /**
     * Distills an evicted slice of memory into itemized delta-bullets.
     * Replaces previous monolithic rewrite approach.
     */
    fun reflect(evictedSlice: Series<MemoryFile>): Series<DeltaBullet> {
        // Deterministic distillation based on file artifact size/id instead of random numbers
        return evictedSlice α { file ->
            val fileId = ContentId.of(file.content)
            BulletId(fileId.hashCode()) j fileId
        }
    }
}
