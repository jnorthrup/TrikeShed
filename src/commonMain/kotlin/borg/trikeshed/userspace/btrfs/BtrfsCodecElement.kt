package borg.trikeshed.userspace.btrfs

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.userspace.context.AsyncContextKey
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext

/**
 * BtrfsCodec — little-endian primitive codec for btrfs on-disk format.
 *
 * Btrfs on-disk format is strictly little-endian, unlike network protocols.
 * This codec is deliberately lambda-shaped so callers can hoist the functions
 * into slab/ring writers without native FFI glue.
 */
interface BtrfsCodec {
    val readLong: (ByteArray, Int) -> Long
    val readULong: (ByteArray, Int) -> ULong
    val readInt: (ByteArray, Int) -> Int
    val readUInt: (ByteArray, Int) -> UInt
    val readShort: (ByteArray, Int) -> Short
    val readUShort: (ByteArray, Int) -> UShort

    val writeLong: (Long, ByteArray, Int) -> Unit
    val writeULong: (ULong, ByteArray, Int) -> Unit
    val writeInt: (Int, ByteArray, Int) -> Unit
    val writeUInt: (UInt, ByteArray, Int) -> Unit
    val writeShort: (Short, ByteArray, Int) -> Unit
    val writeUShort: (UShort, ByteArray, Int) -> Unit

    fun allocateLongBuffer(): ByteArray = ByteArray(8)
    fun allocateIntBuffer(): ByteArray = ByteArray(4)
    fun allocateShortBuffer(): ByteArray = ByteArray(2)
}

/** Canonical little-endian implementation for btrfs on-disk format. */
object BtrfsCodecLE : BtrfsCodec {
    override val readLong: (ByteArray, Int) -> Long = { buf, offset ->
        ((buf[offset + 7].toLong() and 0xFFL) shl 56) or
            ((buf[offset + 6].toLong() and 0xFFL) shl 48) or
            ((buf[offset + 5].toLong() and 0xFFL) shl 40) or
            ((buf[offset + 4].toLong() and 0xFFL) shl 32) or
            ((buf[offset + 3].toLong() and 0xFFL) shl 24) or
            ((buf[offset + 2].toLong() and 0xFFL) shl 16) or
            ((buf[offset + 1].toLong() and 0xFFL) shl 8) or
            (buf[offset].toLong() and 0xFFL)
    }

    override val readULong: (ByteArray, Int) -> ULong = { buf, offset -> readLong(buf, offset).toULong() }

    override val readInt: (ByteArray, Int) -> Int = { buf, offset ->
        ((buf[offset + 3].toInt() and 0xFF) shl 24) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            (buf[offset].toInt() and 0xFF)
    }

    override val readUInt: (ByteArray, Int) -> UInt = { buf, offset -> readInt(buf, offset).toUInt() }

    override val readShort: (ByteArray, Int) -> Short = { buf, offset ->
        (((buf[offset + 1].toInt() and 0xFF) shl 8) or
            (buf[offset].toInt() and 0xFF)).toShort()
    }

    override val readUShort: (ByteArray, Int) -> UShort = { buf, offset -> readShort(buf, offset).toUShort() }

    override val writeLong: (Long, ByteArray, Int) -> Unit = { value, buf, offset ->
        buf[offset] = (value and 0xFFL).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFFL).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFFL).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFFL).toByte()
        buf[offset + 4] = ((value shr 32) and 0xFFL).toByte()
        buf[offset + 5] = ((value shr 40) and 0xFFL).toByte()
        buf[offset + 6] = ((value shr 48) and 0xFFL).toByte()
        buf[offset + 7] = ((value shr 56) and 0xFFL).toByte()
    }

    override val writeULong: (ULong, ByteArray, Int) -> Unit = { value, buf, offset ->
        writeLong(value.toLong(), buf, offset)
    }

    override val writeInt: (Int, ByteArray, Int) -> Unit = { value, buf, offset ->
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    override val writeUInt: (UInt, ByteArray, Int) -> Unit = { value, buf, offset ->
        writeInt(value.toInt(), buf, offset)
    }

    override val writeShort: (Short, ByteArray, Int) -> Unit = { value, buf, offset ->
        val intValue = value.toInt()
        buf[offset] = (intValue and 0xFF).toByte()
        buf[offset + 1] = ((intValue shr 8) and 0xFF).toByte()
    }

    override val writeUShort: (UShort, ByteArray, Int) -> Unit = { value, buf, offset ->
        writeShort(value.toShort(), buf, offset)
    }
}

/** CCEK element that provides [BtrfsCodec] via coroutine context. */
open class BtrfsCodecElement(
    parentJob: Job? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob) {
    override val key: CoroutineContext.Key<*> = AsyncContextKey.BtrfsCodecKey

    val codec: BtrfsCodec = BtrfsCodecLE
}
