package borg.trikeshed.render

import androidx.compose.ui.Modifier
import androidx.compose.ui.node.ModifierNodeElement
import borg.trikeshed.lib.*

// ── FacetedRow<K> → Modifier ──────────────────────────────────
//
// FacetedRow<K> = MetaSeries<K, Any?> — a universal row of keyed facets.
// This maps a FacetedRow to a Compose Modifier chain, where each key
// contributes a semantic or layout modifier.
//
// This is the adapter layer: TrikeShed algebra produces FacetedRow,
// Compose consumes Modifier. The factory bridges them.

/**
 * Creates a [Modifier.Element] from a [FacetedRow] keyed by [K].
 *
 * The [interpret] function is called once per composition to produce
 * a Modifier from the facet values. This is the user-supplied bridge
 * between TrikeShed facets and Compose modifiers.
 *
 * Example usage:
 * ```
 * val styleRow: FacetedRow<StyleK> = ...
 * Box(modifier = Modifier.faceted(styleRow) { row ->
 *     val padding = row[StyleK.Padding] as Float
 *     Modifier.padding(padding.dp)
 * })
 * ```
 */
class FacetedModifierElement<K : OpK<*>>(
    private val row: FacetedRow<K>,
    private val interpret: (FacetedRow<K>) -> Modifier,
) : ModifierNodeElement<FacetedModifierNode<K>>() {

    override fun create(): FacetedModifierNode<K> =
        FacetedModifierNode(row, interpret)

    override fun update(node: FacetedModifierNode<K>) {
        node.row = row
        node.interpret = interpret
    }

    override fun hashCode(): Int = row.hashCode() * 31 + interpret.hashCode()
    override fun equals(other: Any?): Boolean =
        other is FacetedModifierElement<*> && row == other.row && interpret == other.interpret
}

class FacetedModifierNode<K : OpK<*>>(
    var row: FacetedRow<K>,
    var interpret: (FacetedRow<K>) -> Modifier,
) : Modifier.Node()

/**
 * Extension: attach a [FacetedRow] as a Compose [Modifier].
 *
 * The [interpret] lambda converts facet values into Modifier operations.
 */
fun <K : OpK<*>> Modifier.faceted(
    row: FacetedRow<K>,
    interpret: (FacetedRow<K>) -> Modifier,
): Modifier = this.then(FacetedModifierElement(row, interpret))
