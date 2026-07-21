package borg.trikeshed.couch.viewserver

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.`ColumnMeta↻`
import borg.trikeshed.lib.*
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyArray
import org.graalvm.polyglot.proxy.ProxyObject

class RowVecProxy(private val row: RowVec) : ProxyObject {
    private val metaList: List<ColumnMeta> by lazy {
        val list = mutableListOf<ColumnMeta>()
        val metaSupplier: () -> ColumnMeta = row.b as () -> ColumnMeta
        var current: ColumnMeta? = metaSupplier()
        while (current != null) {
            list.add(current)
            current = current.child
        }
        list
    }

    private val metaNames: List<String> by lazy {
        metaList.map { it.name.toString() }
    }

    override fun getMember(key: String): Any? {
        val index = metaNames.indexOf(key)
        val aVal: Series<Any?> = row.a as Series<Any?>
        if (index == -1 || index >= aVal.size) return null
        return aVal[index]
    }

    override fun getMemberKeys(): Any {
        return ProxyArray.fromArray(*metaNames.toTypedArray())
    }

    override fun hasMember(key: String): Boolean {
        return metaNames.contains(key)
    }

    override fun putMember(key: String, value: Value?) {
        throw UnsupportedOperationException("Cursor proxy is read-only")
    }
}

class CursorProxy(private val cursor: Cursor) : ProxyArray {
    override fun get(index: Long): Any {
        if (index < 0 || index >= cursor.size) throw IndexOutOfBoundsException()
        return RowVecProxy(cursor[index.toInt()])
    }

    override fun set(index: Long, value: Value?) {
        throw UnsupportedOperationException("Cursor proxy is read-only")
    }

    override fun getSize(): Long = cursor.size.toLong()
}

object GraalVmCursorHost {
    /**
     * Takes a Cursor, hands it to a JS guest script, and returns the resulting Cursor.
     * The script should evaluate to a function: `(cursor) => { return modifiedCursor; }`
     */
    fun reduceCursor(cursor: Cursor, jsScript: String): Cursor {
        val context = Context.newBuilder("js")
            .allowHostAccess(HostAccess.NONE)
            .build()

        context.use { ctx ->
            val scriptFunc = ctx.eval("js", jsScript)
            require(scriptFunc.canExecute()) { "Provided script must return a function" }

            val proxyCursor = CursorProxy(cursor)
            val result = scriptFunc.execute(proxyCursor)

            return valueToCursor(result, cursor) // Assuming resulting cursor has the same schema
        }
    }

    private fun valueToCursor(value: Value, originalCursor: Cursor): Cursor {
        require(value.hasArrayElements()) { "Guest script must return an array/cursor" }

        val size = value.arraySize.toInt()
        val defaultMeta: `ColumnMeta↻` = { ColumnMeta("empty", IOMemento.IoNothing) }
        val exemplarMeta: `ColumnMeta↻` = if (originalCursor.size > 0) {
            originalCursor[0].b as `ColumnMeta↻`
        } else {
            defaultMeta
        }

        val metaList = mutableListOf<ColumnMeta>()
        var current: ColumnMeta? = exemplarMeta()
        while (current != null) {
            metaList.add(current)
            current = current.child
        }

        // Eagerly materialize all row values before context closes
        val eagerlyEvaluatedRows = Array(size) { i ->
            val rowVal = value.getArrayElement(i.toLong())
            Array<Any?>(metaList.size) { colIdx ->
                val meta = metaList[colIdx]
                val memberVal = rowVal.getMember(meta.name.toString())
                when (meta.type) {
                    IOMemento.IoDouble -> if (memberVal != null && memberVal.isNumber) memberVal.asDouble() else null
                    IOMemento.IoInt -> if (memberVal != null && memberVal.isNumber) memberVal.asInt() else null
                    IOMemento.IoString -> if (memberVal != null && memberVal.isString) memberVal.asString() else null
                    IOMemento.IoBoolean -> if (memberVal != null && memberVal.isBoolean) memberVal.asBoolean() else null
                    else -> null // Simplification for test
                }
            }
        }

        return size j { i: Int ->
            val rowValues = eagerlyEvaluatedRows[i]
            val rowA = (rowValues.size j { idx: Int -> rowValues[idx] })
            val res = rowA j exemplarMeta
            res as RowVec
        }
    }
}
