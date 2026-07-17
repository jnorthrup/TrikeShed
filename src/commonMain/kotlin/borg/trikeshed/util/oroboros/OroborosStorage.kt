package borg.trikeshed.util.oroboros

import borg.trikeshed.lib.OpK
import borg.trikeshed.job.CasStore
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * J1 — core storage facets for util/oroboros.
 *
 * OroborosStorageK sealed class hierarchy for typed storage facet access.
 */
sealed class OroborosStorageK<out R> : OpK<R>() {
    object Cas : OroborosStorageK<CasStore>()
    object Attachments : OroborosStorageK<CouchAttachmentGateway>()
    object Events : OroborosStorageK<ReceiveChannel<ByteArray>>()
    object Manifest : OroborosStorageK<borg.trikeshed.lib.Series2<String, OroborosAttachmentRef>>()
    object Status : OroborosStorageK<String>()
}

/**
 * Typed storage facet row implementing access via OroborosStorageK.
 */
interface OroborosStorageRow {
    operator fun <R> get(key: OroborosStorageK<R>): R
}
