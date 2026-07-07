package borg.trikeshed.isam

import borg.trikeshed.isam.meta.IsamMetaFileReader

class IsamDataFileBuilder {
    var datafileFilename: String = ""
    var metafileFilename: String? = null
    var useMonocursorGroupings: Boolean = true
    var varChars: Map<String, Int> = emptyMap()
    var operations: IsamOperations = defaultIsamOperations()

    fun build(): IsamDataFile {
        require(datafileFilename.isNotEmpty()) { "datafileFilename must not be empty" }
        val metafileLoc = metafileFilename ?: "$datafileFilename.meta"
        val metafileReader = IsamMetaFileReader(metafileLoc)
        return IsamDataFile(
            datafileFilename = datafileFilename,
            metafileFilename = metafileLoc,
            metafile = metafileReader,
            operations = operations
        )
    }
}

fun isamDataFile(block: IsamDataFileBuilder.() -> Unit): IsamDataFile =
    IsamDataFileBuilder().apply(block).build()
