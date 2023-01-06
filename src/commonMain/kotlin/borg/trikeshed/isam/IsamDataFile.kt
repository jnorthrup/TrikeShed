package borg.trikeshed.isam

import borg.trikeshed.lib.Cursor

expect class IsamDataFile(
    datafileFilename: String,
    metafileFilename: String = "$datafileFilename.meta",
    metafile: IsamMetaFileReader = IsamMetaFileReader(metafileFilename)
) :Cursor{
    val datafileFilename: String
    val metafile: IsamMetaFileReader


    fun open()
    fun close()
    companion object {
        fun write(cursor: Cursor, datafilename: String)
    }
}
fun<T> IsamDataFile.use (block: (IsamDataFile) -> T): T {
    try {
        open()
        return block(this)
    } finally {
        close()
    }
}