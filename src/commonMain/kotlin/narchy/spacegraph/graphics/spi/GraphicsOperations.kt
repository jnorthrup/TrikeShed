package narchy.spacegraph.graphics.spi

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import narchy.spacegraph.Rect
import narchy.spacegraph.Vec3
import narchy.spacegraph.Viewport

enum class GraphicsCapability { PATHS, TEXT, IMAGES, MESHES, INSTANCING, DOM, EDITABLE_TEXT, AUDIO, VIDEO, CAPTURE }
enum class GraphicsBackend { SVG, CANVAS, GL }
data class GraphicsCapabilities(val provider: String, val backend: GraphicsBackend, val supported: Set<GraphicsCapability>, val hardwareAccelerated: Boolean?)
data class Rgba(val red: Int, val green: Int, val blue: Int, val alpha: Double = 1.0) {
    init { require(red in 0..255 && green in 0..255 && blue in 0..255 && alpha.isFinite() && alpha in 0.0..1.0) }
    val css get() = "rgba($red,$green,$blue,$alpha)"
    companion object {
        val BLACK = Rgba(0, 0, 0); val WHITE = Rgba(255, 255, 255)
        fun parse(value: String, fallback: Rgba = Rgba(70, 120, 190)): Rgba {
            val s = value.trim().lowercase()
            if (s == "transparent") return Rgba(0, 0, 0, 0.0)
            val named = mapOf("white" to 0xffffff, "black" to 0, "red" to 0xff0000, "green" to 0x008000,
                "blue" to 0x0000ff, "gray" to 0x808080, "yellow" to 0xffff00, "orange" to 0xffa500)
            val hex = s.removePrefix("#").removePrefix("0x")
            val number = named[s] ?: if (s.startsWith("#") || s.startsWith("0x")) {
                when (hex.length) { 3 -> hex.flatMap { listOf(it, it) }.joinToString("").toIntOrNull(16)
                    6 -> hex.toIntOrNull(16); else -> null }
            } else s.toDoubleOrNull()?.toInt()
            return number?.let { Rgba((it shr 16) and 255, (it shr 8) and 255, it and 255) } ?: fallback
        }
    }
}

sealed interface PathPart {
    data class Move(val p: Vec3) : PathPart
    data class Line(val p: Vec3) : PathPart
    data class Quadratic(val control: Vec3, val end: Vec3) : PathPart
    data class Cubic(val c1: Vec3, val c2: Vec3, val end: Vec3) : PathPart
    data object Close : PathPart
}

sealed interface DrawItem {
    val entityId: String
    val clip: Rect?
    data class Path(
        override val entityId: String, val parts: Series<PathPart>, val fill: Rgba? = null,
        val stroke: Rgba? = null, val width: Double = 1.0, val dash: Series<Double> = emptySeriesOf(),
        val dashOffset: Double = 0.0, override val clip: Rect? = null,
    ) : DrawItem
    data class Text(
        override val entityId: String, val text: String, val position: Vec3,
        val color: Rgba = Rgba.BLACK, val size: Double = 14.0, val font: String = "sans-serif",
        val maxWidth: Double? = null, override val clip: Rect? = null,
    ) : DrawItem
    data class Image(
        override val entityId: String, val resource: String, val bounds: Rect, override val clip: Rect? = null,
    ) : DrawItem
    data class Content(
        override val entityId: String, val kind: String, val content: String, val bounds: Rect,
        val requiredCapability: GraphicsCapability, override val clip: Rect? = null,
    ) : DrawItem
}

data class FramePlan(val viewport: Viewport, val items: Series<DrawItem>, val background: Rgba = Rgba(248, 250, 252), val revision: Long = 0)
data class GraphicsRefusal(val entityId: String, val capability: GraphicsCapability, val reason: String)
data class FrameReceipt(val provider: String, val revision: Long, val drawn: Int, val refusals: Series<GraphicsRefusal>)

/** Surface and graphics handles remain private to each platform provider. */
interface GraphicsOperations {
    val capabilities: GraphicsCapabilities
    suspend fun open(viewport: Viewport)
    suspend fun render(frame: FramePlan): FrameReceipt
    suspend fun close()
}
