package borg.trikeshed.render

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*

// ── Cursor → @Composable ──────────────────────────────────────
//
// CursorView materialises a Cursor as a LazyColumn of RowVec rows.
// The algebra stays pure — select/map/α produce new Cursors; this
// widget only reads them. Column reordering is Cursor.select(intArrayOf),
// not widget state.

/**
 * Renders a [Cursor] as a scrollable table using Compose LazyColumn.
 *
 * Row composition is lazy: Cursor[i] is only called when the row scrolls
 * into view, matching Series<T>'s lazy evaluation contract exactly.
 *
 * @param cursor     the algebraic cursor to render
 * @param modifier   Compose layout modifier
 * @param header     optional header composable (default: column names from meta)
 * @param cellContent per-cell renderer; receives (rowValue, columnMeta, rowIndex, colIndex)
 */
@Composable
fun CursorView(
    cursor: Cursor,
    modifier: Modifier = Modifier,
    header: @Composable (Series<ColumnMeta>) -> Unit = { DefaultHeader(it) },
    cellContent: @Composable (rowValue: Any?, colMeta: ColumnMeta, rowIndex: Int, colIndex: Int) -> Unit =
        { v, _, _, _ -> DefaultCell(v) },
) {
    val colCount = remember(cursor) { cursor[0].size }
    val colMeta = remember(cursor) { cursor.meta }

    Column(modifier = modifier) {
        // Header row
        header(colMeta)

        HorizontalDivider()

        // Body — lazy row composition
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(
                count = cursor.size,
                key = { index: Int -> index },
            ) { rowIndex: Int ->
                RowCursor(cursor, rowIndex, colCount, cellContent)
            }
        }
    }
}

@Composable
private fun RowCursor(
    cursor: Cursor,
    rowIndex: Int,
    colCount: Int,
    cellContent: @Composable (Any?, ColumnMeta, Int, Int) -> Unit,
) {
    val row = cursor[rowIndex]
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        for (c in 0 until colCount) {
            val cell = row[c]
            val cellValue = cell.a
            val cellMeta = cell.b()
            Box(
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                cellContent(cellValue, cellMeta, rowIndex, c)
            }
        }
    }
}

/** Default header: column names from metadata, bold. */
@Composable
fun DefaultHeader(colMeta: Series<ColumnMeta>) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        for (c in 0 until colMeta.size) {
            Box(
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = colMeta[c].name.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                )
            }
        }
    }
}

/** Default cell: toString of the value, themed body text. */
@Composable
fun DefaultCell(value: Any?) {
    Text(
        text = value?.toString() ?: "∅",
        style = MaterialTheme.typography.bodySmall,
        color = if (value == null) MaterialTheme.colorScheme.outline else LocalTextStyle.current.color,
    )
}
