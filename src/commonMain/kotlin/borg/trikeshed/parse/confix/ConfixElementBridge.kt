package borg.trikeshed.parse.confix

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.modules.SerializersModule

fun <T> encodeToConfixElement(serializer: SerializationStrategy<T>, value: T): ConfixElement {
    val encoder = ConfixElementEncoder()
    encoder.encodeSerializableValue(serializer, value)
    return encoder.element
}

fun <T> decodeFromConfixElement(deserializer: DeserializationStrategy<T>, element: ConfixElement): T {
    val decoder = ConfixElementDecoder(element)
    return decoder.decodeSerializableValue(deserializer)
}

@OptIn(ExperimentalSerializationApi::class)
private class ConfixElementEncoder : AbstractEncoder() {
    override val serializersModule: SerializersModule = kotlinx.serialization.modules.EmptySerializersModule
    private var node: ConfixElement? = null
    val element: ConfixElement get() = node ?: ConfixNull

    private var mapKey: String? = null
    private var isList = false
    private var isMap = false
    
    private val listBuilder = mutableListOf<ConfixElement>()
    private val mapBuilder = mutableMapOf<String, ConfixElement>()

    override fun encodeValue(value: Any) {
        val prim = when (value) {
            is Boolean -> ConfixPrimitive(value)
            is Number -> ConfixPrimitive(value)
            is String -> ConfixPrimitive(value, isString = true)
            else -> ConfixPrimitive(value.toString(), isString = true)
        }
        putElement(prim)
    }
    
    override fun encodeNull() {
        putElement(ConfixNull)
    }

    private fun putElement(elem: ConfixElement) {
        when {
            isMap -> {
                val k = mapKey
                if (k == null) {
                    mapKey = (elem as? ConfixPrimitive)?.content ?: elem.toString()
                } else {
                    mapBuilder[k] = elem
                    mapKey = null
                }
            }
            isList -> {
                listBuilder.add(elem)
            }
            else -> {
                node = elem
            }
        }
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        val kind = descriptor.kind
        if (kind == StructureKind.LIST) {
            return ConfixElementEncoderList(this)
        } else if (kind == StructureKind.MAP || kind == StructureKind.CLASS || kind == StructureKind.OBJECT) {
            return ConfixElementEncoderObject(this, descriptor)
        }
        return super.beginStructure(descriptor)
    }

    private class ConfixElementEncoderList(val parent: ConfixElementEncoder) : AbstractEncoder() {
        override val serializersModule: SerializersModule get() = parent.serializersModule
        private val listBuilder = mutableListOf<ConfixElement>()

        override fun encodeValue(value: Any) {
            val prim = when (value) {
                is Boolean -> ConfixPrimitive(value)
                is Number -> ConfixPrimitive(value)
                is String -> ConfixPrimitive(value, isString = true)
                else -> ConfixPrimitive(value.toString(), isString = true)
            }
            listBuilder.add(prim)
        }
        
        override fun encodeNull() {
            listBuilder.add(ConfixNull)
        }

        override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
            val child = ConfixElementEncoder()
            return object : CompositeEncoder by child {
                override fun endStructure(descriptor: SerialDescriptor) {
                    child.endStructure(descriptor)
                    listBuilder.add(child.element)
                }
            }
        }
        
        override fun <T> encodeSerializableElement(descriptor: SerialDescriptor, index: Int, serializer: SerializationStrategy<T>, value: T) {
            val child = ConfixElementEncoder()
            child.encodeSerializableValue(serializer, value)
            listBuilder.add(child.element)
        }

        override fun endStructure(descriptor: SerialDescriptor) {
            parent.putElement(ConfixArray(listBuilder))
        }
    }

    private class ConfixElementEncoderObject(val parent: ConfixElementEncoder, val descriptor: SerialDescriptor) : AbstractEncoder() {
        override val serializersModule: SerializersModule get() = parent.serializersModule
        private val mapBuilder = mutableMapOf<String, ConfixElement>()
        private var mapKey: String? = null

        override fun encodeValue(value: Any) {
            val prim = when (value) {
                is Boolean -> ConfixPrimitive(value)
                is Number -> ConfixPrimitive(value)
                is String -> ConfixPrimitive(value, isString = true)
                else -> ConfixPrimitive(value.toString(), isString = true)
            }
            putElement(prim)
        }
        
        override fun encodeNull() {
            putElement(ConfixNull)
        }

        private fun putElement(elem: ConfixElement) {
            if (descriptor.kind == StructureKind.MAP) {
                val k = mapKey
                if (k == null) {
                    mapKey = (elem as? ConfixPrimitive)?.content ?: elem.toString()
                } else {
                    mapBuilder[k] = elem
                    mapKey = null
                }
            }
        }
        
        override fun <T> encodeSerializableElement(descriptor: SerialDescriptor, index: Int, serializer: SerializationStrategy<T>, value: T) {
            val k = descriptor.getElementName(index)
            val child = ConfixElementEncoder()
            child.encodeSerializableValue(serializer, value)
            mapBuilder[k] = child.element
        }

        override fun endStructure(descriptor: SerialDescriptor) {
            parent.putElement(ConfixObject(mapBuilder))
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
private class ConfixElementDecoder(val element: ConfixElement) : AbstractDecoder() {
    override val serializersModule: SerializersModule = kotlinx.serialization.modules.EmptySerializersModule
    
    override fun decodeValue(): Any {
        if (element is ConfixPrimitive) {
            if (element.booleanOrNull != null) return element.booleanOrNull!!
            element.content.toLongOrNull()?.let { return it }
            element.content.toDoubleOrNull()?.let { return it }
            return element.content
        }
        return element.toString()
    }
    
    override fun decodeBoolean(): Boolean {
        if (element is ConfixPrimitive) return element.booleanOrNull ?: false
        return false
    }
    
    override fun decodeByte(): Byte = decodeLong().toByte()
    override fun decodeShort(): Short = decodeLong().toShort()
    override fun decodeInt(): Int = decodeLong().toInt()
    override fun decodeLong(): Long = (element as? ConfixPrimitive)?.content?.toLongOrNull() ?: 0L
    
    override fun decodeFloat(): Float = decodeDouble().toFloat()
    override fun decodeDouble(): Double = (element as? ConfixPrimitive)?.content?.toDoubleOrNull() ?: 0.0
    
    override fun decodeChar(): Char = decodeString().firstOrNull() ?: '\u0000'
    override fun decodeString(): String = (element as? ConfixPrimitive)?.content ?: ""
    
    override fun decodeNotNullMark(): Boolean = element !is ConfixNull

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        val kind = descriptor.kind
        if (kind == StructureKind.LIST && element is ConfixArray) {
            return ConfixElementDecoderList(element)
        } else if ((kind == StructureKind.MAP || kind == StructureKind.CLASS || kind == StructureKind.OBJECT) && element is ConfixObject) {
            return ConfixElementDecoderObject(element, descriptor)
        }
        return super.beginStructure(descriptor)
    }

    private class ConfixElementDecoderList(val element: ConfixArray) : AbstractDecoder() {
        override val serializersModule: SerializersModule = kotlinx.serialization.modules.EmptySerializersModule
        private var currentIndex = 0
        
        override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
            if (currentIndex < element.size) return currentIndex
            return CompositeDecoder.DECODE_DONE
        }
        
        override fun <T> decodeSerializableElement(descriptor: SerialDescriptor, index: Int, deserializer: DeserializationStrategy<T>, previousValue: T?): T {
            val child = element[currentIndex++]
            return ConfixElementDecoder(child).decodeSerializableValue(deserializer)
        }
    }

    private class ConfixElementDecoderObject(val element: ConfixObject, val descriptor: SerialDescriptor) : AbstractDecoder() {
        override val serializersModule: SerializersModule = kotlinx.serialization.modules.EmptySerializersModule
        private var currentIndex = 0
        private val keys = element.keys.toList()
        private var isMap = descriptor.kind == StructureKind.MAP
        
        override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
            if (isMap) {
                if (currentIndex < keys.size * 2) return currentIndex
                return CompositeDecoder.DECODE_DONE
            } else {
                while (currentIndex < descriptor.elementsCount) {
                    val name = descriptor.getElementName(currentIndex)
                    if (element.containsKey(name)) {
                        return currentIndex
                    }
                    currentIndex++
                }
                return CompositeDecoder.DECODE_DONE
            }
        }
        
        override fun decodeStringElement(descriptor: SerialDescriptor, index: Int): String {
            if (isMap) {
                val keyIndex = index / 2
                if (index % 2 == 0) return keys[keyIndex]
                return (element[keys[keyIndex]] as? ConfixPrimitive)?.content ?: ""
            }
            val name = descriptor.getElementName(index)
            return (element[name] as? ConfixPrimitive)?.content ?: ""
        }
        
        override fun <T> decodeSerializableElement(descriptor: SerialDescriptor, index: Int, deserializer: DeserializationStrategy<T>, previousValue: T?): T {
            if (isMap) {
                val keyIndex = index / 2
                if (index % 2 == 0) {
                    currentIndex++
                    return ConfixElementDecoder(ConfixPrimitive(keys[keyIndex], true)).decodeSerializableValue(deserializer)
                }
                val child = element[keys[keyIndex]]!!
                currentIndex++
                return ConfixElementDecoder(child).decodeSerializableValue(deserializer)
            } else {
                val name = descriptor.getElementName(index)
                val child = element[name] ?: ConfixNull
                currentIndex++
                return ConfixElementDecoder(child).decodeSerializableValue(deserializer)
            }
        }
    }
}
