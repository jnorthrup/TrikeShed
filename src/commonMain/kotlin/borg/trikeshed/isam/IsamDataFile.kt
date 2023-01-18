package borg.trikeshed.isam

import borg.trikeshed.common.Usable
import borg.trikeshed.lib.Cursor

expect class IsamDataFile(
    datafileFilename: String,
    metafileFilename: String = "$datafileFilename.meta",
    metafile: IsamMetaFileReader = IsamMetaFileReader(metafileFilename)
) : Usable,Cursor{
    val datafileFilename: String
    val metafile: IsamMetaFileReader
    
    override fun open()
    override fun close()
    companion object {
        fun write(cursor: Cursor, datafilename: String)
    }
}