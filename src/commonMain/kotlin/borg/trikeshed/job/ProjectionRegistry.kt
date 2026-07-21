package borg.trikeshed.job

import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.confix.Syntax
import borg.trikeshed.parse.confix.value

class ProjectionRegistry(val store: CasStore) {
    fun project(cid: ContentId): Lens {
        val bytes = store.get(cid) ?: return Raw

        if (bytes.isEmpty()) {
            return Raw
        }

        var firstNonWhitespace = -1
        for (i in bytes.indices) {
            val char = bytes[i].toInt().toChar()
            if (!char.isWhitespace()) {
                firstNonWhitespace = i
                break
            }
        }

        if (firstNonWhitespace == -1) {
            return Raw
        }

        val syntax = if (bytes[firstNonWhitespace].toInt().toChar() in setOf('{', '[', '"')) Syntax.JSON else Syntax.CBOR
        val doc = try {
            confixDoc(bytes, syntax)
        } catch (e: Exception) {
            return Raw
        }

        val tagStr = doc.value("tag")?.toString() ?: doc.value("kind")?.toString()

        return if (tagStr != null) {
            LENS_TAG_MAP[tagStr] ?: Raw
        } else {
            Raw
        }
    }
}
