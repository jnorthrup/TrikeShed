package borg.trikeshed.isam

import borg.trikeshed.lib.Cursor

expect class IsamDataFile {
    fun open()
    fun close()
    companion object {
        fun write(cursor: Cursor, datafilename: String)
    }
}