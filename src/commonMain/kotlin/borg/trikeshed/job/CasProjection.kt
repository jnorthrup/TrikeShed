package borg.trikeshed.job

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.graph.CausalGraphNode
import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.parse.confix.roots
import borg.trikeshed.parse.confix.value
import borg.trikeshed.lib.Join

sealed class Lens {
    data class Raw(val bytes: ByteArray) : Lens()
    data class CursorLens(val cursor: Cursor) : Lens()
    data class BtreePage(val doc: ConfixDoc) : Lens()
    data class CausalNode(val doc: ConfixDoc) : Lens()
    data class Manifest(val doc: ConfixDoc) : Lens()
}

fun project(cid: ContentId, cas: CasStore): Lens {
    val bytes = cas.get(cid) ?: throw IllegalArgumentException("CID not found in CAS: \$cid")

    val doc = try {
        Join.parse(bytes)
    } catch (e: Exception) {
        return Lens.Raw(bytes)
    }

    val kind = doc.value("kind") as? String ?: doc.value("tag") as? String

    return when (kind) {
        "btree-page" -> Lens.BtreePage(doc)
        "causal-node" -> Lens.CausalNode(doc)
        "manifest" -> Lens.Manifest(doc)
        else -> Lens.CursorLens(doc.roots)
    }
}
