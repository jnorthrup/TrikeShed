package borg.trikeshed.lcnc.reduction

import borg.trikeshed.context.nuid.Capability

object ReducerRegistry {
    var registry: Map<String, LcncReduction<*, *, *, *>> = mapOf(
        "process" to LcncReductions.forgeCascade(emptyList(), emptyList()),
        "cas" to LcncReductions.confixParse(),
        "wireproto" to LcncReductions.crmsFold()
    )

    fun runFor(winningCapability: Capability, payload: Any?): Any? {
        val reduction = registry[winningCapability.category] ?: return null

        @Suppress("UNCHECKED_CAST")
        val typedReduction = reduction as LcncReduction<Any, Any, Any, Any>
        val typedCarrierAlg = typedReduction.carrierAlg

        val carrier = if (payload != null) {
            typedCarrierAlg.carrier(payload)
        } else {
            emptySeriesCarrier()
        }

        return typedReduction.execute(carrier)
    }
}
