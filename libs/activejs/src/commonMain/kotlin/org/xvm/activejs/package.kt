@file:Suppress("UNUSED_IMPORT", "REDUNDANT_PUBLIC")

package org.xvm.activejs

/**
 * ActiveJS — Multiplatform Confix wrapper for live classfile pointcuts.
 *
 * Public API exports:
 *   - ActiveJsTaxonomy: Confix-based faceted ClassFile browse/registry
 *   - LivePointcutCursor: Reactive cursor projecting live pointcut events
 *   - ConfixActiveJsTaxonomy: Confix wrapper with SAX/BlackBoardEntry emission
 *   - Jep466ActiveJsBridge: JEP 466 ClassFile API bridge (JVM/JS/WASM/Native)
 *   - ActiveJsFacet: JS/WASM-specific runtime intent tags
 *   - CoordinateRow: Wire-friendly coordinate row data class
 *   - PointcutEvent: Live pointcut event structure
 */

public expect class ColumnMeta

public actual typealias ColumnMeta = org.xvm.cursor.ColumnMeta
public actual typealias TypeMemento = org.xvm.cursor.TypeMemento
public actual typealias IOMemento = org.xvm.cursor.IOMemento
public actual typealias RowVec = org.xvm.cursor.RowVec
public actual typealias Cursor = org.xvm.cursor.Cursor
public actual typealias Series<T> = borg.trikeshed.lib.Series<T>
public actual typealias Join<A, B> = borg.trikeshed.lib.Join<A, B>
public actual typealias ColumnMetaRef = org.xvm.activejs.ActiveJsTaxonomy.ColumnMetaRef
public actual typealias PointcutFacet = org.xvm.cursor.PointcutFacet

// Re-export main types
public typealias ActiveJsTaxonomy = org.xvm.activejs.ActiveJsTaxonomy
public typealias LivePointcutCursor = org.xvm.activejs.LivePointcutCursor
public typealias ConfixActiveJsTaxonomy = org.xvm.activejs.ConfixActiveJsTaxonomy
public typealias Jep466ActiveJsBridge = org.xvm.activejs.Jep466ActiveJsBridge
public typealias ActiveJsFacet = org.xvm.activejs.ActiveJsFacet
public typealias CoordinateRow = org.xvm.activejs.ActiveJsTaxonomy.CoordinateRow
public typealias PointcutEvent = org.xvm.activejs.LivePointcutCursor.PointcutEvent
public typealias LiveQuery = org.xvm.activejs.LivePointcutCursor.LiveQuery
public typealias BlackBoardEntry = borg.trikeshed.parse.confix.BlackBoardEntry
public typealias ConfixRole = borg.trikeshed.parse.confix.ConfixRole
public typealias SaxEvent = borg.trikeshed.parse.confix.SaxEvent