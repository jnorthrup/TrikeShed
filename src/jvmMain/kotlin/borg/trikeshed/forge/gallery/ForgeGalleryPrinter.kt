package borg.trikeshed.forge.gallery

import borg.trikeshed.forge.blackboard.ForgeBlackboardView
import borg.trikeshed.parse.json.JsonSupport

/**
 * JVM printer for the gallery catalog + blackboard view.  Mirrors the data
 * that the browser blackboard consumes, but renders a fixed-width text grid so
 * the catalog is browsable on `java -jar trikeshed.jar --print-gallery` (or
 * via the dedicated Gradle task).
 *
 * Lives in `jvmMain` so it can use standard `java.io.PrintStream`.  The catalog
 * data itself stays in `commonMain` — the JVM renderer is a thin presentation.
 */
object ForgeGalleryPrinter {

    /** Width of the fixed-grid render (columns of terminal output). */
    private const val GRID_WIDTH: Int = 88

    /**
     * Render the catalog + blackboard view as a single text document.  The first
     * line is a header; the body is grouped by section, and ends with the
     * blackboard corner/title button map.
     */
    fun render(): String = buildString {
        appendLine(headerLine("Forge widget gallery — ${ForgeGalleryCatalog.CATALOG_VERSION}"))
        appendLine("Catalog: ${ForgeGalleryCatalog.widgets().size} widgets across " +
            "${ForgeGallerySection.values().size} sections")
        appendLine(rule())
        ForgeGallerySection.values().forEach { section ->
            val widgets = ForgeGalleryCatalog.bySection(section)
            if (widgets.isEmpty()) return@forEach
            appendLine("── ${section.name} (${widgets.size}) ".padEndVisual(GRID_WIDTH, '─'))
            widgets.forEach { widget ->
                appendLine(formatWidgetLine(widget))
            }
            appendLine(rule())
        }
        appendLine(headerLine("Forge blackboard view — ${ForgeBlackboardView.DEFAULT.surface}"))
        appendLine("Sections: " + ForgeBlackboardView.DEFAULT.sections.joinToString(", "))
        val cam = ForgeBlackboardView.DEFAULT.defaultCamera
        appendLine("Default camera  zoom=${"%.2f".format(cam.zoom)}  tilt=${"%.2f rad".format(cam.tilt)}  bounds=[${cam.minZoom}, ${cam.maxZoom}]")
        val cam3d = ForgeBlackboardView.DEFAULT.mode3D
        appendLine("Default 3D pose yaw=${"%.2f".format(cam3d.yawRadians)}  pitch=${"%.2f rad".format(cam3d.pitchRadians)}  distance=${"%.0f".format(cam3d.distance)}  focal=${"%.0f".format(cam3d.focalLength)}")
        appendLine("Default mode: ${ForgeBlackboardView.DEFAULT.defaultMode}")
        appendLine("3D layout:")
        ForgeBlackboardView.DEFAULT.layout3D.forEach { placement ->
            appendLine("  ${placement.sectionId.padEndVisual(10)} center=(${placement.centerX.toInt()},${placement.centerY.toInt()})  ${placement.width.toInt()}x${placement.height.toInt()}  elevation=${placement.elevation.toInt()}")
        }
        appendLine(rule())
        ForgeBlackboardView.DEFAULT.cornerButtons.forEach { btn ->
            appendLine("  ${btn.slot.name.padEndVisual(14)} ${btn.id.padEndVisual(20)} hotkey=[${btn.hotkey}]  ${btn.label}")
        }
        appendLine(rule())
        appendLine("Catalog entries:")
        ForgeGalleryCatalog.widgets().forEach { widget ->
            appendLine("  " + formatWidgetLine(widget))
        }
    }

    /** Same payload as the browser seed — portable via [JsonSupport]. */
    fun renderJson(): String = JsonSupport.stringify(
        linkedMapOf(
            "catalog" to ForgeGalleryCatalog.toJsonValue(),
            "blackboard" to ForgeBlackboardView.DEFAULT.let { view ->
                linkedMapOf(
                    "surface" to view.surface,
                    "sections" to view.sections,
                    "defaultCamera" to linkedMapOf(
                        "zoom" to view.defaultCamera.zoom,
                        "tilt" to view.defaultCamera.tilt,
                        "minZoom" to view.defaultCamera.minZoom,
                        "maxZoom" to view.defaultCamera.maxZoom,
                    ),
                    "cornerButtons" to view.cornerButtons.map {
                        linkedMapOf(
                            "slot" to it.slot.name,
                            "id" to it.id,
                            "label" to it.label,
                            "hotkey" to it.hotkey,
                            "surface" to it.surface,
                        )
                    },
                )
            },
        )
    )

    private fun formatWidgetLine(widget: ForgeGalleryWidget): String {
        val left = "${widget.id.padEndVisual(20)} ${widget.name.padEndVisual(20)}"
        val maxSynW = GRID_WIDTH - left.visualWidth() - 2
        val synopsis = if (widget.synopsis.visualWidth() > maxSynW) widget.synopsis.takeVisual(maxSynW - 1) + "…" else widget.synopsis.padEndVisual(maxSynW)
        return "$left  $synopsis"
    }

    private fun headerLine(label: String): String =
        "── $label ".padEndVisual(GRID_WIDTH, '─')

    private fun rule(): String = "─".repeat(GRID_WIDTH)
}

private fun String.visualWidth(): Int {
    var width = 0
    var i = 0
    while (i < this.length) {
        val cp = this.codePointAt(i)
        val isWide = cp >= 0x1100 &&
            (cp <= 0x115F || cp == 0x2329 || cp == 0x232A ||
            (cp >= 0x2E80 && cp <= 0xA4C6) || (cp >= 0xAC00 && cp <= 0xD7A3) ||
            (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0xFE10 && cp <= 0xFE19) ||
            (cp >= 0xFE30 && cp <= 0xFE6F) || (cp >= 0xFF00 && cp <= 0xFF60) ||
            (cp >= 0xFFE0 && cp <= 0xFFE6) || (cp >= 0x20000 && cp <= 0x2FFFD) ||
            (cp >= 0x30000 && cp <= 0x3FFFD))
        width += if (isWide) 2 else 1
        i += Character.charCount(cp)
    }
    return width
}

private fun String.padEndVisual(targetWidth: Int, padChar: Char = ' '): String {
    val currentWidth = this.visualWidth()
    if (currentWidth >= targetWidth) return this
    return this + padChar.toString().repeat(targetWidth - currentWidth)
}

private fun String.takeVisual(targetWidth: Int): String {
    var width = 0
    var i = 0
    var charCount = 0
    while (i < this.length) {
        val cp = this.codePointAt(i)
        val isWide = cp >= 0x1100 &&
            (cp <= 0x115F || cp == 0x2329 || cp == 0x232A ||
            (cp >= 0x2E80 && cp <= 0xA4C6) || (cp >= 0xAC00 && cp <= 0xD7A3) ||
            (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0xFE10 && cp <= 0xFE19) ||
            (cp >= 0xFE30 && cp <= 0xFE6F) || (cp >= 0xFF00 && cp <= 0xFF60) ||
            (cp >= 0xFFE0 && cp <= 0xFFE6) || (cp >= 0x20000 && cp <= 0x2FFFD) ||
            (cp >= 0x30000 && cp <= 0x3FFFD))
        val w = if (isWide) 2 else 1
        if (width + w > targetWidth) break
        width += w
        val c = Character.charCount(cp)
        i += c
        charCount += c
    }
    return this.substring(0, charCount)
}

/**
 * Standard JVM entrypoint — prints the catalog to stdout.  Hooked up via a
 * Gradle `JavaExec` task so contributors can sanity-check the catalog without
 * launching a browser.
 */
fun main() {
    println(ForgeGalleryPrinter.render())
}