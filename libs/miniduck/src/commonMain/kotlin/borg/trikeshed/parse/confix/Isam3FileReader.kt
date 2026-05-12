package borg.trikeshed.parse.confix

import borg.trikeshed.userspace.nio.file.Files
import borg.trikeshed.lib.Usable
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.isam.Isam3Layout
import borg.trikeshed.isam.Isam3Partition
import borg.trikeshed.isam.Isam3View
import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view

class Isam3FileReader private constructor(
    val metafileFilename: CharSequence,
    private val layout: Isam3Layout,
) : Usable {
    constructor(metafileFilename: CharSequence) : this(metafileFilename, Isam3Layout.read(metafileFilename))

    val version: Int get() = layout.version
    val partitions: Series<Isam3Partition> get() = layout.partitions
    val views: Series<Isam3View> get() = layout.views
    val viewNames: Series<CharSequence> get() = layout.views.view.map { it.name }.toSeries()
    val recordMeta: Series<RecordMeta> get() = layout.recordMeta()
    val logicalMeta: Series<ColumnMeta> get() = layout.logicalMeta()

    override fun open() {
        require(Files.exists(metafileFilename)) { "ISAM3 layout missing: $metafileFilename" }
    }

    override fun close() = Unit

    fun logicalNames(viewName: CharSequence? = null): Series<CharSequence> = layout.logicalNames(viewName)
    fun recordMeta(viewName: CharSequence? = null): Series<RecordMeta> = layout.recordMeta(viewName)
    fun logicalMeta(viewName: CharSequence? = null): Series<ColumnMeta> = layout.logicalMeta(viewName)

    companion object {
        fun read(metafilename: CharSequence): Isam3FileReader = Isam3FileReader(metafilename)
        fun parse(text: CharSequence): Isam3FileReader = Isam3FileReader("<text>", Isam3Layout.parse(text))
    }
}
