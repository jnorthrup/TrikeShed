package borg.trikeshed.graal

import org.xvm.cursor.PointcutFacet
import borg.trikeshed.lib.MutableSeries
import borg.trikeshed.lib.SeriesBuffer

/**
 * PolyglotFacet represents different language features for pointcuts.
 */
sealed class PolyglotFacet : PointcutFacet {
    object PolyglotFunction : PolyglotFacet()
    object PolyglotVariable : PolyglotFacet()
    object PolyglotBlock : PolyglotFacet()
    object Unfaceted : PolyglotFacet()
}

/**
 * PolyglotCoordinateRow stores metadata about intercepted executions.
 */
data class PolyglotCoordinateRow(
    val languageId: String,
    val symbolName: String,
    val sourceLocation: String,
    val poolId: Int,
    val facet: PolyglotFacet = PolyglotFacet.Unfaceted
)

/**
 * PolyglotTaxonomy registers these rows into the broader TrikeShed taxonomic structure.
 */
class PolyglotTaxonomy {
    var rows: MutableSeries<PolyglotCoordinateRow> = SeriesBuffer()

    val size: Int get() = rows.a

    fun register(row: PolyglotCoordinateRow) {
        rows.add(row)
    }

    fun rowAt(index: Int): PolyglotCoordinateRow = rows.b(index)

    fun getRows(): borg.trikeshed.lib.Series<PolyglotCoordinateRow> = rows
}
