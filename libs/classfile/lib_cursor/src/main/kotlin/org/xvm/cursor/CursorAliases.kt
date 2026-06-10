@file:Suppress("TOPLEVEL_TYPEALIASES_ONLY")
package org.xvm.cursor

// Re-export TrikeShed cursor and confix types so org.xvm.cursor code and tests resolve them
// without explicit package prefixes.
typealias Cursor = borg.trikeshed.cursor.Cursor
typealias RowVec = borg.trikeshed.cursor.RowVec
typealias ConfixOracleFacade = borg.trikeshed.confix.ConfixOracleFacade
typealias ConfixOracleService = borg.trikeshed.confix.ConfixOracleService
typealias ConfixIndex = borg.trikeshed.parse.confix.ConfixIndex
typealias ConfixIndexK<R> = borg.trikeshed.parse.confix.ConfixIndexK<R>
typealias ConfixCell = borg.trikeshed.parse.confix.ConfixCell
typealias ConfixDoc = borg.trikeshed.parse.confix.ConfixDoc
typealias Syntax = borg.trikeshed.parse.confix.Syntax
typealias IOMemento = borg.trikeshed.cursor.IOMemento

fun confixDoc(text: String): ConfixDoc = borg.trikeshed.parse.confix.confixDoc(text)
fun confixDoc(bytes: ByteArray, syntax: Syntax): ConfixDoc = borg.trikeshed.parse.confix.confixDoc(bytes, syntax)
fun scan(text: String): ConfixIndex = borg.trikeshed.parse.confix.scan(text)
fun scan(bytes: ByteArray, syntax: Syntax = Syntax.CBOR): ConfixIndex = borg.trikeshed.parse.confix.scan(bytes, syntax)

