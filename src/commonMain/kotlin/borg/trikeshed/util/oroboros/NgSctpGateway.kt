package borg.trikeshed.util.oroboros

import borg.trikeshed.sctp.SctpElement
import borg.trikeshed.lib.OpK
import borg.trikeshed.lib.FacetedRow
import borg.trikeshed.lib.j

sealed class NgSctpGatewayK<out R> : OpK<R>() {
    object GetSctpElement : NgSctpGatewayK<SctpElement>()
}

class NgSctpGateway(
    private val sctpElement: SctpElement
) {
    fun facetRow(): FacetedRow<NgSctpGatewayK<*>> =
        NgSctpGatewayK.GetSctpElement j { key ->
            when (key) {
                NgSctpGatewayK.GetSctpElement -> sctpElement
            }
        }
}
