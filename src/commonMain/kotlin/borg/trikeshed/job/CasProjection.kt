package borg.trikeshed.job

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.toSeries
import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.confix.Syntax
import borg.trikeshed.parse.confix.value

sealed interface Lens
data object Raw       : Lens
data object Cursor    : Lens
data object BtreePage : Lens
data object CausalNode: Lens
data object Manifest  : Lens

sealed interface LensClassifier {
    data class Known(val lens: Lens) : LensClassifier
    data class Unknown(val tag: String) : LensClassifier
    data object Missing : LensClassifier
}

val LENS_TAG_MAP: Map<String, Lens> = mapOf(
    "cursor" to Cursor,
    "btree-page" to BtreePage,
    "causal-node" to CausalNode,
    "treedoc-manifest" to Manifest
)

fun project(cid: ContentId, store: CasStore, kind: Lens): Series<Byte> {
    val bytes = store.get(cid) ?: throw IllegalArgumentException("CID not found in store: $cid")

    if (kind is Raw) {
        return bytes.toSeries()
    }

    val syntax = if (bytes.isNotEmpty() && bytes[0].toInt().toChar() in setOf('{', '[', '"')) Syntax.JSON else Syntax.CBOR
    val doc = confixDoc(bytes, syntax)

    val tagStr = doc.value("tag")?.toString() ?: doc.value("kind")?.toString()

    val classifier = if (tagStr == null) LensClassifier.Missing
                     else LENS_TAG_MAP[tagStr]?.let { LensClassifier.Known(it) } ?: LensClassifier.Unknown(tagStr)

    if (classifier !is LensClassifier.Known || classifier.lens != kind) {
        throw IllegalArgumentException("Projection mismatch: expected $kind but got $classifier")
    }

    return bytes.toSeries()
}
