@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.isam.meta

import borg.trikeshed.cursor.TypeMemento
import borg.trikeshed.isam.meta.PlatformCodec.Companion.currentPlatformCodec
import borg.trikeshed.isam.meta.PlatformCodec.Companion.currentPlatformCodec.readInt
import borg.trikeshed.isam.meta.PlatformCodec.Companion.currentPlatformCodec.readLong
import borg.trikeshed.isam.meta.PlatformCodec.Companion.currentPlatformCodec.readUInt
import borg.trikeshed.isam.meta.PlatformCodec.Companion.currentPlatformCodec.readULong
import borg.trikeshed.isam.meta.PlatformCodec.Companion.currentPlatformCodec.writeInt
import borg.trikeshed.isam.meta.PlatformCodec.Companion.currentPlatformCodec.writeLong
import borg.trikeshed.isam.meta.PlatformCodec.Companion.currentPlatformCodec.writeUInt
import borg.trikeshed.isam.meta.PlatformCodec.Companion.currentPlatformCodec.writeULong
import borg.trikeshed.lib.*
import borg.trikeshed.lib.CharSeries
import kotlinx.datetime.*

enum class IOMemento(override val networkSize: Int? = null, val fromChars: (Series<Char>) -> Any) : TypeMemento {
    IoBoolean(1, {
        when (it[0]) {
            't' -> true
            'f' -> false
            else -> throw IllegalArgumentException("invalid boolean: $it")
        }
    }) {
        override fun createEncoder(i: Int): (Any?) -> ByteArray = writeBool
        override fun createDecoder(size: Int): (ByteArray) -> Any? = readBool
    },
    IoByte(1, {
        it.parseLong().toByte()
    }) {
        override fun createEncoder(i: Int): (Any?) -> ByteArray = writeByte
        override fun createDecoder(size: Int): (ByteArray) -> Any? = readByte
    },
    IoUByte(1, {
        it.parseLong().toUByte()
    }) {
        override fun createEncoder(i: Int): (Any?) -> ByteArray = writeUByte
        override fun createDecoder(size: Int): (ByteArray) -> Any? = readUByte
    },

    IoShort(2, { it.parseLong().toShort() }) {
        override fun createEncoder(i: Int): (Any?) -> ByteArray = currentPlatformCodec.writeShort as (Any?) -> ByteArray
        override fun createDecoder(size: Int): (ByteArray) -> Any? = currentPlatformCodec.readShort
    },

    IoInt(4, { it.parseLong().toInt() }) {
        override fun createEncoder(i: Int): (Any?) -> ByteArray = writeInt as (Any?) -> ByteArray
        override fun createDecoder(size: Int): (ByteArray) -> Any? = readInt
    },
    IoLong(8, Series<Char>::parseLong) {
        override fun createEncoder(i: Int): (Any?) -> ByteArray = writeLong as (Any?) -> ByteArray
        override fun createDecoder(size: Int): (ByteArray) -> Any? = readLong
    },
    IoUShort(2, { it.parseLong().toUShort() }) {
        override fun createEncoder(i: Int): (Any?) -> ByteArray =
            currentPlatformCodec.writeUShort as (Any?) -> ByteArray

        override fun createDecoder(size: Int): (ByteArray) -> Any? = currentPlatformCodec.readUShort
    },

    IoUInt(4, { it.parseLong().toUInt() }) {
        override fun createEncoder(i: Int): (Any?) -> ByteArray = writeUInt as (Any?) -> ByteArray
        override fun createDecoder(size: Int): (ByteArray) -> Any? = readUInt
    },
    IoULong(8, { it.parseLong().toULong() }) {
        override fun createEncoder(i: Int): (Any?) -> ByteArray = writeULong as (Any?) -> ByteArray
        override fun createDecoder(size: Int): (ByteArray) -> Any? = readULong
    },
    IoFloat(4, { it.parseDouble().toFloat() }) {
        override fun createEncoder(i: Int): (Any?) -> ByteArray = currentPlatformCodec.writeFloat as (Any?) -> ByteArray
        override fun createDecoder(size: Int): (ByteArray) -> Any? = currentPlatformCodec.readFloat
    },
    IoDouble(8, { it.parseDouble() }) {
        override fun createEncoder(i: Int): (Any?) -> ByteArray =
            currentPlatformCodec.writeDouble as (Any?) -> ByteArray

        override fun createDecoder(size: Int): (ByteArray) -> Any? = currentPlatformCodec.readDouble
    },
    IoLocalDate(8, { it.parseIsoDateTime() }) {
        override fun createEncoder(i: Int): (Any?) -> ByteArray = {
            //try a cast elvis first with Instant then with LocalDate
            val date = (it as? Instant)?.toLocalDateTime(TimeZone.UTC)?.date ?: it as LocalDate
//
//            val toEpochDays = (it as LocalDate).toEpochDays()
//            writeLong (toEpochDays.toLong())
            writeLong(date.toEpochDays().toLong())


        }

        override fun createDecoder(size: Int): (ByteArray) -> Any? = {
            val fromEpochDays = LocalDate.fromEpochDays(readLong(it).toInt())
            fromEpochDays
        }
    },

    /**
     * 12 bytes of storage, first epoch seconds Long , then nanos Int
     */
    IoInstant(12,
        { Instant.parse(it.toString()) }) {
        override fun createEncoder(i: Int): (Any?) -> ByteArray = { inst: Any? ->
            val instant = inst as Instant
            val epochSeconds = instant.epochSeconds
            val nanoAdjustment = instant.nanosecondsOfSecond
            writeLong(epochSeconds) + writeInt(nanoAdjustment)
        }

        override fun createDecoder(size: Int): (ByteArray) -> Any? = { bytes: ByteArray ->
            val epochSeconds = readLong(bytes)
            val nanoAdjustment = readInt(bytes.sliceArray(8..11))
            Instant.fromEpochSeconds(epochSeconds, nanoAdjustment)
        }
    },
    IoString(null, { it.asString() }) {
        override fun createEncoder(i: Int): (Any?) -> ByteArray = writeString

        override fun createDecoder(size: Int): (ByteArray) -> Any? = readString
    },
    IoCharSeries(null, ::CharSeries) {
        override fun createEncoder(i: Int): (Any?) -> ByteArray = writeCharSeries
        override fun createDecoder(size: Int): (ByteArray) -> Any? = readCharSeries
    },

    IoByteArray(null, { it.encodeToByteArray() }) {
        override fun createEncoder(i: Int): (Any?) -> ByteArray = writeByteArray
        override fun createDecoder(size: Int): (ByteArray) -> Any? = readByteArray
    },
    IoNothing(null, { "" }) {
        override fun createEncoder(i: Int): (Any?) -> ByteArray = { ByteArray(0) }
        override fun createDecoder(size: Int): (ByteArray) -> Any? = { ByteArray(0) }
    }
    ;

    abstract fun createEncoder(i: Int): (Any?) -> ByteArray
    abstract fun createDecoder(size: Int): (ByteArray) -> Any?

    companion object {
        val readCharSeries: (ByteArray) -> Series<Char> = { value: ByteArray -> value.decodeToChars() }
        val writeCharSeries: (Any?) -> ByteArray = { value: Any? -> (value as Series<Char>).encodeToByteArray() }
        val readByteSeries: (ByteArray) -> Series<Byte> = { value: ByteArray -> value.toSeries() }
        val writeByteSeries: (Any?) -> ByteArray = { value: Any? -> (value as Series<Byte>).toArray() }
        val readByteArray: (ByteArray) -> ByteArray = { value: ByteArray -> value }
        val writeByteArray: (Any?) -> ByteArray = { value: Any? -> value as ByteArray }

        val readString: (ByteArray) -> String = { value: ByteArray -> value.decodeToString() }
        val writeString: (Any?) -> ByteArray = { value: Any? -> (value as String).encodeToByteArray() }

        val readBool: (ByteArray) -> Boolean = { value: ByteArray -> value[0] == 1.toByte() }
        val writeBool: (Any?) -> ByteArray =
            { value: Any? -> ByteArray(1).apply { this[0] = if (value as Boolean) 1 else 0 } }
        val readByte: (ByteArray) -> Byte = { value: ByteArray -> value[0] }
        val writeByte: (Any?) -> ByteArray = { value: Any? -> ByteArray(1).apply { this[0] = value as Byte } }
        val readUByte: (ByteArray) -> UByte = { value: ByteArray -> value[0].toUByte() }
        val writeUByte: (Any?) -> ByteArray =
            { value: Any? -> ByteArray(1).apply { this[0] = (value as Byte) } }//take it on faith here
    }
}