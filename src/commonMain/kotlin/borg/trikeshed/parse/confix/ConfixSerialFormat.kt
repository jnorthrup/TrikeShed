package borg.trikeshed.parse.confix

import borg.trikeshed.collections.associative.Cbor
import borg.trikeshed.collections.associative.Item
import borg.trikeshed.collections.associative.itemArrayOf
import borg.trikeshed.collections.associative.itemMapOf
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

// ─────────────────────────────────────────────────────────────────────────────
// SerialDescriptor → MetaSeries projections
// The descriptor IS a MetaSeries: domain = element indices, oracle = getElementXxx
// ─────────────────────────────────────────────────────────────────────────────

/** Element names as a Series — the descriptor's element-index domain projected to strings. */
val SerialDescriptor.elementNames: Series<String>
    get() = elementsCount j { getElementName(it) }

/** Child descriptors as a Series — recursive projection through the type tree. */
val SerialDescriptor.elementDescriptors: Series<SerialDescriptor>
    get() = elementsCount j { getElementDescriptor(it) }

/** Optional flags as a Series. */
val SerialDescriptor.elementOptionals: Series<Boolean>
    get() = elementsCount j { isElementOptional(it) }

// ─────────────────────────────────────────────────────────────────────────────
// JSON rendering for Item — proper JSON output (replaces Map.toString slop)
// ─────────────────────────────────────────────────────────────────────────────

fun Item.toJsonString(): String = buildString { renderJson(this@toJsonString, this) }

private fun renderJson(item: Item, sb: StringBuilder) {
    when (item) {
        Item.Nil -> sb.append("null")
        is Item.Str -> {
            sb.append('"')
            for (c in item.value) when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
            sb.append('"')
        }
        is Item.Num -> sb.append(item.value)
        is Item.Flt -> sb.append(item.value)
        is Item.Bool -> sb.append(item.value)
        is Item.Bin -> {
            sb.append('"')
            for (b in item.value) {
                sb.append(HEX_DIGITS[(b.toInt() ushr 4) and 0xF])
                sb.append(HEX_DIGITS[b.toInt() and 0xF])
            }
            sb.append('"')
        }
        is Item.Arr -> {
            sb.append('[')
            for (i in 0 until item.size) {
                if (i > 0) sb.append(',')
                renderJson(item[i], sb)
            }
            sb.append(']')
        }
        is Item.Map -> {
            sb.append('{')
            val keys = item.keys()
            val values = item.values()
            for (i in 0 until keys.size) {
                if (i > 0) sb.append(',')
                sb.append('"').append(keys[i]).append('"').append(':')
                renderJson(values[i], sb)
            }
            sb.append('}')
        }
        is Item.Tag -> renderJson(item.item, sb)
    }
}

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

// ─────────────────────────────────────────────────────────────────────────────
// Encoder: SerializationStrategy<T> → Item tree → Cbor.encode
// Single CBOR path: collections.associative.Cbor.
// ─────────────────────────────────────────────────────────────────────────────

class ConfixItemEncoder : AbstractEncoder() {
    override val serializersModule: SerializersModule = EmptySerializersModule()

    private sealed class Frame {
        class Obj(val entries: MutableList<Join<String, Item>>) : Frame()
        class Arr(val items: MutableList<Item>) : Frame()
        class MMap(val pairs: MutableList<Join<String, Item>>) : Frame()
    }

    private val frames = ArrayDeque<Frame>()
    private var pendingKey: String? = null
    private var mapKey: String? = null
    private var root: Item? = null

    val result: Item get() = root ?: Item.Nil

    private fun emit(item: Item) {
        val f = frames.lastOrNull()
        when {
            f == null -> root = item
            f is Frame.Obj -> { f.entries.add((pendingKey ?: error("no key")) j item); pendingKey = null }
            f is Frame.Arr -> f.items.add(item)
            f is Frame.MMap -> {
                if (mapKey == null) mapKey = (item as? Item.Str)?.value ?: error("map key must be string")
                else { f.pairs.add(mapKey!! j item); mapKey = null }
            }
        }
    }

    override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
        val f = frames.lastOrNull()
        if (f is Frame.Obj) pendingKey = descriptor.getElementName(index)
        return true
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        val frame = when (descriptor.kind) {
            StructureKind.LIST -> Frame.Arr(mutableListOf())
            StructureKind.MAP -> Frame.MMap(mutableListOf())
            else -> Frame.Obj(mutableListOf())
        }
        frames.addLast(frame)
        return this
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        val f = frames.removeLast()
        val item = when (f) {
            is Frame.Obj -> {
                // Canonical: sort by key for deterministic output.
                val sorted: List<Join<String, Item>> = f.entries.sortedBy { e -> e.a }
                Item.Map(sorted.size j { i: Int -> sorted[i] })
            }
            is Frame.Arr -> Item.Arr(f.items.size j { i: Int -> f.items[i] })
            is Frame.MMap -> {
                val sorted: List<Join<String, Item>> = f.pairs.sortedBy { e -> e.a }
                Item.Map(sorted.size j { i: Int -> sorted[i] })
            }
        }
        emit(item)
    }

    override fun encodeNull() = emit(Item.Nil)
    override fun encodeBoolean(value: Boolean) = emit(Item.Bool(value))
    override fun encodeByte(value: Byte) = emit(Item.Num(value.toLong()))
    override fun encodeShort(value: Short) = emit(Item.Num(value.toLong()))
    override fun encodeInt(value: Int) = emit(Item.Num(value.toLong()))
    override fun encodeLong(value: Long) = emit(Item.Num(value))
    override fun encodeFloat(value: Float) = emit(Item.Flt(value.toDouble()))
    override fun encodeDouble(value: Double) = emit(Item.Flt(value))
    override fun encodeChar(value: Char) = emit(Item.Str(value.toString()))
    override fun encodeString(value: String) = emit(Item.Str(value))
    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) =
        emit(Item.Str(enumDescriptor.getElementName(index)))
}

// ─────────────────────────────────────────────────────────────────────────────
// Decoder: Cbor.decode → Item tree → DeserializationStrategy<T>
// ─────────────────────────────────────────────────────────────────────────────

class ConfixItemDecoder(private val root: Item) : AbstractDecoder() {
    override val serializersModule: SerializersModule = EmptySerializersModule()

    private var current: Item = root

    private class Frame(val item: Item) {
        val keys: Series<String>? = (item as? Item.Map)?.keys()
        val values: Series<Item>? = (item as? Item.Map)?.values()
        var mapIdx = 0
        var arrIdx = 0
    }

    private val frames = ArrayDeque<Frame>()

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        frames.addLast(Frame(current))
        return this
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        frames.removeLast()
    }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        val f = frames.lastOrNull() ?: return CompositeDecoder.DECODE_DONE
        return when (f.item) {
            is Item.Map -> {
                if (f.mapIdx >= f.item.size) return CompositeDecoder.DECODE_DONE
                val key = f.keys!![f.mapIdx]
                current = f.values!![f.mapIdx]
                f.mapIdx++
                (0 until descriptor.elementsCount).firstOrNull {
                    descriptor.getElementName(it) == key
                } ?: CompositeDecoder.UNKNOWN_NAME
            }
            is Item.Arr -> {
                if (f.arrIdx >= f.item.size) return CompositeDecoder.DECODE_DONE
                current = f.item[f.arrIdx++]
                f.arrIdx - 1
            }
            else -> CompositeDecoder.DECODE_DONE
        }
    }

    override fun decodeSequentially(): Boolean = false
    override fun decodeNotNullMark(): Boolean = current !is Item.Nil
    override fun decodeNull(): Nothing? = null

    override fun decodeBoolean(): Boolean = (current as? Item.Bool)?.value ?: false
    override fun decodeByte(): Byte = (current as? Item.Num)?.value?.toByte()
        ?: (current as? Item.Str)?.value?.toByte() ?: 0
    override fun decodeShort(): Short = (current as? Item.Num)?.value?.toShort()
        ?: (current as? Item.Str)?.value?.toShort() ?: 0
    override fun decodeInt(): Int = (current as? Item.Num)?.value?.toInt()
        ?: (current as? Item.Str)?.value?.toInt() ?: 0
    override fun decodeLong(): Long = (current as? Item.Num)?.value
        ?: (current as? Item.Str)?.value?.toLong() ?: 0L
    override fun decodeFloat(): Float = (current as? Item.Flt)?.value?.toFloat()
        ?: (current as? Item.Num)?.value?.toFloat()
        ?: (current as? Item.Str)?.value?.toFloat() ?: 0f
    override fun decodeDouble(): Double = (current as? Item.Flt)?.value
        ?: (current as? Item.Num)?.value?.toDouble()
        ?: (current as? Item.Str)?.value?.toDouble() ?: 0.0
    override fun decodeChar(): Char = (current as? Item.Str)?.value?.first()
        ?: (current as? Item.Num)?.value?.toInt()?.toChar() ?: '\u0000'
    override fun decodeString(): String = (current as? Item.Str)?.value
        ?: (current as? Item.Num)?.value?.toString()
        ?: (current as? Item.Flt)?.value?.toString()
        ?: (current as? Item.Bool)?.value?.toString() ?: ""
    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int =
        (0 until enumDescriptor.elementsCount).firstOrNull {
            enumDescriptor.getElementName(it) == (current as? Item.Str)?.value
        } ?: 0
}

// ─────────────────────────────────────────────────────────────────────────────
// ConfixFormat — the single BinaryFormat entry point.
// CBOR via collections.associative.Cbor (the one RFC 8949 implementation).
// JSON via Item.toJsonString.
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalSerializationApi::class)
object ConfixFormat : BinaryFormat {
    override val serializersModule: SerializersModule = EmptySerializersModule()

    /** Encode a value to an Item tree. */
    fun <T> encodeToItem(serializer: SerializationStrategy<T>, value: T): Item {
        val enc = ConfixItemEncoder()
        enc.encodeSerializableValue(serializer, value)
        return enc.result
    }

    /** Decode an Item tree into a typed value. */
    fun <T> decodeFromItem(deserializer: DeserializationStrategy<T>, item: Item): T {
        val dec = ConfixItemDecoder(item)
        return dec.decodeSerializableValue(deserializer)
    }

    /** Encode to a JSON string. */
    fun <T> encodeToJsonString(serializer: SerializationStrategy<T>, value: T): String =
        encodeToItem(serializer, value).toJsonString()

    /** Encode to canonical CBOR bytes (single path: Cbor.encode). */
    override fun <T> encodeToByteArray(serializer: SerializationStrategy<T>, value: T): ByteArray =
        Cbor.encode(encodeToItem(serializer, value))

    /** Decode from canonical CBOR bytes (single path: Cbor.decode). */
    override fun <T> decodeFromByteArray(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T =
        decodeFromItem(deserializer, Cbor.decode(bytes))
}
