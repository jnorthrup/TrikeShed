package borg.trikeshed.parse.confix

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.PolymorphicKind
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
// JSON rendering — proper JSON output for ConfixElement (replaces Map.toString)
// ─────────────────────────────────────────────────────────────────────────────

fun ConfixElement.toJsonString(): String = buildString { renderJson(this@toJsonString, this) }

private fun renderJson(e: ConfixElement, sb: StringBuilder) {
    when (e) {
        ConfixNull -> sb.append("null")
        is ConfixPrimitive -> {
            if (e.isString) {
                sb.append('"')
                for (c in e.content) when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> sb.append(c)
                }
                sb.append('"')
            } else {
                sb.append(e.content)
            }
        }
        is ConfixArray -> {
            sb.append('[')
            for (i in e.elements.indices) {
                if (i > 0) sb.append(',')
                renderJson(e.elements[i], sb)
            }
            sb.append(']')
        }
        is ConfixObject -> {
            sb.append('{')
            var first = true
            for ((k, v) in e.content) {
                if (!first) sb.append(',')
                first = false
                sb.append('"').append(k).append('"').append(':')
                renderJson(v, sb)
            }
            sb.append('}')
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CBOR byte encoding (canonical: sorted map keys, definite-length)
// Absorbed from ConfixCborEncoder.kt — single writer, no duplicate.
// ─────────────────────────────────────────────────────────────────────────────

private class ByteBuilder(capacity: Int = 32) {
    private var buf = ByteArray(capacity)
    var size = 0; private set
    fun write(b: Int) { ensure(size + 1); buf[size++] = b.toByte() }
    fun write(bytes: ByteArray) { ensure(size + bytes.size); bytes.copyInto(buf, size); size += bytes.size }
    private fun ensure(min: Int) {
        if (min > buf.size) {
            var n = buf.size * 2; if (n < min) n = min
            buf = buf.copyOf(n)
        }
    }
    fun toByteArray(): ByteArray = buf.copyOf(size)
}

private object CborWriter {
    fun emit(element: ConfixElement): ByteArray {
        val out = ByteBuilder()
        write(out, element)
        return out.toByteArray()
    }

    private fun write(out: ByteBuilder, e: ConfixElement) {
        when (e) {
            ConfixNull -> out.write(0xF6)
            is ConfixPrimitive -> {
                val v = e.content
                if (e.isString) {
                    val b = v.encodeToByteArray()
                    head(out, 3, b.size.toULong()); out.write(b)
                } else {
                    val bool = e.booleanOrNull
                    val ulong = v.toULongOrNull()
                    val long = v.toLongOrNull()
                    val dbl = v.toDoubleOrNull()
                    when {
                        bool != null -> out.write(if (bool) 0xF5 else 0xF4)
                        ulong != null -> head(out, 0, ulong)
                        long != null -> {
                            if (long >= 0) head(out, 0, long.toULong())
                            else head(out, 1, (-1L - long).toULong())
                        }
                        dbl != null -> {
                            out.write(0xFB)
                            val bits = dbl.toBits()
                            for (s in 56 downTo 0 step 8) out.write((bits ushr s).toInt() and 0xFF)
                        }
                        else -> {
                            val b = v.encodeToByteArray()
                            head(out, 2, b.size.toULong()); out.write(b)
                        }
                    }
                }
            }
            is ConfixArray -> {
                head(out, 4, e.size.toULong())
                e.forEach { write(out, it) }
            }
            is ConfixObject -> {
                head(out, 5, e.size.toULong())
                // Canonical: sort by encoded key bytes (length-first, then bytewise).
                val sorted = e.content.entries.map { (k, v) ->
                    val kb = k.encodeToByteArray()
                    val ek = ByteBuilder().also { head(it, 3, kb.size.toULong()); it.write(kb) }.toByteArray()
                    Triple(k, ek, v)
                }.sortedWith(Comparator { a, b ->
                    val lenCmp = a.second.size.compareTo(b.second.size)
                    if (lenCmp != 0) return@Comparator lenCmp
                    for (i in a.second.indices) {
                        val byteCmp = (a.second[i].toInt() and 0xFF).compareTo(b.second[i].toInt() and 0xFF)
                        if (byteCmp != 0) return@Comparator byteCmp
                    }
                    0
                })
                for ((_, ek, v) in sorted) {
                    out.write(ek)
                    write(out, v)
                }
            }
        }
    }

    private fun head(out: ByteBuilder, mt: Int, len: ULong) {
        val base = mt shl 5
        when {
            len <= 23u -> out.write(base or len.toInt())
            len <= 255u -> { out.write(base or 24); out.write(len.toInt()) }
            len <= 65535u -> {
                out.write(base or 25)
                out.write((len.toInt() ushr 8) and 0xFF)
                out.write(len.toInt() and 0xFF)
            }
            len <= 4294967295u -> {
                out.write(base or 26)
                out.write((len.toInt() ushr 24) and 0xFF)
                out.write((len.toInt() ushr 16) and 0xFF)
                out.write((len.toInt() ushr 8) and 0xFF)
                out.write(len.toInt() and 0xFF)
            }
            else -> {
                out.write(base or 27)
                for (s in 56 downTo 0 step 8) out.write((len shr s).toInt() and 0xFF)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CBOR byte decoding — single reader, absorbed from ConfixCborDecoder.kt
// ─────────────────────────────────────────────────────────────────────────────

private class ByteReader(private val bytes: ByteArray) {
    var pos = 0
    fun read(): Int {
        if (pos >= bytes.size) throw IllegalArgumentException("unexpected end of CBOR")
        return bytes[pos++].toInt() and 0xFF
    }
    fun read(n: Int): ByteArray {
        if (pos + n > bytes.size) throw IllegalArgumentException("unexpected end of CBOR")
        val r = bytes.copyOfRange(pos, pos + n); pos += n; return r
    }
}

private object CborReader {
    fun decode(bytes: ByteArray): ConfixElement = decodeElement(ByteReader(bytes))

    private fun decodeElement(r: ByteReader): ConfixElement {
        val init = r.read()
        val mt = init ushr 5
        val ai = init and 0x1F
        return when (mt) {
            0 -> ConfixPrimitive(arg(r, ai).toString(), false)
            1 -> ConfixPrimitive((-1L - arg(r, ai).toLong()).toString(), false)
            2 -> { val n = arg(r, ai).toInt(); ConfixPrimitive(r.read(n).decodeToString(), false) }
            3 -> { val n = arg(r, ai).toInt(); ConfixPrimitive(r.read(n).decodeToString(), true) }
            4 -> {
                val n = arg(r, ai).toInt()
                ConfixArray(List(n) { decodeElement(r) })
            }
            5 -> {
                val n = arg(r, ai).toInt()
                val m = LinkedHashMap<String, ConfixElement>(n)
                repeat(n) { m[(decodeElement(r) as ConfixPrimitive).content] = decodeElement(r) }
                ConfixObject(m)
            }
            7 -> when (ai) {
                20 -> ConfixPrimitive(false)
                21 -> ConfixPrimitive(true)
                22 -> ConfixNull
                27 -> {
                    var l = 0L; for (i in 0..7) l = (l shl 8) or r.read().toLong()
                    ConfixPrimitive(Double.fromBits(l).toString(), false)
                }
                else -> throw IllegalArgumentException("unsupported simple value: $ai")
            }
            else -> throw IllegalArgumentException("unsupported major type: $mt")
        }
    }

    private fun arg(r: ByteReader, ai: Int): ULong = when (ai) {
        in 0..23 -> ai.toULong()
        24 -> r.read().toULong()
        25 -> ((r.read() shl 8) or r.read()).toULong()
        26 -> {
            var v = 0L
            repeat(4) { v = (v shl 8) or r.read().toLong() }
            v.toULong()
        }
        27 -> {
            var v = 0UL
            repeat(8) { v = (v shl 8) or r.read().toULong() }
            v
        }
        else -> throw IllegalArgumentException("unsupported additional info: $ai")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Encoder: SerializationStrategy<T> → ConfixElement tree
// The SerialDescriptor projections drive the element-name oracle.
// ─────────────────────────────────────────────────────────────────────────────

class ConfixElementEncoder : AbstractEncoder() {
    override val serializersModule: SerializersModule = EmptySerializersModule()

    private sealed class Frame {
        class Obj(val map: MutableMap<String, ConfixElement>) : Frame()
        class Arr(val list: MutableList<ConfixElement>) : Frame()
        class MMap(val pairs: MutableList<Pair<String, ConfixElement>>) : Frame()
    }

    private val frames = ArrayDeque<Frame>()
    private var pendingKey: String? = null
    private var mapKey: String? = null
    private var root: ConfixElement? = null

    val result: ConfixElement get() = root ?: ConfixNull

    private fun emit(e: ConfixElement) {
        val f = frames.lastOrNull()
        when {
            f == null -> root = e
            f is Frame.Obj -> { f.map[pendingKey ?: error("no key")] = e; pendingKey = null }
            f is Frame.Arr -> f.list.add(e)
            f is Frame.MMap -> {
                if (mapKey == null) mapKey = (e as? ConfixPrimitive)?.content ?: error("map key must be string")
                else { f.pairs.add(mapKey!! to e); mapKey = null }
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
            else -> Frame.Obj(mutableMapOf())
        }
        frames.addLast(frame)
        return this
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        val f = frames.removeLast()
        val e = when (f) {
            is Frame.Obj -> ConfixObject(f.map)
            is Frame.Arr -> ConfixArray(f.list)
            is Frame.MMap -> ConfixObject(LinkedHashMap(f.pairs.toMap()))
        }
        emit(e)
    }

    override fun encodeNull() = emit(ConfixNull)
    override fun encodeBoolean(value: Boolean) = emit(ConfixPrimitive(value))
    override fun encodeByte(value: Byte) = emit(ConfixPrimitive(value))
    override fun encodeShort(value: Short) = emit(ConfixPrimitive(value))
    override fun encodeInt(value: Int) = emit(ConfixPrimitive(value))
    override fun encodeLong(value: Long) = emit(ConfixPrimitive(value))
    override fun encodeFloat(value: Float) = emit(ConfixPrimitive(value))
    override fun encodeDouble(value: Double) = emit(ConfixPrimitive(value))
    override fun encodeChar(value: Char) = emit(ConfixPrimitive(value.toString()))
    override fun encodeString(value: String) = emit(ConfixPrimitive(value))
    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) =
        emit(ConfixPrimitive(enumDescriptor.getElementName(index)))
}

// ─────────────────────────────────────────────────────────────────────────────
// Decoder: ConfixElement tree → DeserializationStrategy<T>
// Reifier: ConfixPrimitive.content is the materialised value oracle.
// ─────────────────────────────────────────────────────────────────────────────

class ConfixElementDecoder(private val root: ConfixElement) : AbstractDecoder() {
    override val serializersModule: SerializersModule = EmptySerializersModule()

    private var current: ConfixElement = root

    private class Frame(val element: ConfixElement) {
        val unconsumed: MutableMap<String, ConfixElement> =
            (element as? ConfixObject)?.content?.toMutableMap() ?: mutableMapOf()
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
        return when (f.element) {
            is ConfixObject -> {
                val entry = f.unconsumed.entries.firstOrNull() ?: return CompositeDecoder.DECODE_DONE
                current = entry.value
                f.unconsumed.remove(entry.key)
                (0 until descriptor.elementsCount).firstOrNull {
                    descriptor.getElementName(it) == entry.key
                } ?: CompositeDecoder.UNKNOWN_NAME
            }
            is ConfixArray -> {
                if (f.arrIdx >= f.element.size) return CompositeDecoder.DECODE_DONE
                current = f.element[f.arrIdx++]
                f.arrIdx - 1
            }
            else -> CompositeDecoder.DECODE_DONE
        }
    }

    override fun decodeSequentially(): Boolean = false
    override fun decodeNotNullMark(): Boolean = current !is ConfixNull
    override fun decodeNull(): Nothing? = null

    private fun prim(): ConfixPrimitive = current as? ConfixPrimitive
        ?: error("expected primitive, got ${current::class.simpleName}")

    override fun decodeBoolean(): Boolean = prim().booleanOrNull ?: false
    override fun decodeByte(): Byte = prim().content.toByte()
    override fun decodeShort(): Short = prim().content.toShort()
    override fun decodeInt(): Int = prim().content.toInt()
    override fun decodeLong(): Long = prim().content.toLong()
    override fun decodeFloat(): Float = prim().content.toFloat()
    override fun decodeDouble(): Double = prim().content.toDouble()
    override fun decodeChar(): Char = prim().content.first()
    override fun decodeString(): String = prim().content
    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int =
        (0 until enumDescriptor.elementsCount).firstOrNull {
            enumDescriptor.getElementName(it) == prim().content
        } ?: 0
}

// ─────────────────────────────────────────────────────────────────────────────
// ConfixFormat — the single BinaryFormat entry point.
// CBOR for bytes, JSON for strings. ConfixElement is the shared IR.
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalSerializationApi::class)
object ConfixFormat : BinaryFormat {
    override val serializersModule: SerializersModule = EmptySerializersModule()

    /** Encode a value to a ConfixElement tree (the shared IR). */
    fun <T> encodeToElement(serializer: SerializationStrategy<T>, value: T): ConfixElement {
        val enc = ConfixElementEncoder()
        enc.encodeSerializableValue(serializer, value)
        return enc.result
    }

    /** Decode a ConfixElement tree into a typed value. */
    fun <T> decodeFromElement(deserializer: DeserializationStrategy<T>, element: ConfixElement): T {
        val dec = ConfixElementDecoder(element)
        return dec.decodeSerializableValue(deserializer)
    }

    /** Encode to a JSON string. */
    fun <T> encodeToJsonString(serializer: SerializationStrategy<T>, value: T): String =
        encodeToElement(serializer, value).toJsonString()

    /** Encode a raw ConfixElement tree to canonical CBOR bytes. */
    fun encodeElementToCbor(element: ConfixElement): ByteArray = CborWriter.emit(element)

    /** Decode canonical CBOR bytes to a raw ConfixElement tree. */
    fun decodeCborToElement(bytes: ByteArray): ConfixElement = CborReader.decode(bytes)

    /** Encode to canonical CBOR bytes. */
    override fun <T> encodeToByteArray(serializer: SerializationStrategy<T>, value: T): ByteArray =
        CborWriter.emit(encodeToElement(serializer, value))

    /** Decode from canonical CBOR bytes. */
    override fun <T> decodeFromByteArray(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T =
        decodeFromElement(deserializer, CborReader.decode(bytes))
}
