package borg.trikeshed.relaxfactory

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.parse.jsonOf
import com.oracle.truffle.api.Assumption
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.Truffle

/** Ordered Confix field names and tags, independent of source offsets and values. */
class ConfixShape(val names: Series<String>) {
    fun slot(name: String): Int = names.view.indexOf(name)

    companion object {
        const val CAPACITY = 256
        data class Layout(val tag: IOMemento, val fields: List<Pair<String, Layout>>, val elements: List<Layout>)

        val shapes = object : LinkedHashMap<Layout, ConfixShape>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Layout, ConfixShape>): Boolean =
                size > CAPACITY
        }

        fun layout(row: RowVec, src: Series<Byte>): Layout = when (row.tag) {
            IOMemento.IoObject -> Layout(row.tag, List(row.kids.size / 2) { slot ->
                (row.kids[slot * 2].reify(src) as String) to layout(row.kids[slot * 2 + 1], src)
            }, emptyList())
            IOMemento.IoArray -> Layout(row.tag, emptyList(), row.kids.view.map { layout(it, src) })
            else -> Layout(row.tag, emptyList(), emptyList())
        }

        @Synchronized
        fun of(doc: ConfixDoc): ConfixShape {
            val root = requireNotNull(doc.root) { "Confix receiver requires an object" }
            require(root.tag == IOMemento.IoObject) { "Confix receiver requires an object" }
            val kids = root.kids
            require(kids.size % 2 == 0) { "Confix object has an incomplete field" }
            val fields = List(kids.size / 2) { slot ->
                require(kids[slot * 2].tag == IOMemento.IoString) { "Confix object field is not a string" }
                (kids[slot * 2].reify(doc.src) as String) to kids[slot * 2 + 1].tag
            }
            require(fields.map { it.first }.toSet().size == fields.size) { "Duplicate Confix object field" }
            return shapes.getOrPut(layout(root, doc.src)) {
                ConfixShape(fields.size j { fields[it].first })
            }
        }
    }
}

/** The source and its index remain the receiver; a Map is materialized only at a host boundary. */
class ConfixBean(doc: ConfixDoc) {
    class State(val doc: ConfixDoc, val shape: ConfixShape, val version: Long, val assumption: Assumption)

    @Volatile
    var state: State = State(doc, ConfixShape.of(doc), 0, Truffle.getRuntime().createAssumption("confix:0"))
        private set

    val document: ConfixDoc get() = state.doc
    val shape: ConfixShape get() = state.shape
    val version: Long get() = state.version
    val assumption: Assumption get() = state.assumption

    fun get(name: String, type: Class<*> = Any::class.java): Any? {
        val current = state
        return read(current, current.shape.slot(name), type)
    }

    fun read(current: State, slot: Int, type: Class<*>): Any? {
        val row = if (slot < 0) null else current.doc.root!!.kids[slot * 2 + 1]
        val value = if (row == null) null else numeric(row, current.doc.src)?.let { java.math.BigDecimal(it) }
            ?: reify(row, current.doc.src)
        return if (type === Any::class.java && value is java.math.BigDecimal)
            number(value.toString()) else ConfixTypes.coerce(value, type)
    }

    fun fields(): Map<String, Any?> = fields(state)

    fun fields(current: State): Map<String, Any?> =
        current.shape.names.view.mapIndexed { slot, name -> name to read(current, slot, Any::class.java) }.toMap()

    @Synchronized
    fun replace(fields: Map<String, Any?>) = replace(from(fields).document)

    fun at(path: List<Any>): Any? {
        val current = state
        val cell = current.doc.docAt(*path.toTypedArray()) ?: return null
        return reify(cell.row, current.doc.src)
    }

    @Synchronized
    fun put(path: List<Any>, value: Any?) {
        require(path.isNotEmpty()) { "Confix property path is empty" }
        @Suppress("UNCHECKED_CAST")
        replace(update(fields(), path, 0, ConfixTypes.document(value), false) as Map<String, Any?>)
    }

    @Synchronized
    fun remove(path: List<Any>): Boolean {
        if (document.docAt(*path.toTypedArray()) == null) return false
        @Suppress("UNCHECKED_CAST")
        replace(update(fields(), path, 0, null, true) as Map<String, Any?>)
        return true
    }

    @Synchronized
    fun set(name: String, value: Any?) {
        val fields = fields().toMutableMap()
        fields[name] = ConfixTypes.document(value)
        replace(from(fields).document)
    }

    @Synchronized
    fun remove(name: String): Boolean {
        if (state.shape.slot(name) < 0) return false
        val fields = fields().toMutableMap()
        fields.remove(name)
        replace(from(fields).document)
        return true
    }

    @Synchronized
    fun replace(doc: ConfixDoc) {
        val shape = ConfixShape.of(doc)
        CompilerDirectives.transferToInterpreterAndInvalidate()
        val prior = state
        prior.assumption.invalidate()
        val version = prior.version + 1
        state = State(doc, shape, version, Truffle.getRuntime().createAssumption("confix:$version"))
    }

    companion object {
        fun from(fields: Map<String, Any?>): ConfixBean = from(jsonOf(ConfixTypes.document(fields)))
        fun from(text: String): ConfixBean = ConfixBean(confixDoc(text))

        fun numeric(row: RowVec, src: Series<Byte>): String? {
            if (row.tag != IOMemento.IoDouble) return null
            val first = src[row.open].toInt().toChar()
            if (first != '-' && first !in '0'..'9') return null
            return ByteArray(row.close - row.open + 1) { src[row.open + it] }.decodeToString()
        }

        fun number(text: String): Number {
            val decimal = java.math.BigDecimal(text)
            return try { decimal.longValueExact() } catch (_: ArithmeticException) {
                if (decimal.stripTrailingZeros().scale() <= 0) decimal.toBigIntegerExact()
                else decimal.toDouble().takeIf { it.isFinite() && java.math.BigDecimal.valueOf(it).compareTo(decimal) == 0 } ?: decimal
            }
        }

        fun update(container: Any?, path: List<Any>, depth: Int, value: Any?, delete: Boolean): Any? {
            val key = path[depth]
            val last = depth == path.lastIndex
            return when (container) {
                is Map<*, *> -> {
                    require(key is String) { "Confix object property requires a string" }
                    val fields = container.entries.associate { it.key as String to it.value }.toMutableMap()
                    if (last && delete) fields.remove(key)
                    else fields[key] = if (last) value else update(fields[key], path, depth + 1, value, delete)
                    fields
                }
                is List<*> -> {
                    require(key is Int && key in container.indices) { "Confix array index out of bounds" }
                    val elements = container.toMutableList()
                    if (last && delete) elements.removeAt(key)
                    else elements[key] = if (last) value else update(elements[key], path, depth + 1, value, delete)
                    elements
                }
                else -> throw IllegalArgumentException("Confix property path crosses a scalar")
            }
        }

        fun reify(row: RowVec, src: Series<Byte>): Any? = when (row.tag) {
            IOMemento.IoObject -> {
                val kids = row.kids
                List(kids.size / 2) { slot ->
                    (kids[slot * 2].reify(src) as String) to reify(kids[slot * 2 + 1], src)
                }.toMap()
            }
            IOMemento.IoArray -> row.kids.view.map { reify(it, src) }
            IOMemento.IoDouble -> numeric(row, src)?.let(::number) ?: row.reify(src)
            else -> row.reify(src)
        }
    }
}

/** RequestFactory's declared Java type is the conversion boundary. */
object ConfixTypes {
    fun coerce(value: Any?, type: Class<*>): Any? {
        if (value == null) {
            require(!type.isPrimitive) { "Null Confix value for ${type.name}" }
            return null
        }
        if (type.isInstance(value)) return value
        return when (type) {
            java.lang.Integer.TYPE, java.lang.Integer::class.java -> integral(value, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            java.lang.Long.TYPE, java.lang.Long::class.java -> integral(value, Long.MIN_VALUE, Long.MAX_VALUE)
            java.lang.Short.TYPE, java.lang.Short::class.java -> integral(value, Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toShort()
            java.lang.Byte.TYPE, java.lang.Byte::class.java -> integral(value, Byte.MIN_VALUE.toLong(), Byte.MAX_VALUE.toLong()).toByte()
            java.lang.Double.TYPE, java.lang.Double::class.java -> (value as? Number)?.toDouble()
                ?: decimal(value).toDouble()
            java.lang.Float.TYPE, java.lang.Float::class.java -> (value as? Number)?.toFloat()
                ?: decimal(value).toFloat()
            java.math.BigDecimal::class.java -> decimal(value)
            java.math.BigInteger::class.java -> try { decimal(value).toBigIntegerExact() } catch (e: ArithmeticException) {
                throw IllegalArgumentException("Confix value is not an exact integer", e)
            }
            java.util.Date::class.java -> java.util.Date(integral(value, Long.MIN_VALUE, Long.MAX_VALUE))
            java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> value as? Boolean
                ?: throw IllegalArgumentException("Confix value is not boolean")
            java.lang.Character.TYPE, java.lang.Character::class.java -> (value as? String)?.singleOrNull()
                ?: throw IllegalArgumentException("Confix value is not a character")
            ConfixBean::class.java -> {
                @Suppress("UNCHECKED_CAST")
                ConfixBean.from(value as? Map<String, Any?> ?: throw IllegalArgumentException("Confix value is not an object"))
            }
            else -> if (type.isEnum && value is String) type.enumConstants.firstOrNull { (it as Enum<*>).name == value }
                ?: throw IllegalArgumentException("Unknown ${type.name} value '$value'")
            else throw IllegalArgumentException("Confix ${value.javaClass.name} cannot be read as ${type.name}")
        }
    }

    fun integral(value: Any, min: Long, max: Long): Long {
        val decimal = decimal(value)
        val result = try { decimal.longValueExact() } catch (e: ArithmeticException) {
            throw IllegalArgumentException("Confix value is not an exact integer", e)
        }
        require(result in min..max) { "Confix integer exceeds the declared type" }
        return result
    }

    fun decimal(value: Any): java.math.BigDecimal = when (value) {
        is java.math.BigDecimal -> value
        is Number, is String -> try { java.math.BigDecimal(value.toString()) } catch (e: NumberFormatException) {
            throw IllegalArgumentException("Confix value is not numeric", e)
        }
        else -> throw IllegalArgumentException("Confix value is not numeric")
    }

    fun document(value: Any?): Any? = when (value) {
        is java.math.BigDecimal, is java.math.BigInteger -> value.toString()
        is java.util.Date -> value.time
        null, is String, is Boolean, is Number -> value
        is Char -> value.toString()
        is Enum<*> -> value.name
        is ConfixBean -> value.fields()
        is Map<*, *> -> value.entries.associate { (key, field) ->
            require(key is String) { "Confix object field is not a string" }
            key to document(field)
        }
        is Iterable<*> -> value.map(::document)
        is Array<*> -> value.map(::document)
        else -> throw IllegalArgumentException("Unsupported Confix value ${value.javaClass.name}")
    }
}
