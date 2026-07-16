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
            appendLine("── ${section.name} (${widgets.size}) ".padEnd(GRID_WIDTH, '─'))
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
            appendLine("  ${placement.sectionId.padEnd(10)} center=(${placement.centerX.toInt()},${placement.centerY.toInt()})  ${placement.width.toInt()}x${placement.height.toInt()}  elevation=${placement.elevation.toInt()}")
        }
        appendLine(rule())
        ForgeBlackboardView.DEFAULT.cornerButtons.forEach { btn ->
            appendLine("  ${btn.slot.name.padEnd(14)} ${btn.id.padEnd(20)} hotkey=[${btn.hotkey}]  ${btn.label}")
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
        val left = "${widget.id.padEnd(20)} ${widget.name.padEnd(20)}"
        val synopsis = widget.synopsis.take(GRID_WIDTH - left.length - 2)
        return "$left  $synopsis"
    }

    private fun headerLine(label: String): String =
        "── $label ".padEnd(GRID_WIDTH, '─')

    private fun rule(): String = "─".repeat(GRID_WIDTH)
}

/**
 * Standard JVM entrypoint — prints the catalog to stdout.  Hooked up via a
 * Gradle `JavaExec` task so contributors can sanity-check the catalog without
 * launching a browser.
 */
fun main() {
    println(ForgeGalleryPrinter.render())
}