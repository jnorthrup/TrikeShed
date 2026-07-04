package borg.trikeshed.forge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import borg.trikeshed.kanban.CardPriority
import borg.trikeshed.kanban.ForgeBoardEvent
import borg.trikeshed.kanban.ForgeBoardFSM
import borg.trikeshed.kanban.ForgeBoardState
import borg.trikeshed.kanban.KanbanCard
import borg.trikeshed.kanban.KanbanCardId
import borg.trikeshed.kanban.KanbanColumn
import borg.trikeshed.kanban.KanbanColumnId
import borg.trikeshed.kanban.cardsInColumn
import borg.trikeshed.kanban.wipCount
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.collectAsState
import kotlinx.datetime.Clock
import kotlin.math.roundToInt

// ─── Top-level screen ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanbanBoardScreen() {
    val state = remember { mutableStateOf(ForgeBoardFSM.current()) }
    val playState    by WalkthroughPlayer.playState.collectAsState()
    val playerStep   by WalkthroughPlayer.currentStep.collectAsState()
    val playerProgress by WalkthroughPlayer.progress.collectAsState()
    val narration    by PlayerOverlayState.narration.collectAsState()
    val recState     = remember { mutableStateOf(ForgeRecorder.recorder.state) }

    // Refresh recorder state every 500ms while recording
    LaunchedEffect(ForgeRecorder.isRecording) {
        while (true) {
            recState.value = ForgeRecorder.recorder.state
            kotlinx.coroutines.delay(500)
        }
    }

    LaunchedEffect(Unit) {
        // Bootstrap default board on first launch
        if (ForgeBoardFSM.current().boards.isEmpty()) ForgeBoardFSM.loadDefault()
        ForgeBoardFSM.state.collect { state.value = it }
    }

    // Sync player step → overlay
    LaunchedEffect(playerStep) { PlayerOverlayState.update(playerStep) }

    val board = state.value.activeBoard

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(board?.name ?: "Forge Kanban") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                actions = {
                    // ── Recording toolbar ─────────────────────────────────
                    RecordingToolbar(
                        playState   = playState,
                        recState    = recState.value,
                        frameCount  = ForgeRecorder.frameCount,
                        lastExport  = ForgeRecorder.lastExportPath,
                        progress    = playerProgress,
                    )
                    IconButton(onClick = { ForgeBoardFSM.loadDefault() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload board")
                    }
                },
            )

            if (board == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No board loaded — tap Refresh", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                KanbanBoardBody(
                    state = state.value,
                    onEvent = { ForgeBoardFSM.emit(it) },
                )
            }
        }

        // ── Narration subtitle overlay ─────────────────────────────────────
        if (narration.isNotEmpty()) {
            NarrationOverlay(text = narration)
        }

        // ── Recording indicator badge ──────────────────────────────────────
        if (recState.value == ScreenRecorder.State.RECORDING) {
            RecordingBadge(
                frameCount = ForgeRecorder.frameCount,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 12.dp),
            )
        }
    }
}

// ─── Recording toolbar ────────────────────────────────────────────────────────

@Composable
private fun RecordingToolbar(
    playState: WalkthroughPlayer.PlayState,
    recState: ScreenRecorder.State,
    frameCount: Int,
    lastExport: String?,
    progress: Pair<Int, Int>,
) {
    val isPlaying   = playState == WalkthroughPlayer.PlayState.PLAYING
    val isExporting = recState  == ScreenRecorder.State.EXPORTING
    val isDone      = playState == WalkthroughPlayer.PlayState.DONE

    // ── Play / Record walkthrough
    IconButton(
        enabled = !isPlaying && !isExporting,
        onClick  = {
            WalkthroughPlayer.reset()
            WalkthroughPlayer.play(
                script = teachPendantRobotScript(),
                window = WalkthroughWindowRef.get(),
                record = true,
            )
        },
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Default.Close else Icons.Default.PlayArrow,
            contentDescription = if (isPlaying) "Stop walkthrough" else "Play & Record walkthrough",
            tint = if (isPlaying) MaterialTheme.colorScheme.error
                   else          MaterialTheme.colorScheme.primary,
        )
    }

    // ── Stop
    if (isPlaying) {
        IconButton(onClick = { WalkthroughPlayer.stop() }) {
            Icon(Icons.Default.Stop, contentDescription = "Stop", tint = MaterialTheme.colorScheme.error)
        }
    }

    // ── Progress
    if (isPlaying) {
        val (cur, total) = progress
        Text(
            text = "$cur/$total",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }

    // ── Exporting indicator
    if (isExporting) {
        Text(
            "Exporting…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }

    // ── Last export badge
    if (isDone && lastExport != null) {
        TextButton(onClick = {
            // Open in Finder (macOS) or Explorer
            try { Runtime.getRuntime().exec(arrayOf("open", "-R", lastExport)) } catch (_: Exception) {}
        }) {
            Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Show video", style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ─── Narration subtitle overlay ───────────────────────────────────────────────

@Composable
private fun NarrationOverlay(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            color  = Color.Black.copy(alpha = 0.72f),
            shape  = RoundedCornerShape(8.dp),
        ) {
            Text(
                text  = text,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            )
        }
    }
}

// ─── Recording live badge ─────────────────────────────────────────────────────

@Composable
private fun RecordingBadge(frameCount: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color    = MaterialTheme.colorScheme.error.copy(alpha = 0.9f),
        shape    = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier            = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment   = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(Color.White, CircleShape),
            )
            Text(
                "REC  $frameCount",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
    }
}

// ─── Board body ──────────────────────────────────────────────────────────────

/**
 * Horizontal scrollable row of columns, with per-column card lists.
 *
 * Drag state is tracked at this level so every column drop-target can be
 * highlighted and we can commit the drop when the pointer is released over a
 * column that differs from the origin.
 */
@Composable
private fun KanbanBoardBody(
    state: ForgeBoardState,
    onEvent: (ForgeBoardEvent) -> Unit,
) {
    val board = state.activeBoard ?: return
    val drag = state.dragState

    // Column bounding boxes for hit-testing (root coordinates)
    val columnBounds = remember { mutableMapOf<KanbanColumnId, androidx.compose.ui.geometry.Rect>() }

    // Floating ghost card offset while dragging
    var ghostOffset by remember { mutableStateOf(Offset.Zero) }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            board.columns.sortedBy { it.order }.forEach { column ->
                val cards = board.cardsInColumn(column.id)
                val isDropTarget = drag != null && drag.overColumnId == column.id && drag.fromColumnId != column.id
                val isSource = drag != null && drag.fromColumnId == column.id

                KanbanColumnPanel(
                    column = column,
                    cards = cards,
                    wipCount = board.wipCount(column.id),
                    isDropTarget = isDropTarget,
                    isDragSource = isSource,
                    draggingCardId = drag?.cardId,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .onGloballyPositioned { coords ->
                            val pos = coords.positionInRoot()
                            columnBounds[column.id] = androidx.compose.ui.geometry.Rect(
                                left = pos.x,
                                top = pos.y,
                                right = pos.x + coords.size.width,
                                bottom = pos.y + coords.size.height,
                            )
                        },
                    onCardDragStart = { cardId ->
                        ghostOffset = Offset.Zero
                        onEvent(
                            ForgeBoardEvent.DragStarted(
                                boardId = board.id,
                                cardId = cardId,
                                fromColumnId = column.id,
                                timestampMs = nowMs(),
                            ),
                        )
                    },
                    onCardDragDelta = { delta ->
                        ghostOffset += delta
                        // Hit-test which column we're over
                        val hitColumn = columnBounds.entries.firstOrNull { (_, rect) ->
                            val x = ghostOffset.x
                            val y = ghostOffset.y
                            rect.contains(Offset(x, y))
                        }?.key
                        if (hitColumn != null && hitColumn != drag?.overColumnId) {
                            onEvent(ForgeBoardEvent.DragOver(hitColumn, nowMs()))
                        }
                    },
                    onCardDragEnd = {
                        if (drag != null && drag.overColumnId != null && drag.overColumnId != drag.fromColumnId) {
                            onEvent(ForgeBoardEvent.DragDropped(nowMs()))
                        } else {
                            onEvent(ForgeBoardEvent.DragCancelled(nowMs()))
                        }
                        ghostOffset = Offset.Zero
                    },
                    onAddCard = { title ->
                        onEvent(
                            ForgeBoardEvent.CardCreated(
                                boardId = board.id,
                                cardId = KanbanCardId.generate(),
                                columnId = column.id,
                                title = title,
                                timestampMs = nowMs(),
                            ),
                        )
                    },
                    onDeleteCard = { cardId ->
                        onEvent(ForgeBoardEvent.CardDeleted(board.id, cardId, nowMs()))
                    },
                )
            }
        }

        // Floating ghost while dragging
        if (drag != null) {
            val dragCard = board.cards.firstOrNull { it.id == drag.cardId }
            if (dragCard != null) {
                KanbanCardGhost(
                    card = dragCard,
                    modifier = Modifier
                        .offset { IntOffset(ghostOffset.x.roundToInt(), ghostOffset.y.roundToInt()) }
                        .zIndex(10f),
                )
            }
        }
    }
}

// ─── Column panel ────────────────────────────────────────────────────────────

@Composable
private fun KanbanColumnPanel(
    column: KanbanColumn,
    cards: List<KanbanCard>,
    wipCount: Int,
    isDropTarget: Boolean,
    isDragSource: Boolean,
    draggingCardId: KanbanCardId?,
    modifier: Modifier = Modifier,
    onCardDragStart: (KanbanCardId) -> Unit,
    onCardDragDelta: (Offset) -> Unit,
    onCardDragEnd: () -> Unit,
    onAddCard: (String) -> Unit,
    onDeleteCard: (KanbanCardId) -> Unit,
) {
    val wipOver = column.wipLimit != null && wipCount > column.wipLimit!!
    val borderColor = when {
        isDropTarget -> MaterialTheme.colorScheme.primary
        wipOver      -> Color(0xFFD32F2F)
        else         -> MaterialTheme.colorScheme.outlineVariant
    }
    val borderWidth by animateDpAsState(
        targetValue = if (isDropTarget) 2.dp else 1.dp,
        animationSpec = tween(150),
        label = "border",
    )
    val bgAlpha = if (isDropTarget) 0.08f else 0f
    val bg = MaterialTheme.colorScheme.primary.copy(alpha = bgAlpha)

    var addingCard by remember { mutableStateOf(false) }
    var newCardTitle by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .background(
                color = bg,
                shape = RoundedCornerShape(12.dp),
            )
            .border(borderWidth, borderColor, RoundedCornerShape(12.dp))
            .padding(8.dp),
    ) {
        // Column header
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = column.name,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WipBadge(count = wipCount, limit = column.wipLimit)
                IconButton(
                    onClick = { addingCard = !addingCard },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        if (addingCard) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = if (addingCard) "Cancel" else "Add card",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        HorizontalDivider(thickness = 1.dp, color = borderColor.copy(alpha = 0.4f))
        Spacer(modifier = Modifier.height(6.dp))

        // Inline add card form
        AnimatedVisibility(visible = addingCard, enter = fadeIn(), exit = fadeOut()) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                OutlinedTextField(
                    value = newCardTitle,
                    onValueChange = { newCardTitle = it },
                    placeholder = { Text("Card title…", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { addingCard = false; newCardTitle = "" }) {
                        Text("Cancel", fontSize = 12.sp)
                    }
                    FilledTonalButton(
                        onClick = {
                            if (newCardTitle.isNotBlank()) {
                                onAddCard(newCardTitle.trim())
                                newCardTitle = ""
                                addingCard = false
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text("Add", fontSize = 12.sp)
                    }
                }
            }
        }

        // Card list
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(cards, key = { it.id.value }) { card ->
                val isDragging = card.id == draggingCardId
                KanbanCardItem(
                    card = card,
                    isDragging = isDragging,
                    onDragStart = { onCardDragStart(card.id) },
                    onDragDelta = onCardDragDelta,
                    onDragEnd = onCardDragEnd,
                    onDelete = { onDeleteCard(card.id) },
                )
            }
        }
    }
}

// ─── Card item ───────────────────────────────────────────────────────────────

@Composable
private fun KanbanCardItem(
    card: KanbanCard,
    isDragging: Boolean,
    onDragStart: () -> Unit,
    onDragDelta: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val elevation by animateDpAsState(
        targetValue = if (isDragging) 12.dp else 2.dp,
        animationSpec = tween(150),
        label = "elevation",
    )
    val priorityColor = card.priority.color()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation, RoundedCornerShape(8.dp))
            .pointerInput(card.id) {
                detectDragGestures(
                    onDragStart = { _ -> onDragStart() },
                    onDrag = { _, delta -> onDragDelta(delta) },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                )
            },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDragging)
                MaterialTheme.colorScheme.surfaceContainerHighest
            else
                MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                // Priority indicator + title
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(priorityColor, CircleShape),
                    )
                    Text(
                        text = card.title,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(24.dp).padding(2.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Delete card",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (card.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = card.description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (card.assignee != null || card.tags.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (card.assignee != null) {
                        AssigneeChip(card.assignee!!)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        card.tags.take(3).forEach { tag -> TagChip(tag) }
                    }
                }
            }
        }
    }
}

// ─── Ghost (floating card during drag) ─────────────────────────────────────

@Composable
private fun KanbanCardGhost(
    card: KanbanCard,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .widthIn(min = 160.dp, max = 280.dp)
            .shadow(16.dp, RoundedCornerShape(8.dp)),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
        tonalElevation = 8.dp,
    ) {
        Text(
            text = card.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

// ─── Small components ───────────────────────────────────────────────────────

@Composable
private fun WipBadge(count: Int, limit: Int?) {
    if (limit == null) {
        Text(text = "$count", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val over = count > limit
    val bgColor = if (over) Color(0xFFD32F2F) else MaterialTheme.colorScheme.secondaryContainer
    val fgColor = if (over) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(shape = RoundedCornerShape(10.dp), color = bgColor) {
        Text(
            text = "$count / $limit",
            fontSize = 10.sp,
            color = fgColor,
            fontWeight = if (over) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun AssigneeChip(name: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(
            text = name.take(12),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun TagChip(tag: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = "#$tag",
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

// ─── Priority colours ───────────────────────────────────────────────────────

private fun CardPriority.color(): Color = when (this) {
    CardPriority.LOW      -> Color(0xFF4CAF50)
    CardPriority.MEDIUM   -> Color(0xFFFF9800)
    CardPriority.HIGH     -> Color(0xFFE53935)
    CardPriority.CRITICAL -> Color(0xFF9C27B0)
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
