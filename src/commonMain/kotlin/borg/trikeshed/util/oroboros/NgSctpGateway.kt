package borg.trikeshed.util.oroboros

import borg.trikeshed.sctp.SctpElement
import borg.trikeshed.lib.OpK
import borg.trikeshed.lib.MetaSeries
import borg.trikeshed.lib.FacetedRow
import borg.trikeshed.lib.j

sealed class NgSctpGatewayK<out R> : OpK<R>() {
    object GetSctpElement : NgSctpGatewayK<SctpElement>()
}

class NgSctpGateway(
    private val sctpElement: SctpElement
) {
    fun facetRow(): FacetedRow<NgSctpGatewayK<*>> {
        return object : FacetedRow<NgSctpGatewayK<*>> {
            override val a: NgSctpGatewayK<*> = NgSctpGatewayK.GetSctpElement
            override val b: (NgSctpGatewayK<*>) -> Any? = { key ->
                when (key) {
                    NgSctpGatewayK.GetSctpElement -> sctpElement
                }
            }
        }
}
