package borg.trikeshed.isam.meta

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

expect object PlatformCodec {
    val readBool: (ByteArray) -> Boolean
    val readByte: (ByteArray) -> Byte
    val readInt: (ByteArray) -> Int
    val readLong: (ByteArray) -> Long
    val readFloat: (ByteArray) -> Float
    val readDouble: (ByteArray) -> Double
    val readInstant: (ByteArray) -> Instant
    val readString: (ByteArray) -> String
    val readNothing: (ByteArray) -> Nothing?
    val writeBool: (Any?) -> ByteArray
    val writeByte: (Any?) -> ByteArray
    val writeInt: (Any?) -> ByteArray
    val writeLong: (Any?) -> ByteArray
    val writeFloat: (Any?) -> ByteArray
    val writeDouble: (Any?) -> ByteArray
    val writeInstant: (Any?) -> ByteArray

    val readShort: (ByteArray) -> Short
    val writeShort: (Any?) -> ByteArray
    fun createEncoder(
        type: IOMemento,
        size: Int
    ): (Any?) -> ByteArray

    fun createDecoder(
        type: IOMemento,
        size: Int
    ): (ByteArray) -> Any?

    val readLocalDate: (ByteArray) -> LocalDate
    val writeLocalDate: (Any?) -> ByteArray
    val writeString: (Any?) -> ByteArray
    val writeNothing: (Any?) -> ByteArray


}