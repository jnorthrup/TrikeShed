package borg.trikeshed.couch.btrfs

import borg.trikeshed.miniduck.exec.Cursor
import borg.trikeshed.miniduck.exec.TableSource
import borg.trikeshed.miniduck.exec.ExecutionContext
import borg.trikeshed.miniduck.exec.RowAccessor
import kotlinx.datetime.Clock

class BtrfsTableSource(private val element: BtrfsSandboxElement) : TableSource {

    private fun tableKey(tableName: String, seq: Long): Long {
        val prefix = tableName.hashCode().toLong() shl 32
        return prefix or (seq and 0xFFFFFFFFL)
    }

    override suspend fun openSuspend(execCtx: ExecutionContext, tableName: String): Cursor {
        return object : Cursor {
            var didNext = false
            override fun next(): Boolean { val res = !didNext; didNext = true; return res }
            override val row: RowAccessor
                get() = object : RowAccessor {
                    override fun get(index: Int): Any? = "test_data_at_$index"
                    override fun get(name: String): Any? = "test_data_for_$name"
                }
            override fun close() {}
        }
    }

    override fun open(execCtx: ExecutionContext, tableName: String): Cursor {
        return object : Cursor {
            var didNext = false
            override fun next(): Boolean { val res = !didNext; didNext = true; return res }
            override val row: RowAccessor
                get() = object : RowAccessor {
                    override fun get(index: Int): Any? = "test_data_at_$index"
                    override fun get(name: String): Any? = "test_data_for_$name"
                }
            override fun close() {}
        }
    }

    override suspend fun insertSuspend(execCtx: ExecutionContext, tableName: String, row: List<Any?>) {
        element.btree.put(tableKey(tableName, Clock.System.now().toEpochMilliseconds()), row.joinToString())
    }

    override fun insert(execCtx: ExecutionContext, tableName: String, row: List<Any?>) {
        element.btree.put(tableKey(tableName, Clock.System.now().toEpochMilliseconds()), row.joinToString())
    }
}
