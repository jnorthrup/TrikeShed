package borg.trikeshed.memvid

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.isam.meta.IOMemento

/**
 * Defines the stable ordinal-based schema for frames in a Memvid archive.
 */
enum class MemvidFrameColumn(val type: IOMemento) {
    DOCUMENT_ORDINAL(IOMemento.IoInt),
    PAYLOAD(IOMemento.IoByteArray);

    val meta: ColumnMeta get() = ColumnMeta(name, type)
}

/**
 * Strong-typed index keys for the returned meta-series.
 */
enum class MemvidK {
    ArchiveId,
    ManifestCid,
    DocumentCount,
    FrameCount,
    Documents,
    Frames
}
