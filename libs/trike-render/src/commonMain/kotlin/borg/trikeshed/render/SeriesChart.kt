package borg.trikeshed.render

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.lib.*

// ── Series<T> → Canvas ────────────────────────────────────────
//
// SeriesChart renders a Series<T> as a Canvas drawing.
// The renderer receives the DrawScope and the Series; all drawing
// is pure — the Series is read lazily, only the visible region
// needs to be sampled.

/**
 * Renders a [Series] of [Number] as a line chart on a Canvas.
 *
 * The Series is sampled on every draw pass, so lazy Series work
 * correctly — only the accessed indices trigger evaluation.
 *
 * @param series     lazy numeric series to plot
 * @param modifier   layout modifier
 * @param color      line color
 * @param strokeWidth line width in dp
 * @param fill       whether to fill the area under the line
 */
@Composable
fun <T : Number> SeriesChart(
    series: Series<T>,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF6200EE),
    strokeWidth: Float = 3f,
    fill: Boolean = false,
    fillColor: Color = color.copy(alpha = 0.15f),
) {
    if (series.size < 2) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val n = series.size

        // Sample the series to find min/max — O(n) but only on draw
        var minVal = Double.MAX_VALUE
        var maxVal = Double.MIN_VALUE
        for (i in 0 until n) {
            val v = series[i].toDouble()
            if (v < minVal) minVal = v
            if (v > maxVal) maxVal = v
        }
        val range = (maxVal - minVal).let { if (it == 0.0) 1.0 else it }

        // Build path
        val path = Path()
        for (i in 0 until n) {
            val x = (i.toFloat() / (n - 1)) * w
            val y = h - ((series[i].toDouble() - minVal) / range * h).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        // Optional fill
        if (fill) {
            val fillPath = Path().apply {
                addPath(path)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(fillPath, fillColor)
        }

        drawPath(path, color, style = Stroke(width = strokeWidth))
    }
}

/**
 * Bar chart from a [Series] of [Number].
 */
@Composable
fun <T : Number> BarChart(
    series: Series<T>,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF03DAC6),
    barSpacing: Float = 2f,
) {
    if (series.size == 0) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val n = series.size

        var maxVal = Double.MIN_VALUE
        for (i in 0 until n) {
            val v = series[i].toDouble()
            if (v > maxVal) maxVal = v
        }
        if (maxVal == 0.0) maxVal = 1.0

        val barWidth = (w - barSpacing * (n + 1)) / n

        for (i in 0 until n) {
            val barHeight = (series[i].toDouble() / maxVal * h).toFloat()
            val x = barSpacing + i * (barWidth + barSpacing)
            drawRect(
                color = color,
                topLeft = Offset(x, h - barHeight),
                size = Size(barWidth, barHeight),
            )
        }
    }
}

/**
 * Generic Series → Canvas renderer. For when the built-in charts
 * don't match — pass your own draw function.
 */
@Composable
fun <T> SeriesCanvas(
    series: Series<T>,
    modifier: Modifier = Modifier,
    draw: DrawScope.(Series<T>) -> Unit,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        draw(series)
    }
}
