package borg.trikeshed.miniduck

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec

/**
 * MiniRowVec: typealias to root RowVec.
 * 
 * "RowVec and Cursor contracts shall not bifurcate except at factorytime"
 * 
 * Use the root RowVec for all row representation.
 * Specialized factories (DocRowVec, ViewRowVec, etc.) live in RowVecFamilies.kt
 */
typealias MiniRowVec = RowVec

/**
 * MiniCursor: typealias to root Cursor.
 * 
 * Use the root Cursor for all cursor representation.
 */
typealias MiniCursor = Cursor