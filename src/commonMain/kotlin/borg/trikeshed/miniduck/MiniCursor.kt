package borg.trikeshed.miniduck

import borg.trikeshed.cursor.Cursor

fun emptyMiniCursor(): Cursor = Cursor(0) { _ -> error("This cursor is empty") }
