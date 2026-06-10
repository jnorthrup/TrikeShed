package borg.trikeshed.couch.htx

/**
 * HTX block metadata — matches HAProxy struct htx_blk.
 * - addr: relative storage address of payload
 * - info: type (4 bits) | value_len (20 bits) | name_len (8 bits)
 */
data class HtxBlock(
    val addr: UInt,
    val info: UInt,
) {
    constructor(type: HtxBlockType, nameLen: Int, valueLen: Int, addr: UInt) : this(
        addr = addr,
        info = ((type.code.toUInt()) shl 28) or ((valueLen and 0xFFFFF).toUInt() shl 8) or ((nameLen and 0xFF).toUInt())
    )

    val blockType: HtxBlockType get() = HtxBlockType.fromCode(((info shr 28).toUByte()))
    val valueLen: Int get() = (info and 0x0FFFFFFFu).toInt() shr 8
    val nameLen: Int get() = ((info shr 8) and 0xFFu).toInt()
}
