package borg.trikeshed.config

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.isam.IsamDataFile
import borg.trikeshed.isam.IsamOperations
import borg.trikeshed.isam.defaultIsamOperations
import borg.trikeshed.lib.EmptySeries
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series2
import borg.trikeshed.cursor.`ColumnMeta↻`
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

class IsamConfigStore(
    private val dataFilename: String,
    private val isamOperations: IsamOperations = defaultIsamOperations()
) {

    fun saveConfig(configValues: Map<String, ConfigValue>) {
        val rows = configValues.entries.mapIndexed { index, (key, value) ->
            val typeStr = when (value) {
                is ConfigValue.StringValue -> "STRING"
                is ConfigValue.IntValue -> "INT"
                is ConfigValue.BooleanValue -> "BOOLEAN"
                is ConfigValue.DoubleValue -> "DOUBLE"
            }
            val valStr = value.value.toString()
            
            val list = listOf<Any?>(key, typeStr, valStr)
            val columns = listOf("key", "type", "value")
            
            val metaFuncs = columns.map { colName ->
                val f: `ColumnMeta↻` = { ColumnMeta(colName, IOMemento.IoString) }
                f
            }

            val rowVec = object : Join<Series<Any?>, Series<`ColumnMeta↻`>> {
                override val a: Series<Any?> = list.size j { i -> list[i] }
                override val b: Series<`ColumnMeta↻`> = metaFuncs.size j { i -> metaFuncs[i] }
            }
            
            @Suppress("UNCHECKED_CAST")
            rowVec as RowVec
        }
        
        IsamDataFile.append(
            msf = rows,
            datafilename = dataFilename,
            operations = isamOperations
        )
    }
}
