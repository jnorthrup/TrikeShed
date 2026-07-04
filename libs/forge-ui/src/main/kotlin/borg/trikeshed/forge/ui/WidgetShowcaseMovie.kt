package borg.trikeshed.forge.ui

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.io.OutputStream

private val WIDGET_BG = Color(0x0F, 0x14, 0x1B)
private val WIDGET_PANEL = Color(0x11, 0x18, 0x21)
private val WIDGET_PANEL_2 = Color(0x16, 0x1E, 0x28)
private val WIDGET_TEXT = Color(0xD3, 0xDB, 0xE6)
private val WIDGET_MUTED = Color(0x75, 0x83, 0x95)
private val WIDGET_BORDER = Color(0x1D, 0x2A, 0x38)

object WidgetShowcaseMovieRenderer {
    private val titleFont = Font("SansSerif", Font.BOLD, 24)
    private val bodyFont = Font("SansSerif", Font.PLAIN, 14)
    private val smallFont = Font("SansSerif", Font.PLAIN, 11)
    private val widgetTitleFont = Font("SansSerif", Font.BOLD, 16)

    fun render(
        page: WidgetShowcasePage,
        width: Int = 1280,
        height: Int = 800,
        selectedWidgetIndex: Int = 0,
        narration: String = "",
    ): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

        g.color = WIDGET_BG
        g.fillRect(0, 0, width, height)

        // Title rail
        g.color = WIDGET_PANEL
        g.fillRoundRect(20, 20, width - 40, 88, 22, 22)
        g.color = WIDGET_BORDER
        g.stroke = BasicStroke(1f)
        g.drawRoundRect(20, 20, width - 40, 88, 22, 22)
        g.color = WIDGET_TEXT
        g.font = titleFont
        g.drawString(page.title, 42, 56)
        g.font = bodyFont
        g.color = WIDGET_MUTED
        g.drawString(page.subtitle, 42, 80)
        g.drawString("layout: ${page.layout.name.lowercase()}", width - 220, 56)

        // Side notes
        g.color = WIDGET_PANEL_2
        roundRect(g, width - 340, 128, 300, height - 168, 18)
        g.color = WIDGET_BORDER
        g.drawRoundRect(width - 340, 128, 300, height - 168, 18, 18)
        g.color = WIDGET_TEXT
        g.font = widgetTitleFont
        g.drawString("Template notes", width - 310, 160)
        g.font = bodyFont
        var noteY = 188
        page.templateNotes.forEach { note ->
            g.color = WIDGET_MUTED
            g.drawString("• $note", width - 310, noteY)
            noteY += 24
        }

        g.color = WIDGET_PANEL
        roundRect(g, 20, 128, width - 380, height - 168, 18)
        g.color = WIDGET_BORDER
        g.drawRoundRect(20, 128, width - 380, height - 168, 18, 18)

        val left = 40
        val top = 150
        val previewWidth = width - 420
        when (page.layout) {
            WidgetShowcaseLayout.HERO -> renderHero(g, page, left, top, previewWidth, selectedWidgetIndex)
            WidgetShowcaseLayout.SPLIT -> renderSplit(g, page, left, top, previewWidth, selectedWidgetIndex)
            WidgetShowcaseLayout.GRID -> renderGrid(g, page, left, top, previewWidth, selectedWidgetIndex)
            WidgetShowcaseLayout.STACK -> renderStack(g, page, left, top, previewWidth, selectedWidgetIndex)
            WidgetShowcaseLayout.DOC -> renderDoc(g, page, left, top, previewWidth, selectedWidgetIndex)
            WidgetShowcaseLayout.RADAR -> renderRadar(g, page, left, top, previewWidth, selectedWidgetIndex)
        }

        if (narration.isNotBlank()) {
            g.color = Color(0, 0, 0, 180)
            g.fillRoundRect(40, height - 82, width - 380, 48, 12, 12)
            g.color = Color.WHITE
            g.font = bodyFont
            val fm = g.fontMetrics
            g.drawString(narration, 60, height - 82 + 28 + fm.ascent / 2 - 2)
        }

        g.dispose()
        return img
    }

    private fun renderHero(
        g: Graphics2D,
        page: WidgetShowcasePage,
        left: Int,
        top: Int,
        width: Int,
        selectedWidgetIndex: Int,
    ) {
        val heroHeight = 140
        g.color = Color(0x12, 0x1A, 0x24)
        g.fillRoundRect(left, top, width, heroHeight, 18, 18)
        g.color = Color(0x28, 0x33, 0x43)
        g.drawRoundRect(left, top, width, heroHeight, 18, 18)
        g.color = WIDGET_TEXT
        g.font = titleFont
        g.drawString(page.title, left + 24, top + 52)
        g.font = bodyFont
        g.color = WIDGET_MUTED
        g.drawString(page.subtitle, left + 24, top + 82)

        val rowY = top + heroHeight + 18
        drawWidgetRow(g, page.widgets.take(2), left, rowY, width, selectedWidgetIndex, startIndex = 0)
        drawWidgetRow(g, page.widgets.drop(2), left, rowY + 180, width, selectedWidgetIndex, startIndex = 2)
    }

    private fun renderSplit(
        g: Graphics2D,
        page: WidgetShowcasePage,
        left: Int,
        top: Int,
        width: Int,
        selectedWidgetIndex: Int,
    ) {
        val colW = (width - 20) / 2
        drawCardList(g, page.widgets.take(2), left, top, colW, selectedWidgetIndex, 0)
        drawInspectorCard(g, page.widgets.getOrNull(selectedWidgetIndex.coerceIn(page.widgets.indices)) ?: page.widgets.first(), left + colW + 20, top, colW, page)
        drawWidgetRow(g, page.widgets.drop(2), left, top + 240, width, selectedWidgetIndex, startIndex = 2)
    }

    private fun renderGrid(
        g: Graphics2D,
        page: WidgetShowcasePage,
        left: Int,
        top: Int,
        width: Int,
        selectedWidgetIndex: Int,
    ) {
        val colW = (width - 20) / 2
        drawGridCell(g, page.widgets[0], left, top, colW, 0, selectedWidgetIndex)
        drawGridCell(g, page.widgets[1], left + colW + 20, top, colW, 1, selectedWidgetIndex)
        drawGridCell(g, page.widgets[2], left, top + 170, colW, 2, selectedWidgetIndex)
        drawGridCell(g, page.widgets[3], left + colW + 20, top + 170, colW, 3, selectedWidgetIndex)
    }

    private fun renderStack(
        g: Graphics2D,
        page: WidgetShowcasePage,
        left: Int,
        top: Int,
        width: Int,
        selectedWidgetIndex: Int,
    ) {
        var y = top
        page.widgets.forEachIndexed { index, widget ->
            drawStackCard(g, widget, left, y, width, selectedWidgetIndex == index)
            y += 112
        }
    }

    private fun renderDoc(
        g: Graphics2D,
        page: WidgetShowcasePage,
        left: Int,
        top: Int,
        width: Int,
        selectedWidgetIndex: Int,
    ) {
        val stageH = 440
        val outlineW = (width * 0.36).toInt()
        val readingX = left + outlineW + 18
        val readingW = width - outlineW - 18
        g.color = Color(0x0E, 0x14, 0x1D)
        g.fillRoundRect(left, top, width, stageH, 22, 22)
        g.color = Color(0x29, 0x37, 0x48)
        g.drawRoundRect(left, top, width, stageH, 22, 22)

        g.color = Color(0x16, 0x20, 0x2B)
        g.fillRoundRect(left + 14, top + 14, outlineW - 22, stageH - 28, 18, 18)
        g.color = Color(0x2E, 0x3C, 0x4D)
        g.drawRoundRect(left + 14, top + 14, outlineW - 22, stageH - 28, 18, 18)
        g.color = WIDGET_TEXT
        g.font = widgetTitleFont
        g.drawString("Outline", left + 34, top + 42)
        g.font = bodyFont
        g.color = WIDGET_MUTED
        g.drawString("A document that routes into the live gallery.", left + 34, top + 64)

        var outlineY = top + 88
        page.widgets.forEachIndexed { index, widget ->
            drawStackCard(g, widget, left + 28, outlineY, outlineW - 46, selectedWidgetIndex == index)
            outlineY += 110
        }

        val selectedIndex = selectedWidgetIndex.coerceIn(page.widgets.indices)
        val widget = page.widgets[selectedIndex]
        g.color = Color(0x13, 0x1A, 0x25)
        g.fillRoundRect(readingX, top + 14, readingW - 14, stageH - 28, 18, 18)
        g.color = Color(0x30, 0x40, 0x52)
        g.drawRoundRect(readingX, top + 14, readingW - 14, stageH - 28, 18, 18)
        g.color = WIDGET_TEXT
        g.font = smallFont
        g.drawString("Intelligent document", readingX + 22, top + 42)
        g.font = titleFont
        g.drawString(widget.title, readingX + 22, top + 76)
        g.font = bodyFont
        g.color = WIDGET_MUTED
        g.drawString(widget.body, readingX + 22, top + 104)
        g.drawString("value: ${widget.value}", readingX + 22, top + 134)
        g.drawString("kind: ${widget.kind.name.lowercase()}", readingX + 22, top + 156)
        g.drawString("tone: ${widget.tone.name.lowercase()}", readingX + 22, top + 178)

        g.color = WIDGET_TEXT
        g.font = widgetTitleFont
        g.drawString("Linked sections", readingX + 22, top + 218)
        g.font = bodyFont
        var linkY = top + 244
        widget.links.take(4).forEach { link ->
            g.color = widgetAccent(widget).darker()
            g.fillRoundRect(readingX + 18, linkY - 16, readingW - 52, 26, 12, 12)
            g.color = WIDGET_TEXT
            g.drawString("→ $link", readingX + 30, linkY)
            linkY += 34
        }

        g.color = WIDGET_TEXT
        g.font = widgetTitleFont
        g.drawString("Document notes", readingX + 22, top + 332)
        g.font = bodyFont
        var noteY = top + 356
        page.templateNotes.take(3).forEach { note ->
            g.color = WIDGET_MUTED
            g.drawString("• ${truncate(note, readingW - 80, g.fontMetrics)}", readingX + 22, noteY)
            noteY += 24
        }
    }

    private fun renderRadar(
        g: Graphics2D,
        page: WidgetShowcasePage,
        left: Int,
        top: Int,
        width: Int,
        selectedWidgetIndex: Int,
    ) {
        val h = 420
        val stageX = left
        val stageY = top
        g.color = Color(0x0C, 0x11, 0x19)
        g.fillRoundRect(stageX, stageY, width, h, 22, 22)
        g.color = Color(0x26, 0x33, 0x45)
        g.drawRoundRect(stageX, stageY, width, h, 22, 22)

        val cx = stageX + width / 2
        val cy = stageY + h / 2
        val ring = (minOf(width, h) * 0.28f).toInt()
        g.color = Color(0x22, 0x50, 0x80)
        g.stroke = BasicStroke(1.2f)
        g.drawOval(cx - ring, cy - ring, ring * 2, ring * 2)
        g.drawOval(cx - ring / 2, cy - ring / 2, ring, ring)
        g.drawLine(stageX + 16, cy, stageX + width - 16, cy)
        g.drawLine(cx, stageY + 16, cx, stageY + h - 16)

        val selectedIndex = selectedWidgetIndex.coerceIn(page.widgets.indices)
        val selected = page.widgets[selectedIndex]
        val others = page.widgets.withIndex().filter { it.index != selectedIndex }
        val placements = mutableMapOf<String, Pair<Int, Int>>()
        placements[selected.id] = cx to cy
        val radius = ring + 32
        others.forEachIndexed { idx, entry ->
            val angle = (-Math.PI / 2.0) + idx * (2 * Math.PI / maxOf(1, others.size).toDouble())
            val weight = 1.0 + entry.value.links.size * 0.05
            val x = (cx + kotlin.math.cos(angle) * radius * weight).toInt()
            val y = (cy + kotlin.math.sin(angle) * radius * weight).toInt()
            placements[entry.value.id] = x to y
        }

        g.stroke = BasicStroke(2f)
        page.widgets.forEach { widget ->
            val from = placements[widget.id] ?: return@forEach
            widget.links.forEach { linkId ->
                val to = placements[linkId] ?: return@forEach
                g.color = widgetAccent(widget).darker().darker()
                g.drawLine(from.first, from.second, to.first, to.second)
            }
        }

        placements.forEach { (id, xy) ->
            val widget = page.widgets.first { it.id == id }
            val selectedNode = widget.id == selected.id
            val w = if (selectedNode) 186 else 160
            val hh = if (selectedNode) 92 else 82
            val x = xy.first - w / 2
            val y = xy.second - hh / 2
            g.color = Color(0, 0, 0, if (selectedNode) 140 else 100)
            g.fillRoundRect(x + 4, y + 8, w, hh, 18, 18)
            g.color = Color(20, 28, 38)
            g.fillRoundRect(x, y, w, hh, 18, 18)
            g.color = widgetAccent(widget)
            g.drawRoundRect(x, y, w, hh, 18, 18)
            g.color = WIDGET_TEXT
            g.font = smallFont
            g.drawString(widget.kind.name.lowercase(), x + 12, y + 18)
            g.font = widgetTitleFont
            g.drawString(widget.title, x + 12, y + 42)
            g.font = bodyFont
            g.color = widgetAccent(widget)
            g.drawString(widget.value, x + 12, y + 62)
        }
    }

    private fun widgetAccent(widget: WidgetShowcaseWidget): Color {
        val hex = widget.accent.removePrefix("#")
        val value = hex.toLong(16)
        val rgb = if (hex.length <= 6) value else value and 0xFFFFFF
        return Color(((rgb shr 16) and 0xFF).toInt(), ((rgb shr 8) and 0xFF).toInt(), (rgb and 0xFF).toInt())
    }

    private fun drawWidgetRow(
        g: Graphics2D,
        widgets: List<WidgetShowcaseWidget>,
        left: Int,
        top: Int,
        width: Int,
        selectedWidgetIndex: Int,
        startIndex: Int,
    ) {
        val rowCount = widgets.size.coerceAtLeast(1)
        val gap = 16
        val cardW = (width - gap * (rowCount - 1)) / rowCount
        widgets.forEachIndexed { index, widget ->
            drawStackCard(g, widget, left + index * (cardW + gap), top, cardW, selectedWidgetIndex == startIndex + index)
        }
    }

    private fun drawCardList(
        g: Graphics2D,
        widgets: List<WidgetShowcaseWidget>,
        left: Int,
        top: Int,
        width: Int,
        selectedWidgetIndex: Int,
        startIndex: Int,
    ) {
        var y = top
        widgets.forEachIndexed { index, widget ->
            drawStackCard(g, widget, left, y, width, selectedWidgetIndex == startIndex + index)
            y += 112
        }
    }

    private fun drawGridCell(
        g: Graphics2D,
        widget: WidgetShowcaseWidget,
        left: Int,
        top: Int,
        width: Int,
        index: Int,
        selectedWidgetIndex: Int,
    ) {
        drawStackCard(g, widget, left, top, width, selectedWidgetIndex == index)
    }

    private fun drawInspectorCard(
        g: Graphics2D,
        widget: WidgetShowcaseWidget,
        left: Int,
        top: Int,
        width: Int,
        page: WidgetShowcasePage,
    ) {
        g.color = Color(0x17, 0x22, 0x2D)
        g.fillRoundRect(left, top, width, 236, 18, 18)
        g.color = Color(0x2B, 0x39, 0x49)
        g.drawRoundRect(left, top, width, 236, 18, 18)
        g.font = widgetTitleFont
        g.color = WIDGET_TEXT
        g.drawString("Inspector", left + 20, top + 34)
        g.drawString(widget.title, left + 20, top + 66)
        g.font = bodyFont
        g.color = WIDGET_MUTED
        g.drawString(widget.body, left + 20, top + 92)
        g.drawString("tone: ${widget.tone.name.lowercase()}", left + 20, top + 126)
        g.drawString("accent: ${widget.accent}", left + 20, top + 150)
        g.drawString("template: ${page.layout.name.lowercase()}", left + 20, top + 184)
    }

    private fun drawStackCard(
        g: Graphics2D,
        widget: WidgetShowcaseWidget,
        left: Int,
        top: Int,
        width: Int,
        selected: Boolean,
    ) {
        val accent = parseColor(widget.accent)
        g.color = if (selected) Color(0x1B, 0x27, 0x35) else Color(0x17, 0x22, 0x2D)
        g.fillRoundRect(left, top, width, 96, 16, 16)
        g.color = if (selected) accent else Color(0x2B, 0x39, 0x49)
        g.stroke = BasicStroke(if (selected) 2f else 1f)
        g.drawRoundRect(left, top, width, 96, 16, 16)
        g.color = accent
        g.fillRoundRect(left + 12, top + 12, 10, 72, 8, 8)
        g.color = WIDGET_TEXT
        g.font = widgetTitleFont
        g.drawString(widget.title, left + 34, top + 32)
        g.font = bodyFont
        g.color = WIDGET_MUTED
        g.drawString(widget.value, left + 34, top + 54)
        g.drawString(truncate(widget.body, width - 150, g.fontMetrics), left + 34, top + 76)
    }

    private fun roundRect(g: Graphics2D, x: Int, y: Int, w: Int, h: Int, arc: Int) {
        g.fillRoundRect(x, y, w, h, arc, arc)
    }

    private fun truncate(text: String, maxWidth: Int, fm: FontMetrics): String {
        if (fm.stringWidth(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && fm.stringWidth(text.take(end) + "…") > maxWidth) end--
        return text.take(end) + "…"
    }

    private fun parseColor(hex: String): Color = Color.decode(hex)
}

object WidgetShowcaseMovieRunner {
    data class RunResult(
        val outputFile: File,
        val totalFrames: Int,
        val durationMs: Long,
    )

    fun run(
        page: WidgetShowcasePage,
        outputFile: File,
        ffmpegPath: String = "ffmpeg",
        verbose: Boolean = true,
    ): RunResult {
        outputFile.parentFile?.mkdirs()
        val width = 1280
        val height = 800
        val fps = 30
        val ffmpegArgs = listOf(
            ffmpegPath, "-y",
            "-f", "rawvideo",
            "-vcodec", "rawvideo",
            "-s", "${width}x$height",
            "-pix_fmt", "rgb24",
            "-r", fps.toString(),
            "-i", "pipe:0",
            "-c:v", "libx264",
            "-preset", "fast",
            "-crf", "18",
            "-pix_fmt", "yuv420p",
            "-movflags", "+faststart",
            "-an",
            outputFile.absolutePath,
        )

        fun log(message: String) {
            if (verbose) println(message)
        }

        val started = System.currentTimeMillis()
        val process = ProcessBuilder(ffmpegArgs)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        val pipe: OutputStream = process.outputStream

        var totalFrames = 0
        page.widgets.forEachIndexed { index, widget ->
            repeat(30) {
                val frame = WidgetShowcaseMovieRenderer.render(
                    page = page,
                    width = width,
                    height = height,
                    selectedWidgetIndex = index,
                    narration = widget.title,
                )
                pipe.write(imgToRgb24(frame))
                totalFrames++
            }
        }
        repeat(45) {
            val frame = WidgetShowcaseMovieRenderer.render(
                page = page,
                width = width,
                height = height,
                selectedWidgetIndex = page.widgets.lastIndex,
                narration = "Recorded separately to movie: ${page.title}",
            )
            pipe.write(imgToRgb24(frame))
            totalFrames++
        }

        log("▶ Rendering $totalFrames frames for ${page.title}")
        pipe.flush()
        pipe.close()
        val exit = process.waitFor()
        val elapsed = System.currentTimeMillis() - started
        if (exit != 0) {
            error("ffmpeg exited with $exit while rendering ${page.title}")
        }
        return RunResult(outputFile = outputFile, totalFrames = totalFrames, durationMs = elapsed)
    }

    private fun imgToRgb24(img: BufferedImage): ByteArray {
        val w = img.width
        val h = img.height
        val buf = ByteArray(w * h * 3)
        var i = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val rgb = img.getRGB(x, y)
                buf[i++] = ((rgb shr 16) and 0xFF).toByte()
                buf[i++] = ((rgb shr 8) and 0xFF).toByte()
                buf[i++] = (rgb and 0xFF).toByte()
            }
        }
        return buf
    }
}
