package borg.trikeshed.render

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ── Algebra → Compose Layout ──────────────────────────────────
//
// The window-toolkit's SignalAlgebra provides beside/above/overlay
// as abstract composition operators. Here we ground them as actual
// Compose layout operations:
//
//   beside  → Row
//   above   → Column
//   overlay → Box
//
// These are the layout duals of the algebra. The same composition
// that window-toolkit expresses abstractly becomes concrete pixels.

/**
 * Beside — horizontal composition (Row).
 *
 * Maps to window-toolkit's SignalAlgebra.beside().
 */
@Composable
fun Beside(
    spacing: Dp = 0.dp,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/**
 * Above — vertical composition (Column).
 *
 * Maps to window-toolkit's SignalAlgebra.above().
 */
@Composable
fun Above(
    spacing: Dp = 0.dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/**
 * Overlay — stacked composition (Box).
 *
 * Maps to window-toolkit's SignalAlgebra.overlay().
 */
@Composable
fun Overlay(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier,
        content = content,
    )
}
