package borg.trikeshed.forge.ui

import borg.trikeshed.kanban.CardPriority
import borg.trikeshed.kanban.ForgeBoardEvent
import borg.trikeshed.kanban.ForgeBoardFSM
import borg.trikeshed.kanban.KanbanBoard
import borg.trikeshed.kanban.KanbanCard
import borg.trikeshed.kanban.KanbanCardId
import borg.trikeshed.kanban.cardsInColumn
import borg.trikeshed.kanban.wipCount
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.io.OutputStream

// ─── Palette ──────────────────────────────────────────────────────────────────

private val BG          = Color(0x1E, 0x1E, 0x2E)  // dark navy
private val SURFACE     = Color(0x2A, 0x2A, 0x3E)
private val SURFACE2    = Color(0x33, 0x33, 0x50)
private val ACCENT      = Color(0x7C, 0x3A, 0xED)  // purple
private val ACCENT_DIM  = Color(0x4A, 0x25, 0x95)
private val TEXT        = Color(0xF0, 0xF0, 0xFF)
private val MUTED       = Color(0x88, 0x88, 0xAA)
private val DROP_RING   = Color(0x5A, 0xE6, 0xAA)  // teal drop-target ring
private val ERR         = Color(0xFF, 0x5A, 0x5A)

private val PRIORITY_COLORS = mapOf(
    CardPriority.LOW      to Color(0x44, 0xAA, 0x66),
    CardPriority.MEDIUM   to Color(0x55, 0x99, 0xDD),
    CardPriority.HIGH     to Color(0xFF, 0xAA, 0x33),
    CardPriority.CRITICAL to Color(0xFF, 0x44, 0x44),
)

// ─── Offscreen board renderer ─────────────────────────────────────────────────

/**
 * Renders a KanbanBoard into a BufferedImage using Java2D.
 * No Compose, no screen, no AWT window needed.
 */
object OffscreenBoardRenderer {

    private val TITLE_FONT  = Font("SansSerif", Font.BOLD,   18)
    private val CARD_FONT   = Font("SansSerif", Font.PLAIN,  14)
    private val SMALL_FONT  = Font("SansSerif", Font.PLAIN,  11)
    private val LABEL_FONT  = Font("SansSerif", Font.BOLD,   12)
    private val NARR_FONT   = Font("SansSerif", Font.BOLD,   22)

    fun render(
        board: KanbanBoard,
        width: Int = 1280,
        height: Int = 800,
        narration: String = "",
        highlightColumnId: String? = null,
        draggingCardId: KanbanCardId? = null,
        stepInfo: String = "",
    ): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

        // Background
        g.color = BG
        g.fillRect(0, 0, width, height)

        // Top bar
        g.color = SURFACE
        g.fillRect(0, 0, width, 52)
        g.color = ACCENT
        g.fillRect(0, 50, width, 2)
        g.color = TEXT
        g.font = TITLE_FONT
        g.drawString(board.name, 20, 34)
        if (stepInfo.isNotEmpty()) {
            g.color = MUTED
            g.font = SMALL_FONT
            g.drawString(stepInfo, width - 200, 34)
        }

        // Columns
        val cols = board.columns.sortedBy { it.order }
        if (cols.isEmpty()) {
            // Empty board — just render the title bar and return
            g.dispose()
            return img
        }
        val colW = ((width - 40) / cols.size).coerceAtLeast(200)
        val colStartY = 64
        val colHeight = height - colStartY - if (narration.isNotEmpty()) 72 else 24

        cols.forEachIndexed { ci, col ->
            val x = 20 + ci * (colW + 8)
            val isHighlight = col.id.value == highlightColumnId
            val cards = board.cardsInColumn(col.id)
            val wip = board.wipCount(col.id)
            val overLimit = col.wipLimit != null && wip > col.wipLimit!!

            // Column background
            g.color = if (isHighlight) Color(0x2A, 0x44, 0x3A) else SURFACE
            roundRect(g, x, colStartY, colW - 8, colHeight, 12)

            // Highlight ring
            if (isHighlight) {
                g.color = DROP_RING
                g.stroke = BasicStroke(2f)
                g.drawRoundRect(x, colStartY, colW - 8, colHeight, 12, 12)
                g.stroke = BasicStroke(1f)
            }

            // Column header
            g.color = SURFACE2
            roundRect(g, x, colStartY, colW - 8, 38, 12)

            g.color = if (overLimit) ERR else TEXT
            g.font = LABEL_FONT
            g.drawString(col.name, x + 12, colStartY + 24)

            // WIP badge
            val wipText = if (col.wipLimit != null) "$wip/${col.wipLimit}" else "$wip"
            g.color = if (overLimit) ERR else MUTED
            g.font = SMALL_FONT
            val bw = g.fontMetrics.stringWidth(wipText) + 12
            val bx = x + colW - 8 - bw - 8
            g.color = if (overLimit) Color(0xFF, 0x44, 0x44, 80) else Color(0x40, 0x40, 0x60)
            g.fillRoundRect(bx, colStartY + 8, bw, 22, 11, 11)
            g.color = if (overLimit) ERR else MUTED
            g.drawString(wipText, bx + 6, colStartY + 24)

            // Cards
            var cardY = colStartY + 46
            cards.forEach { card ->
                if (card.id == draggingCardId) {
                    // Ghost: dimmed outline only
                    g.color = Color(0xFF, 0xFF, 0xFF, 30)
                    g.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0f, floatArrayOf(4f, 4f), 0f)
                    g.drawRoundRect(x + 8, cardY, colW - 24, 70, 8, 8)
                    g.stroke = BasicStroke(1f)
                } else {
                    cardY = drawCard(g, card, x + 8, cardY, colW - 24)
                }
                cardY += 6
                if (cardY > colStartY + colHeight - 80) return@forEach
            }
        }

        // Narration bar
        if (narration.isNotEmpty()) {
            val barH = 56
            val barY = height - barH - 8
            g.color = Color(0, 0, 0, 185)
            g.fillRoundRect(40, barY, width - 80, barH, 10, 10)
            g.color = Color.WHITE
            g.font = NARR_FONT
            val fm = g.fontMetrics
            val tx = (width - fm.stringWidth(narration)) / 2
            g.drawString(narration, tx, barY + barH / 2 + fm.ascent / 2 - 4)
        }

        g.dispose()
        return img
    }

    private fun drawCard(g: Graphics2D, card: KanbanCard, x: Int, y: Int, w: Int): Int {
        val cardH = 72
        // Card background
        g.color = Color(0x3A, 0x3A, 0x55)
        g.fillRoundRect(x, y, w, cardH, 8, 8)

        // Priority stripe
        val stripe = PRIORITY_COLORS[card.priority] ?: MUTED
        g.color = stripe
        g.fillRoundRect(x, y, 4, cardH, 4, 4)

        // Title
        g.color = TEXT
        g.font = CARD_FONT
        val fm: FontMetrics = g.fontMetrics
        val title = truncate(card.title, w - 18, fm)
        g.drawString(title, x + 12, y + 22)

        // Priority label
        g.color = stripe
        g.font = SMALL_FONT
        g.drawString(card.priority.name, x + 12, y + 42)

        // Assignee
        if (card.assignee != null) {
            g.color = MUTED
            val aFm = g.fontMetrics
            val aText = "@${card.assignee}"
            val ax = x + w - aFm.stringWidth(aText) - 8
            g.drawString(aText, ax, y + 42)
        }

        // Description
        if (card.description.isNotEmpty()) {
            g.color = MUTED
            g.font = SMALL_FONT
            g.drawString(truncate(card.description, w - 18, g.fontMetrics), x + 12, y + 60)
        }

        return y + cardH
    }

    private fun roundRect(g: Graphics2D, x: Int, y: Int, w: Int, h: Int, arc: Int) {
        g.fillRoundRect(x, y, w, h, arc, arc)
    }

    private fun truncate(s: String, maxPx: Int, fm: FontMetrics): String {
        if (fm.stringWidth(s) <= maxPx) return s
        var end = s.length
        while (end > 1 && fm.stringWidth(s.take(end) + "…") > maxPx) end--
        return s.take(end) + "…"
    }
}

// ─── Headless walkthrough runner ──────────────────────────────────────────────

/**
 * Runs a [WalkthroughScript] headlessly:
 *  - Drives ForgeBoardFSM events
 *  - Renders each frame with [OffscreenBoardRenderer]
 *  - Streams raw RGB24 frames to ffmpeg stdin → MP4 output
 *  - Prints a full terminal trace of every state transition
 */
object HeadlessWalkthroughRunner {

    data class RunResult(
        val outputFile: File,
        val totalFrames: Int,
        val durationMs: Long,
        val trace: List<String>,
    )

    fun run(
        script: WalkthroughScript,
        outputFile: File,
        ffmpegPath: String = "ffmpeg",
        verbose: Boolean = true,
    ): RunResult = runBlocking {
        val trace = mutableListOf<String>()
        fun log(msg: String) {
            trace += msg
            if (verbose) println(msg)
        }

        log("═══════════════════════════════════════════════")
        log("  ${script.title}")
        log("  ${script.steps.size} steps · ${script.targetFps} fps · ${script.captureWidth}×${script.captureHeight}")
        log("  Output: ${outputFile.absolutePath}")
        log("═══════════════════════════════════════════════")

        ForgeBoardFSM.reset()

        outputFile.parentFile?.mkdirs()

        // ffmpeg: read raw RGB24 frames from stdin, encode to H.264
        val ffmpegArgs = listOf(
            ffmpegPath, "-y",
            "-f", "rawvideo",
            "-vcodec", "rawvideo",
            "-s", "${script.captureWidth}x${script.captureHeight}",
            "-pix_fmt", "rgb24",
            "-r", script.targetFps.toString(),
            "-i", "pipe:0",
            "-c:v", "libx264",
            "-preset", "fast",
            "-crf", "18",
            "-pix_fmt", "yuv420p",
            "-movflags", "+faststart",
            "-an",
            outputFile.absolutePath,
        )

        val pb = ProcessBuilder(ffmpegArgs)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
        pb.environment()["PATH"] = System.getenv("PATH") ?: "/usr/local/bin:/usr/bin:/bin:/opt/homebrew/bin"

        val ffmpeg = pb.start()
        val pipe: OutputStream = ffmpeg.outputStream
        val frameIntervalMs = 1000L / script.targetFps
        var totalFrames = 0
        val startMs = System.currentTimeMillis()
        var narration = ""

        fun pushFrames(count: Int, board: KanbanBoard, stepInfo: String, highlightCol: String? = null, dragging: KanbanCardId? = null) {
            val img = OffscreenBoardRenderer.render(
                board = board,
                width = script.captureWidth,
                height = script.captureHeight,
                narration = narration,
                highlightColumnId = highlightCol,
                draggingCardId = dragging,
                stepInfo = stepInfo,
            )
            val rgbBytes = imgToRgb24(img)
            repeat(count) {
                pipe.write(rgbBytes)
                totalFrames++
            }
        }

        fun framesFor(ms: Long): Int = ((ms.toDouble() / 1000.0) * script.targetFps).toInt().coerceAtLeast(1)

        for ((idx, step) in script.steps.withIndex()) {
            val stepLabel = "Step ${idx + 1}/${script.steps.size}: ${step.id}"
            narration = step.narration

            val boardBefore = ForgeBoardFSM.current().activeBoard
            log("")
            log("┌─ $stepLabel")
            log("│  narration: ${step.narration.ifEmpty { "(none)" }}")
            log("│  action:    ${step.action::class.simpleName}")
            boardBefore?.let { printBoard(it, ::log) }

            // Pre-action hold frames
            if (step.delayMs > 0) {
                val fCount = framesFor(step.delayMs)
                log("│  delayMs=${step.delayMs} → $fCount pre-frames")
                pushFrames(fCount, boardBefore ?: emptyBoard(), "$stepLabel — before")
                delay(1) // yield
            }

            // Execute action against FSM
            val actionLog = executeAction(step.action, ::log)
            log("│  → $actionLog")

            val boardAfter = ForgeBoardFSM.current().activeBoard

            // Post-action hold frames
            if (step.holdMs > 0) {
                val fCount = framesFor(step.holdMs)
                log("│  holdMs=${step.holdMs} → $fCount post-frames")
                val highlight = when (val a = step.action) {
                    is WalkthroughAction.HighlightColumn -> a.columnId.value
                    is WalkthroughAction.MoveCardByTitle -> a.toColumnId.value
                    is WalkthroughAction.MoveCard        -> a.toColumnId.value
                    is WalkthroughAction.CreateCard      -> a.columnId.value
                    else -> null
                }
                pushFrames(fCount, boardAfter ?: emptyBoard(), "$stepLabel — after", highlightCol = highlight)
                delay(1)
            }

            boardAfter?.let { printBoard(it, ::log) }
            log("└─ done (total frames so far: $totalFrames)")
        }

        // Trail: 1.5s hold on final state
        ForgeBoardFSM.current().activeBoard?.let { pushFrames(framesFor(1500), it, "Complete") }

        pipe.flush()
        pipe.close()
        val exitCode = ffmpeg.waitFor()
        val durationMs = System.currentTimeMillis() - startMs

        log("")
        log("═══════════════════════════════════════════════")
        if (exitCode == 0) {
            log("  COMPLETE — $totalFrames frames · ${durationMs}ms · ${outputFile.absolutePath}")
        } else {
            log("  FFMPEG ERROR — exit=$exitCode")
        }
        log("═══════════════════════════════════════════════")

        RunResult(outputFile, totalFrames, durationMs, trace)
    }

    // ── Action executor (same logic as WalkthroughPlayer but sync-safe) ────────

    private fun executeAction(action: WalkthroughAction, log: (String) -> Unit): String {
        val now = System.currentTimeMillis()
        val board = ForgeBoardFSM.current().activeBoard
        return when (action) {
            is WalkthroughAction.Narrate -> "Narrate: \"${action.text}\""
            is WalkthroughAction.LoadDefaultBoard -> {
                ForgeBoardFSM.loadDefault()
                "LoadDefaultBoard → ${ForgeBoardFSM.current().activeBoard?.columns?.size} columns, " +
                    "${ForgeBoardFSM.current().activeBoard?.cards?.size} cards"
            }
            is WalkthroughAction.CreateCard -> {
                if (board == null) return "CreateCard skipped — no active board"
                val cardId = KanbanCardId.generate()
                ForgeBoardFSM.emit(ForgeBoardEvent.CardCreated(
                    boardId = board.id, cardId = cardId, columnId = action.columnId,
                    title = action.title, timestampMs = now,
                ))
                "CreateCard '${action.title}' → ${action.columnId.value} (${action.priority})"
            }
            is WalkthroughAction.MoveCardByTitle -> {
                if (board == null) return "MoveCardByTitle skipped — no board"
                val card = board.cards.firstOrNull { it.title == action.title }
                    ?: return "MoveCardByTitle '${action.title}' NOT FOUND"
                ForgeBoardFSM.emit(ForgeBoardEvent.CardMoved(
                    boardId = board.id, cardId = card.id,
                    toColumnId = action.toColumnId, timestampMs = now,
                ))
                "MoveCard '${action.title}' → ${action.toColumnId.value}"
            }
            is WalkthroughAction.MoveCard -> {
                if (board == null) return "MoveCard skipped"
                ForgeBoardFSM.emit(ForgeBoardEvent.CardMoved(
                    boardId = board.id, cardId = action.cardId,
                    toColumnId = action.toColumnId, timestampMs = now,
                ))
                "MoveCard ${action.cardId.value} → ${action.toColumnId.value}"
            }
            is WalkthroughAction.DragCard -> {
                if (board == null) return "DragCard skipped"
                ForgeBoardFSM.emit(ForgeBoardEvent.DragStarted(board.id, action.cardId, action.fromColumnId, now))
                ForgeBoardFSM.emit(ForgeBoardEvent.DragOver(action.toColumnId, now))
                ForgeBoardFSM.emit(ForgeBoardEvent.DragDropped(now))
                "DragCard ${action.cardId.value}: ${action.fromColumnId.value} → ${action.toColumnId.value}"
            }
            is WalkthroughAction.DeleteCardByTitle -> {
                if (board == null) return "DeleteCard skipped"
                val card = board.cards.firstOrNull { it.title == action.title }
                    ?: return "DeleteCard '${action.title}' NOT FOUND"
                ForgeBoardFSM.emit(ForgeBoardEvent.CardDeleted(board.id, card.id, now))
                "DeleteCard '${action.title}'"
            }
            is WalkthroughAction.Pause -> "Pause ${action.durationMs}ms ${action.text}"
            is WalkthroughAction.SelectBoard -> {
                ForgeBoardFSM.emit(ForgeBoardEvent.BoardSelected(action.boardId, now))
                "SelectBoard ${action.boardId.value}"
            }
            is WalkthroughAction.HighlightColumn -> "HighlightColumn ${action.columnId.value}"
            is WalkthroughAction.ClearHighlight  -> "ClearHighlight"
        }
    }

    // ── Board state printer ───────────────────────────────────────────────────

    private fun printBoard(board: KanbanBoard, log: (String) -> Unit) {
        val cols = board.columns.sortedBy { it.order }
        val colWidth = 22
        val header = cols.joinToString("  ") { it.name.padEnd(colWidth) }
        log("│  ┌${header.replace(Regex("."), "─")}┐")
        log("│  │ $header │")
        log("│  ├${header.replace(Regex("."), "─")}┤")

        // Collect cards per column, pad to same height
        val colCards = cols.map { col ->
            board.cardsInColumn(col.id).map { card ->
                val prio = when (card.priority) {
                    CardPriority.CRITICAL -> "[!!]"
                    CardPriority.HIGH     -> "[! ]"
                    CardPriority.MEDIUM   -> "[  ]"
                    CardPriority.LOW      -> "[. ]"
                }
                ("$prio ${card.title}").take(colWidth).padEnd(colWidth)
            }
        }
        val maxRows = colCards.maxOfOrNull { it.size } ?: 0
        for (row in 0 until maxRows) {
            val line = cols.indices.joinToString("  ") { ci ->
                colCards[ci].getOrElse(row) { " ".repeat(colWidth) }
            }
            log("│  │ $line │")
        }
        if (maxRows == 0) log("│  │ ${" ".repeat(colWidth * cols.size + (cols.size - 1) * 2)} │")
        log("│  └${header.replace(Regex("."), "─")}┘")
    }

    // ── RGB frame conversion ──────────────────────────────────────────────────

    private fun imgToRgb24(img: BufferedImage): ByteArray {
        val w = img.width; val h = img.height
        val buf = ByteArray(w * h * 3)
        var i = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val rgb = img.getRGB(x, y)
                buf[i++] = ((rgb shr 16) and 0xFF).toByte()
                buf[i++] = ((rgb shr 8)  and 0xFF).toByte()
                buf[i++] = (rgb          and 0xFF).toByte()
            }
        }
        return buf
    }

    private fun emptyBoard() = borg.trikeshed.kanban.KanbanBoard(
        id = borg.trikeshed.kanban.KanbanBoardId("empty"),
        name = "Loading…",
        columns = emptyList(),
        cards = emptyList(),
    )
}
