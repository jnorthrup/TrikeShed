package borg.trikeshed.dag

import borg.trikeshed.collections.LinearHashMap
import borg.trikeshed.job.ContentId

private data class RefractionKey(
    val ruleId: String,
    val ruleVersionCid: ContentId,
    val supportCids: List<ContentId>,
)

/** One firing per rule-version and unordered set of supporting fact versions. */
class ReteRefraction {
    private val fired = LinearHashMap<RefractionKey, Unit>()

    fun record(activation: Activation): Boolean {
        val key = activation.refractionKey()
        if (key in fired) return false
        fired.put(key, Unit)
        return true
    }

    fun invalidateBySupport(supportCid: ContentId): Int {
        val invalidated = fired.keyList().filter { supportCid in it.supportCids }
        invalidated.forEach(fired::remove)
        return invalidated.size
    }

    private fun Activation.refractionKey(): RefractionKey = RefractionKey(
        ruleId = ruleId,
        ruleVersionCid = ruleVersionCid,
        supportCids = supportCids
            .distinctBy { it.value }
            .sortedBy { it.value },
    )
}
