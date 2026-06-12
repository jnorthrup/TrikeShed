@file:Suppress("unused")

package borg.trikeshed.render

/**
 * trike-render: Compose Multiplatform rendering for TrikeShed algebra.
 *
 * This library bridges TrikeShed's Join/Series/Cursor/CharStr algebra
 * to JetBrains Compose Multiplatform (@Composable) rendering.
 *
 * ── Isomorphism ──────────────────────────────────────────────
 *
 *   TrikeShed                 Compose
 *   ─────────                 ───────
 *   Join<A,B>                 LayoutNode (binary product)
 *   Series<T>                 LazyList<Item>
 *   Cursor = Series<RowVec>   LazyColumn of measured rows
 *   CharStr                   AnnotatedString
 *   α (fmap)                  @Composable transform
 *   FacetedRow<K>             Modifier chain
 *   SignalAlgebra.beside      Row
 *   SignalAlgebra.above       Column
 *   SignalAlgebra.overlay     Box
 *
 * ── Entry points ─────────────────────────────────────────────
 *
 *   CursorView(cursor)        — dataframe table widget
 *   SeriesChart(series)       — line chart on Canvas
 *   BarChart(series)          — bar chart on Canvas
 *   CharStrText(charStr)      — faceted text rendering
 *   CorpusView(corpus)        — column of CharStr lines
 *   Beside / Above / Overlay  — layout algebra
 *   Modifier.faceted(row)     — FacetedRow → Modifier bridge
 *   Flow<T>.asState(initial)  — window-toolkit Signal → State
 *
 * ── Dependencies ─────────────────────────────────────────────
 *
 *   - TrikeShed kernel (Join, Series, Cursor, CharStr, etc.)
 *   - Compose Multiplatform 1.11.1 (runtime, ui, foundation, material3)
 *   - No Skiko dependency at the library level
 */
