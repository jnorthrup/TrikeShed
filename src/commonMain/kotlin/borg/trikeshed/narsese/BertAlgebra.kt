package borg.trikeshed.narsese

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.lib.get
import borg.trikeshed.manifold.ManifoldRow
import borg.trikeshed.manifold.manifold

/** Row-major parameter storage indexed through the MetaSeries shape domain, without copying it. */
val BertTensor.indexed: MetaSeries<Shape, BertScalar> get() {
    var count = 1L
    for (extent in a.view) {
        require(extent >= 0 && (extent == 0 || count <= Int.MAX_VALUE / extent)) { "Invalid BERT tensor extent" }
        count *= extent
    }
    require(count == b.size.toLong()) { "BERT tensor storage differs from its shape" }
    return a j { index: Shape ->
        require(index.size == a.size) { "BERT tensor index rank differs from its shape" }
        var offset = 0
        for (axis in 0 until a.size) {
            require(index[axis] in 0 until a[axis]) { "BERT tensor coordinate exceeds its shape" }
            offset = offset * a[axis] + index[axis]
        }
        b[offset]
    }
}

/** Explicit UTF-16 target offsets retain the token's bounds and paired presence. */
val BertToken.offsets: Twin<Int>? get() {
    require((start == null) == (end == null)) { "BERT target offsets must be paired" }
    val from = start ?: return null
    val to = requireNotNull(end)
    require(from >= 0 && to > from && to <= sentence.length) { "BERT target offsets exceed the sentence" }
    return from j to
}

/** Borrowed array storage: projections observe the owner's coordinates without a numeric copy. */
val BertVector.coordinates: Series<Float>? get() = values?.toSeries()

/** A rank-one tensor over the same coordinate storage, indexed by a rank-one multi-index. */
val BertVector.tensor: MetaSeries<Tensor, Float>? get() {
    val coordinates = coordinates ?: return null
    return shapeOf(coordinates.size) j { index: Shape ->
        require(index.size == 1) { "BERT tensor indices must have rank one" }
        val at = index[0]
        require(at in 0 until coordinates.size) { "BERT tensor coordinate exceeds its shape" }
        coordinates[at]
    }
}

/** A vector remains one array-valued cell beside its token and span metadata. */
fun BertVector.asRowVec(): RowVec {
    val coordinates = coordinates
    return 4 j { column: Int ->
        val value: Any? = when (column) {
            0 -> token.lemma
            1 -> token.sentence
            2 -> span.name
            3 -> coordinates
            else -> throw IndexOutOfBoundsException("BERT column $column")
        }
        value j {
            when (column) {
                0 -> ColumnMeta("lemma", IOMemento.IoString)
                1 -> ColumnMeta("sentence", IOMemento.IoString)
                2 -> ColumnMeta("span", IOMemento.IoString)
                else -> ColumnMeta("values", IOMemento.IoArray, ColumnMeta("value", IOMemento.IoFloat))
            }
        }
    }
}

fun Series<BertVector>.asCursor(): Cursor = this α { it.asRowVec() }

/** The caller supplies the manifold coordinate and budget; the payload remains the borrowed tensor. */
fun BertVector.asManifold(angular: Long, budget: BudgetCoord): ManifoldRow = manifold(angular, budget, tensor)
