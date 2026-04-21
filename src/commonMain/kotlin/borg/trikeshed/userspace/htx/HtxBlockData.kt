package borg.trikeshed.userspace.htx

/**
 * A single block in an HTX message — discriminated union over block types.
 */
sealed class HtxBlockData {
    abstract val blockType: HtxBlockType

    data class StartLine(val sl: HtxStartLine) : HtxBlockData() {
        override val blockType: HtxBlockType get() =
            if (sl.isRequest) HtxBlockType.ReqSl else HtxBlockType.ResSl
    }

    data class Header(val name: ByteArray, val value: ByteArray) : HtxBlockData() {
        override val blockType: HtxBlockType get() = HtxBlockType.Hdr
    }

    data class Data(val bytes: ByteArray) : HtxBlockData() {
        override val blockType: HtxBlockType get() = HtxBlockType.Data
    }

    data class Trailer(val name: ByteArray, val value: ByteArray) : HtxBlockData() {
        override val blockType: HtxBlockType get() = HtxBlockType.Tlr
    }

    data object EndHeaders : HtxBlockData() {
        override val blockType: HtxBlockType get() = HtxBlockType.Eoh
    }

    data object EndTrailers : HtxBlockData() {
        override val blockType: HtxBlockType get() = HtxBlockType.Eot
    }
}
