package borg.trikeshed.narsese

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * SkillUsageFeeder — jvmMain retrieval for [SkillUsageLedger].
 *
 * Companion to [CuratorImpulseFeeder]: that one carries *edit* evidence (the
 * curator ledger — what was written), this one carries *utility* evidence (the
 * usage record — what was actually used). Only the pair is a fair basis; edits
 * alone bias every conclusion toward accretion, because writing a skill is the
 * one act the review pass is instructed never to skip.
 *
 * Retrieval only — [SkillUsageLedger] owns the shaping. Blocking IO is
 * dispatched, never run on the reactor.
 */
class SkillUsageFeeder(private val profileDir: File) {

    /**
     * Parse `<profile>/skills/.usage.json`. Empty when the file is absent or
     * is not a JSON object — a missing usage record is a profile that has
     * never curated, not an error.
     */
    suspend fun load(): Series<SkillUsageRecord> = withContext(Dispatchers.IO) {
        val f = File(profileDir, "skills/.usage.json")
        if (!f.isFile) return@withContext emptySeriesOf()
        val parsed = runCatching { JsonSupport.parse(f.readText()) }.getOrNull() as? Map<*, *>
            ?: return@withContext emptySeriesOf()
        @Suppress("UNCHECKED_CAST")
        SkillUsageLedger.records(parsed as Map<String, Any?>)
    }
}
