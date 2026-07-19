package borg.trikeshed.treedoc

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.isam.meta.IOMemento

/**
 * Defines the stable ordinal-based schema for frames in a TreeDoc archive.
 */
enum class TreeDocFrameColumn(val type: IOMemento) {
    DOCUMENT_ORDINAL(IOMemento.IoInt),
    PAYLOAD(IOMemento.IoByteArray);

    val meta: ColumnMeta get() = ColumnMeta(name, type)
}

/**
 * Strong-typed index keys for the returned meta-series.
 */
enum class TreeDocK {
    ArchiveId,
    ManifestCid,
    DocumentCount,
    FrameCount,
    Documents,
    Frames
}
