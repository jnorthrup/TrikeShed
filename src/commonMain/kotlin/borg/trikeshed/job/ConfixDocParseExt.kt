package borg.trikeshed.job

import borg.trikeshed.lib.Join
import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.confix.Syntax

/**
 * ConfixDoc.parse — brought into the borg.trikeshed.job package scope so
 * tests calling ConfixDoc.parse(bytes) resolve without explicit import.
 *
 * ConfixDoc is a typealias for Join<ConfixIndex, Series<Byte>>, so
 * ConfixDoc.parse(bytes) resolves to a companion call on Join.Companion.
 */
fun Join.Companion.parse(bytes: ByteArray): ConfixDoc =
    confixDoc(bytes, Syntax.JSON)

fun Join.Companion.parse(text: String): ConfixDoc =
    confixDoc(text)
