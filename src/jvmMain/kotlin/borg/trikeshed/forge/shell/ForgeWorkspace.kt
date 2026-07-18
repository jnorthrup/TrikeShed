package borg.trikeshed.forge.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import borg.trikeshed.forge.blackboard.ForgeBlackboardCornerSlot
import borg.trikeshed.forge.blackboard.ForgeBlackboardMode
import borg.trikeshed.forge.blackboard.ForgeBlackboardSection3D
import borg.trikeshed.forge.blackboard.ForgeBlackboardView
import borg.trikeshed.forge.gallery.ForgeGalleryCatalog
import borg.trikeshed.forge.gallery.ForgeGallerySection
import borg.trikeshed.forge.gallery.ForgeGalleryWidget

/** Theme — same dark palette as the browser bundle. */
private val ForgeDarkScheme = darkColorScheme(
    background = Color(0xFF090D13),
    surface = Color(0xFF111824),
    surfaceVariant = Color(0xFF1B2635),
    primary = Color(0xFF7AA2F7),
    onPrimary = Color(0xFF0B0F15),
    secondary = Color(0xFF7DCFFF),
    onSecondary = Color(0xFF0B0F15),
    tertiary = Color(0xFFE0AF68),
    onTertiary = Color(0xFF0B0F15),
    onBackground = Color(0xFFDBE7F3),
    onSurface = Color(0xFFDBE7F3),
    onSurfaceVariant = Color(0xFF7E8DA0),
    outline = Color(0xFF263548),
)

/**
 * JVM desktop entrypoint — opens the platform window and mounts the Forge
 * workspace shell.  All model data is the same commonMain catalog +
 * blackboard view that the browser seed carries.
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Forge — JVM shell",
    ) {
        MaterialTheme(colorScheme = ForgeDarkScheme) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxSize()) {
                    val view = remember { ForgeBlackboardView.DEFAULT }
                    val catalog = remember { ForgeGalleryCatalog.widgets() }
                    Rail(catalog = catalog)
                    Workspace(view = view, catalog = catalog)
                }
            }
        }
    }
}

@Composable
private fun Rail(catalog: List<ForgeGalleryWidget>) {
    Column(
        modifier = Modifier
            .width(260.dp)
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outline)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column {
            Text("FORGE LOCAL-FIRST", color = MaterialTheme.colorScheme.secondary, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(8.dp))
            Text("Page, board, field", color = MaterialTheme.colorScheme.onBackground, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "RTS-style zoom from workspace lanes into card-level detail. Same model the browser bundle renders.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }

        SectionHeader("Gallery sections", "Click a section to scroll the gallery.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ForgeGallerySection.values().toList()) { section ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(section.name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            "${catalog.count { it.section == section }} widgets",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, sub: String) {
    Column {
        Text(title, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun Workspace(view: ForgeBlackboardView, catalog: List<ForgeGalleryWidget>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D131D))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BlackboardChrome(view = view)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BoardPanel(view = view)
            GalleryPanel(view = view, catalog = catalog)
        }

        BlackboardView(view = view, catalog = catalog)
    }
}

@Composable
private fun BlackboardChrome(view: ForgeBlackboardView) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            // Corner buttons anchored at the four edges
            CornerSlot(ForgeBlackboardCornerSlot.TOP_LEFT, view, Alignment.TopStart)
            CornerSlot(ForgeBlackboardCornerSlot.TOP_RIGHT, view, Alignment.TopEnd)
            CornerSlot(ForgeBlackboardCornerSlot.BOTTOM_LEFT, view, Alignment.BottomStart)
            CornerSlot(ForgeBlackboardCornerSlot.BOTTOM_RIGHT, view, Alignment.BottomEnd)

            // Title bar — centered
            Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(view.surface, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                Text(
                    "mode=${view.defaultMode.name}  yaw=${"%.2f".format(view.mode3D.yawRadians)}  pitch=${"%.2f".format(view.mode3D.pitchRadians)} rad",
                    color = MaterialTheme.colorScheme.secondary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun BoxScope.CornerSlot(slot: ForgeBlackboardCornerSlot, view: ForgeBlackboardView, alignment: Alignment) {
    val btn = view.cornerButtons.firstOrNull { it.slot == slot } ?: return
    Box(modifier = Modifier.align(alignment)) {
        ChromeButton(label = btn.label, hotkey = btn.hotkey)
    }
}

@Composable
private fun ChromeButton(label: String, hotkey: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("[$hotkey]", color = MaterialTheme.colorScheme.tertiary, fontSize = 10.sp)
        }
    }
}

@Composable
private fun BoardPanel(view: ForgeBlackboardView) {
    Card(
        modifier = Modifier.fillMaxWidth().height(220.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Board", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            val board = view.layout3D.firstOrNull { it.sectionId == "board" }
            Text(
                "center=(${board?.centerX?.toInt()},${board?.centerY?.toInt()})  elevation=${board?.elevation?.toInt()}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column1("Backlog", listOf("Setup CI", "Wire kanban bridge"))
                Column1("In progress", listOf("Confix ingest", "Couch cascade"))
                Column1("Done", listOf("Taxonomy", "Blackboard"))
            }
        }
    }
}

@Composable
private fun Column1(name: String, cards: List<String>) {
    Column(
        modifier = Modifier
            .width(150.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        cards.forEach { card ->
            Text("• $card", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

@Composable
private fun GalleryPanel(view: ForgeBlackboardView, catalog: List<ForgeGalleryWidget>) {
    Card(
        modifier = Modifier.fillMaxWidth().height(220.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val placement = view.layout3D.firstOrNull { it.sectionId == "gallery" }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Gallery", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "elevation=${placement?.elevation?.toInt()}  catalog=${ForgeGalleryCatalog.CATALOG_VERSION}",
                    color = MaterialTheme.colorScheme.secondary,
                    fontSize = 11.sp,
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(catalog.take(8)) { widget -> GalleryRow(widget) }
                if (catalog.size > 8) item {
                    Text("… and ${catalog.size - 8} more", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun GalleryRow(widget: ForgeGalleryWidget) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 110.dp, height = 18.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(widget.id, color = MaterialTheme.colorScheme.secondary, fontSize = 10.sp)
        }
        Text(widget.name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text(
            widget.synopsis.take(48),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun BlackboardView(view: ForgeBlackboardView, catalog: List<ForgeGalleryWidget>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Blackboard — ${view.defaultMode.name}", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "depth-toggle [d] cycles ${ForgeBlackboardMode.values().joinToString("→") { it.name }}",
                    color = MaterialTheme.colorScheme.tertiary,
                    fontSize = 11.sp,
                )
            }
            // Plan-view projection of the 3D layout — simple isometric placeholder.
            PlanProjection(view = view, catalog = catalog)
        }
    }
}

@Composable
private fun PlanProjection(view: ForgeBlackboardView, catalog: List<ForgeGalleryWidget>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(Color(0xFF0B0F15), RoundedCornerShape(12.dp))
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outline, shape = RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        view.layout3D.forEach { placement ->
            SectionChip(placement = placement, catalog = catalog)
        }
    }
}

@Composable
private fun SectionChip(placement: ForgeBlackboardSection3D, catalog: List<ForgeGalleryWidget>) {
    val widgetCount = if (placement.sectionId == "gallery") catalog.size else null
    val accent = when (placement.sectionId) {
        "page" -> MaterialTheme.colorScheme.secondary
        "board" -> MaterialTheme.colorScheme.primary
        "gallery" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(placement.sectionId.uppercase(), color = accent, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
            Text(
                "(${placement.centerX.toInt()},${placement.centerY.toInt()}) elev=${placement.elevation.toInt()}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
            )
            if (widgetCount != null) {
                Text("$widgetCount widgets", color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)
            }
        }
    }
}