package borg.trikeshed.miniduck

import borg.trikeshed.cursor.Cursor

typealias MiniCursor = Cursor

fun emptyMiniCursor(): MiniCursor = Cursor(0) { _ -> error("This cursor is empty") }