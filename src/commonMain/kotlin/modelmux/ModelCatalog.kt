package modelmux

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

data class ModelCatalogEntry(
    val provider: String,
    val model: String,
    val freeTier: Boolean,
    val quotaRemaining: Int,
    val latencyEstimateMs: Int
)

class ModelCatalog(val entries: Series<ModelCatalogEntry>) : Cursor {
    override val a: Int
        get() = entries.a

    private val providerMeta = ColumnMeta("provider", IOMemento.IoString, null)
    private val modelMeta = ColumnMeta("model", IOMemento.IoString, null)
    private val freeTierMeta = ColumnMeta("freeTier", IOMemento.IoBoolean, null)
    private val quotaMeta = ColumnMeta("quotaRemaining", IOMemento.IoInt, null)
    private val latencyMeta = ColumnMeta("latencyEstimateMs", IOMemento.IoInt, null)

    override val b: (Int) -> RowVec
        get() = { rowIdx ->
            val entry = entries.b(rowIdx)
            5 j { colIdx ->
                when (colIdx) {
                    0 -> entry.provider j { providerMeta }
                    1 -> entry.model j { modelMeta }
                    2 -> entry.freeTier j { freeTierMeta }
                    3 -> entry.quotaRemaining j { quotaMeta }
                    4 -> entry.latencyEstimateMs j { latencyMeta }
                    else -> error("Invalid column index: $colIdx")
                }
            }
        }
        
    fun cacheHits(predicate: (ModelCatalogEntry) -> Boolean): Cursor {
        val hits = (0 until entries.a).filter { predicate(entries.b(it)) }
        return ModelCatalog(hits.size j { i -> entries.b(hits[i]) })
    }

    fun cacheMisses(predicate: (ModelCatalogEntry) -> Boolean): Cursor {
        val misses = (0 until entries.a).filter { !predicate(entries.b(it)) }
        return ModelCatalog(misses.size j { i -> entries.b(misses[i]) })
    }
}
