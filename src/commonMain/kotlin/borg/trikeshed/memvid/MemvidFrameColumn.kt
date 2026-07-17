package borg.trikeshed.memvid

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.IOMemento

/**
 * Defines the stable ordinal-based schema for frames in a Memvid archive.
 */
enum class MemvidFrameColumn(val type: IOMemento) {
    DOCUMENT_ORDINAL(IOMemento.IoInt),
    FRAME_ORDINAL(IOMemento.IoInt),
    BYTE_OFFSET(IOMemento.IoInt),
    BYTE_LENGTH(IOMemento.IoInt),
    CHUNK_CID(IOMemento.IoString),
    PAYLOAD(IOMemento.IoBytes);

    val meta: ColumnMeta get() = ColumnMeta(name, type)
}

/**
 * Defines the stable ordinal-based schema for documents in a Memvid archive.
 */
enum class MemvidDocumentColumn(val type: IOMemento) {
    DOCUMENT_ORDINAL(IOMemento.IoInt),
    PATH(IOMemento.IoString),
    MEDIA_TYPE(IOMemento.IoString),
    BYTE_SIZE(IOMemento.IoInt),
    DOCUMENT_CID(IOMemento.IoString),
    FIRST_FRAME_ORDINAL(IOMemento.IoInt),
    FRAME_COUNT(IOMemento.IoInt);

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
