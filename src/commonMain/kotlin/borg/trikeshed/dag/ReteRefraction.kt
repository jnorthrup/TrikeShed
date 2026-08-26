package borg.trikeshed.dag

import borg.trikeshed.collections.associative.LinearHashMap
import borg.trikeshed.lib.view
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
        fired.set(key, Unit)
        return true
    }

    fun invalidateBySupport(supportCid: ContentId): Int {
        val invalidated = fired.entries().view.filter { supportCid in it.a.supportCids }
        invalidated.forEach { fired.remove(it.a) }
        return invalidated.size
    }

    private fun Activation.refractionKey(): RefractionKey = RefractionKey(
        ruleId = ruleId,
        ruleVersionCid = ruleVersionCid,
        supportCids = supportCids
            .distinctBy { it.value }
            .sortedBy { it.value },
    )

    // Audited: refraction correctly keys on ruleVersionCid and sorted supportCids
}
