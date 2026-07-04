package borg.trikeshed.forge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun WidgetShowcaseScreen() {
    val pages = remember { widgetShowcasePages() }
    var selectedPageIndex by remember { mutableStateOf(0) }
    var selectedWidgetIndex by remember { mutableStateOf(0) }

    val page = pages[selectedPageIndex]
    val selectedWidget = page.widgets.getOrNull(selectedWidgetIndex) ?: page.widgets.first()

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(page.title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    page.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    pages.forEachIndexed { index, candidate ->
                        val selected = index == selectedPageIndex
                        val buttonModifier = Modifier
                            .border(
                                width = if (selected) 1.dp else 0.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                shape = RoundedCornerShape(999.dp),
                            )
                        if (selected) {
                            FilledTonalButton(
                                onClick = {
                                    selectedPageIndex = index
                                    selectedWidgetIndex = 0
                                },
                                modifier = buttonModifier,
                            ) {
                                Text(candidate.title)
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    selectedPageIndex = index
                                    selectedWidgetIndex = 0
                                },
                                modifier = buttonModifier,
                            ) {
                                Text(candidate.title)
                            }
                        }
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                PagePreview(
                    page = page,
                    selectedWidgetIndex = selectedWidgetIndex,
                    onWidgetSelected = { selectedWidgetIndex = it },
                )
            }
            Column(modifier = Modifier.widthIn(min = 300.dp, max = 380.dp).fillMaxSize()) {
                WidgetInspector(selectedWidget = selectedWidget, page = page)
            }
        }
    }
}

@Composable
private fun PagePreview(
    page: WidgetShowcasePage,
    selectedWidgetIndex: Int,
    onWidgetSelected: (Int) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (page.layout) {
                WidgetShowcaseLayout.HERO -> HeroPreview(page, selectedWidgetIndex, onWidgetSelected)
                WidgetShowcaseLayout.SPLIT -> SplitPreview(page, selectedWidgetIndex, onWidgetSelected)
                WidgetShowcaseLayout.GRID -> GridPreview(page, selectedWidgetIndex, onWidgetSelected)
                WidgetShowcaseLayout.STACK -> StackPreview(page, selectedWidgetIndex, onWidgetSelected)
                WidgetShowcaseLayout.DOC -> DocPreview(page, selectedWidgetIndex, onWidgetSelected)
                WidgetShowcaseLayout.RADAR -> RadarPreview(page, selectedWidgetIndex, onWidgetSelected)
            }
        }
    }
}

@Composable
private fun HeroPreview(
    page: WidgetShowcasePage,
    selectedWidgetIndex: Int,
    onWidgetSelected: (Int) -> Unit,
) {
    PreviewHeader(page = page)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        page.widgets.take(2).forEachIndexed { index, widget ->
            ShowcaseWidgetCard(
                widget = widget,
                selected = selectedWidgetIndex == index,
                modifier = Modifier.weight(1f),
                onClick = { onWidgetSelected(index) },
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        page.widgets.drop(2).forEachIndexed { offset, widget ->
            val index = offset + 2
            ShowcaseWidgetCard(
                widget = widget,
                selected = selectedWidgetIndex == index,
                modifier = Modifier.weight(1f),
                onClick = { onWidgetSelected(index) },
            )
        }
    }
}

@Composable
private fun SplitPreview(
    page: WidgetShowcasePage,
    selectedWidgetIndex: Int,
    onWidgetSelected: (Int) -> Unit,
) {
    PreviewHeader(page = page)
    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LazyColumn(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(page.widgets) { index, widget ->
                ShowcaseWidgetCard(
                    widget = widget,
                    selected = selectedWidgetIndex == index,
                    onClick = { onWidgetSelected(index) },
                )
            }
        }
        Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ShowcaseWidgetCard(
                widget = page.widgets[selectedWidgetIndex.coerceIn(page.widgets.indices)],
                selected = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Selected template notes", style = MaterialTheme.typography.titleMedium)
                    page.templateNotes.forEach { note ->
                        Text("• $note", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun GridPreview(
    page: WidgetShowcasePage,
    selectedWidgetIndex: Int,
    onWidgetSelected: (Int) -> Unit,
) {
    PreviewHeader(page = page)
    val rows = page.widgets.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEachIndexed { colIndex, widget ->
                    val index = rowIndex * 2 + colIndex
                    ShowcaseWidgetCard(
                        widget = widget,
                        selected = selectedWidgetIndex == index,
                        modifier = Modifier.weight(1f),
                        onClick = { onWidgetSelected(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StackPreview(
    page: WidgetShowcasePage,
    selectedWidgetIndex: Int,
    onWidgetSelected: (Int) -> Unit,
) {
    PreviewHeader(page = page)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        page.widgets.forEachIndexed { index, widget ->
            ShowcaseWidgetCard(
                widget = widget,
                selected = selectedWidgetIndex == index,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onWidgetSelected(index) },
            )
        }
    }
}

@Composable
private fun DocPreview(
    page: WidgetShowcasePage,
    selectedWidgetIndex: Int,
    onWidgetSelected: (Int) -> Unit,
) {
    PreviewHeader(page = page)
    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LazyColumn(modifier = Modifier.weight(0.95f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(page.widgets) { index, widget ->
                ShowcaseWidgetCard(
                    widget = widget,
                    selected = selectedWidgetIndex == index,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onWidgetSelected(index) },
                )
            }
        }
        Column(modifier = Modifier.weight(1.15f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val selected = page.widgets[selectedWidgetIndex.coerceIn(page.widgets.indices)]
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Intelligent document", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(selected.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(selected.body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                            Text(selected.value, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
                        }
                        Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                            Text(selected.kind.name.lowercase(), modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Text("This section folds the live gallery into a readable document so the user can move from outline to board to radar without leaving the mental model.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (selected.links.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Linked sections", style = MaterialTheme.typography.titleMedium)
                            selected.links.forEach { link ->
                                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                    Text("→ $link", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Document notes", style = MaterialTheme.typography.titleMedium)
                    page.templateNotes.forEach { note ->
                        Text("• $note", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun RadarPreview(
    page: WidgetShowcasePage,
    selectedWidgetIndex: Int,
    onWidgetSelected: (Int) -> Unit,
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var flipped by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    PreviewHeader(page = page)
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RadarControls(
                zoom = zoom,
                flipped = flipped,
                onZoomIn = { zoom = (zoom + 0.15f).coerceAtMost(1.8f) },
                onZoomOut = { zoom = (zoom - 0.15f).coerceAtLeast(0.7f) },
                onFlip = { flipped = !flipped },
                onCenter = {
                    panX = 0f
                    panY = 0f
                    zoom = 1f
                },
                onPanLeft = { panX -= 32f },
                onPanRight = { panX += 32f },
                onPanUp = { panY -= 32f },
                onPanDown = { panY += 32f },
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { size = it }
                    .background(
                        Brush.radialGradient(
                            listOf(
                                Color(0xFF182131),
                                Color(0xFF0D1118),
                                Color(0xFF090C11),
                            ),
                        ),
                    )
            ) {
                val placements = radarPlacements(
                    page = page,
                    selectedIndex = selectedWidgetIndex,
                    size = size,
                    zoom = zoom,
                    panX = panX,
                    panY = panY,
                    flipped = flipped,
                )

                placements.forEachIndexed { index, placement ->
                    val selected = index == selectedWidgetIndex
                    val nodeWidth = if (selected) 250.dp else 220.dp
                    val nodeHeight = if (selected) 132.dp else 118.dp
                    val x = placement.centerX.roundToInt() - (if (selected) 125 else 110)
                    val y = placement.centerY.roundToInt() - (if (selected) 66 else 59)
                    ShowcaseWidgetCard(
                        widget = placement.widget,
                        selected = selected,
                        modifier = Modifier
                            .offset { IntOffset(x, y) }
                            .width(nodeWidth)
                            .height(nodeHeight),
                        onClick = { onWidgetSelected(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RadarControls(
    zoom: Float,
    flipped: Boolean,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFlip: () -> Unit,
    onCenter: () -> Unit,
    onPanLeft: () -> Unit,
    onPanRight: () -> Unit,
    onPanUp: () -> Unit,
    onPanDown: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onCenter) { Text("center") }
        OutlinedButton(onClick = onPanLeft) { Text("←") }
        OutlinedButton(onClick = onPanRight) { Text("→") }
        OutlinedButton(onClick = onPanUp) { Text("↑") }
        OutlinedButton(onClick = onPanDown) { Text("↓") }
        FilledTonalButton(onClick = onZoomOut) { Text("zoom −") }
        FilledTonalButton(onClick = onZoomIn) { Text("zoom +") }
        OutlinedButton(onClick = onFlip) { Text(if (flipped) "flip on" else "flip off") }
        Text("zoom ${"%.2f".format(zoom)}x", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PreviewHeader(page: WidgetShowcasePage) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(page.layout.name.lowercase().replace('_', ' '), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(page.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(page.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                page.templateNotes.take(3).forEach { note ->
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Text(note, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShowcaseWidgetCard(
    widget: WidgetShowcaseWidget,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val borderColor = parseHexColor(widget.accent)
    val container = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerHighest
    val elevation = if (selected) 18.dp else 8.dp
    Card(
        modifier = modifier
            .shadow(elevation, RoundedCornerShape(20.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .border(1.dp, if (selected) borderColor else Color.Transparent, RoundedCornerShape(20.dp))
            .graphicsLayer {
                transformOrigin = TransformOrigin(0.5f, 0.5f)
                scaleX = if (selected) 1.03f else 1f
                scaleY = if (selected) 1.03f else 1f
            },
        colors = CardDefaults.cardColors(containerColor = container),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier.size(12.dp).background(borderColor, RoundedCornerShape(999.dp)),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(widget.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(widget.kind.name.lowercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(widget.value, style = MaterialTheme.typography.labelLarge, color = borderColor)
            }
            Text(widget.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (widget.badges.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    widget.badges.forEach { badge ->
                        Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Text(badge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            if (widget.ctas.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    widget.ctas.forEach { cta ->
                        OutlinedButton(onClick = {}, enabled = false) {
                            Text(cta)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetInspector(
    selectedWidget: WidgetShowcaseWidget,
    page: WidgetShowcasePage,
) {
    val accent = parseHexColor(selectedWidget.accent)
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Inspector", style = MaterialTheme.typography.headlineSmall)
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(selectedWidget.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(selectedWidget.kind.name.lowercase(), style = MaterialTheme.typography.labelLarge, color = accent)
                    Text(selectedWidget.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Tone: ${selectedWidget.tone.name.lowercase()}", style = MaterialTheme.typography.labelMedium)
                    Text("Accent: ${selectedWidget.accent}", style = MaterialTheme.typography.labelMedium)
                    if (selectedWidget.links.isNotEmpty()) {
                        Text("Links: ${selectedWidget.links.joinToString(" · ")}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Page template", style = MaterialTheme.typography.titleMedium)
                    Text(page.layout.name.lowercase(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    page.templateNotes.forEach { note ->
                        Text("• $note", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private data class RadarPlacement(
    val widget: WidgetShowcaseWidget,
    val centerX: Double,
    val centerY: Double,
)

private fun radarPlacements(
    page: WidgetShowcasePage,
    selectedIndex: Int,
    size: IntSize,
    zoom: Float,
    panX: Float,
    panY: Float,
    flipped: Boolean,
): List<RadarPlacement> {
    if (size.width <= 0 || size.height <= 0) return emptyList()
    val cx = size.width / 2f + panX
    val cy = size.height / 2f + panY
    val ringRadius = min(size.width, size.height) * (0.24f + (0.05f * zoom))
    val linked = page.widgets.withIndex().filter { it.index != selectedIndex.coerceIn(page.widgets.indices) }
    val step = (2 * PI / maxOf(1, linked.size)).toFloat()
    val selectedWidget = page.widgets[selectedIndex.coerceIn(page.widgets.indices)]
    val placements = mutableListOf<RadarPlacement>()
    placements += RadarPlacement(selectedWidget, cx.toDouble(), cy.toDouble())
    linked.forEachIndexed { ordinal, entry ->
        val angle = (-PI / 2.0 + ordinal * step).toFloat()
        val linkWeight = if (entry.value.links.isEmpty()) 0.9f else 1.0f + entry.value.links.size * 0.06f
        var x = cx + cos(angle) * ringRadius * linkWeight
        val y = cy + sin(angle) * ringRadius * linkWeight
        if (flipped) x = size.width - x
        placements += RadarPlacement(entry.value, x.toDouble(), y.toDouble())
    }
    return placements
}

private fun parseHexColor(hex: String): Color {
    val clean = hex.removePrefix("#")
    val value = clean.toLong(16)
    val rgb = if (clean.length <= 6) value else value and 0xFFFFFF
    return Color(((rgb shr 16) and 0xFF).toInt(), ((rgb shr 8) and 0xFF).toInt(), (rgb and 0xFF).toInt())
}
