package borg.trikeshed.memory.ace

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.get
import borg.trikeshed.lib.α
import borg.trikeshed.job.ContentId

/**
 * ACE (Agentic Context Engineering) Curator Element.
 * Simulated generator lies removed: rnd() gates, token-count proxies, salience as drawn floats.
 * Merge now deterministically de-dups by ContentId and decays by pure function utility thresholds, not rnd() checks.
 * Lifecycle tokens CREATED, OPEN, ACTIVE, DRAINING, CLOSED
 */
class AceCuratorElement : AsyncContextElement(ElementState.CREATED) {
    companion object Key : AsyncContextKey<AceCuratorElement>()
    override val key: AsyncContextKey<AceCuratorElement> = Key

    override suspend fun open() {
        super.open()
        // Override/open or consume so OPEN→ACTIVE occurs before reflect/merge
        if (state == ElementState.OPEN) {
            state = ElementState.ACTIVE
        }
    }

    /** Pure function to compute eviction score for a bullet. Replaces random drop. */
    private fun evictionScore(bullet: PlaybookBullet): Long {
        val helpful = bullet.b.b.a.raw
        val born = bullet.b.b.b
        // Decays naturally: a low helpful count and old born ordinal means lower score
        // Give helpfulness much higher weight so useful older items survive
        return (helpful.toLong() * 1000) - born.toLong()
    }

    /**
     * Merges deltas, de-dups by ContentId, tracks helpfulness, and decays unused rules.
     * Eviction score is a pure function (Bullet) -> Long.
     */
    fun merge(playbook: Series<PlaybookBullet>, delta: Series<DeltaBullet>): Series<PlaybookBullet> {
        requireState(ElementState.ACTIVE)
        val nPlaybook = playbook.size
        val nDelta = delta.size
        val total = nPlaybook + nDelta
        
        if (total == 0) return emptyArray<PlaybookBullet>() α { it } // Return empty series via array
        
        // stdlib-boundary: list conversion just for stable sort/dedup
        val combined = Array<PlaybookBullet?>(total) { null }
        
        var idx = 0
        var playbookUniqueCount = 0
        
        // Deduplicate playbook first
        while (idx < nPlaybook) {
            val p = playbook[idx]
            var found = false
            var jdx = 0
            while (jdx < playbookUniqueCount) {
                val existing = combined[jdx]
                if (existing != null && existing.b.a == p.b.a) {
                    val oldHelpful = existing.b.b.a.raw
                    val newP = existing.a j (existing.b.a j (HelpfulCount(oldHelpful + 1) j existing.b.b.b))
                    combined[jdx] = newP
                    found = true
                    break
                }
                jdx++
            }
            if (!found) {
                combined[playbookUniqueCount] = p
                playbookUniqueCount++
            }
            idx++
        }
        
        var added = 0
        idx = 0
        while (idx < nDelta) {
            val d = delta[idx]
            var found = false
            var jdx = 0
            while (jdx < playbookUniqueCount + added) {
                val p = combined[jdx]
                if (p != null && p.b.a == d.b) {
                    val oldHelpful = p.b.b.a.raw
                    val newP = p.a j (p.b.a j (HelpfulCount(oldHelpful + 1) j p.b.b.b))
                    combined[jdx] = newP
                    found = true
                    break
                }
                jdx++
            }
            if (!found) {
                // Not found, add it. bornOrdinal = 1 for recent items, will age
                combined[playbookUniqueCount + added] = d.a j (d.b j (HelpfulCount(0) j 1))
                added++
            }
            idx++
        }
        
        val actualCount = playbookUniqueCount + added
        val compressed = Array<PlaybookBullet?>(actualCount) { null }
        var cIdx = 0
        idx = 0
        while (idx < total) {
            val item = combined[idx]
            if (item != null) {
                // Age all items
                val agedItem = item.a j (item.b.a j (item.b.b.a j (item.b.b.b + 1)))
                compressed[cIdx++] = agedItem
            }
            idx++
        }
        
        // stdlib-boundary: sorting
        compressed.sortWith(Comparator { a, b ->
            if (a == null || b == null) 0 else evictionScore(b).compareTo(evictionScore(a))
        })
        
        // Keep top items or threshold > 0 (dropping old unused ones)
        val THRESHOLD = -1000L 
        
        var finalCount = 0
        idx = 0
        while (idx < actualCount) {
            if (compressed[idx] != null && evictionScore(compressed[idx]!!) >= THRESHOLD) {
                finalCount++
            }
            idx++
        }
        
        if (finalCount == 0) {
            return emptyArray<PlaybookBullet>() α { it }
        }
        
        val resultArr = Array<PlaybookBullet>(finalCount) { compressed[0]!! }
        idx = 0
        var rIdx = 0
        while (idx < actualCount) {
            if (compressed[idx] != null && evictionScore(compressed[idx]!!) >= THRESHOLD) {
                resultArr[rIdx++] = compressed[idx]!!
            }
            idx++
        }
        
        return resultArr α { it }
    }
    
    override suspend fun drain() {
        super.drain()
    }
}
