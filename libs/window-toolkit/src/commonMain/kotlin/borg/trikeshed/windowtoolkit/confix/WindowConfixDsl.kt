package borg.trikeshed.windowtoolkit.confix

import borg.trikeshed.lib.*
import borg.trikeshed.miniduck.v2.FacetedCursor
import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.usersignals.facets.FacetedSignal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@DslMarker
annotation class WindowConfixDsl

/**
 * Window-confix DSL — composes Compose UI from ConfixDoc + FacetedCursor.
 * 
 * Shape: ConfixDoc → FacetedCursor → FacetedSignal → ComposeTree
 * 
 * Each Confix node becomes a composable; facets drive reactive updates.
 * Valhalla: inline value classes for layout constraints, dense twins for style attrs.
 */
@WindowConfixDsl
class WindowConfixBuilder(
    private val doc: ConfixDoc,
    private val cursor: FacetedCursor,
    private val signals: FacetedSignal<Signal<*>>,
) {

    /** Render the confix doc as a Compose UI tree. */
    fun compose(): ComposeNode = doc.root.compose(this)

    /** Bind a confix path to a signal facet. */
    fun bind(path: JsPath, signal: FacetKey) {
        // Signal facet drives confix node updates
    }

    /** Create a layout constraint from confix attributes. */
    fun layout(path: JsPath): LayoutConstraints = LayoutConstraints.from(doc, path)

    /** Extract style facet for theming. */
    fun style(path: JsPath): StyleFacet = StyleFacet.from(doc, path)

    /** Subscribe to cursor changes for reactive UI. */
    fun subscribe(path: JsPath, onChange: (Any?) -> Unit): FacetFn<Signal<*>, Signal<*>> = 
        FacetFn { it.facet(path) { onChange(it.value) } }

    companion object {
        fun from(doc: ConfixDoc, cursor: FacetedCursor, signals: FacetedSignal<Signal<*>>): WindowConfixBuilder =
            WindowConfixBuilder(doc, cursor, signals)
    }
}

/** Compose node — zero-overhead value class wrapper. */
@JvmInline
value class ComposeNode(
    private val kind: String,
    private val props: Map<String, Any>,
    private val children: List<ComposeNode> = emptyList(),
) {

    infix fun child(node: ComposeNode): ComposeNode = copy(children = children + node)

    infix fun prop(key: String, value: Any): ComposeNode = copy(props = props + key to value)

    fun render(): String = "$kind(${props.joinToString()}) ${children.joinToString(" ")}"
    
    companion object {
        fun row(props: Map<String, Any>, children: List<ComposeNode>) = ComposeNode("Row", props, children)
        fun column(props: Map<String, Any>, children: List<ComposeNode>) = ComposeNode("Column", props, children)
        fun box(props: Map<String, Any>, children: List<ComposeNode>) = ComposeNode("Box", props, children)
        fun text(text: String, style: TextStyle = TextStyle()) = ComposeNode("Text", mapOf("text" to text, "style" to style))
        fun spacer(modifier: Modifier = Modifier()) = ComposeNode("Spacer", mapOf("modifier" to modifier))
        fun div(props: Map<String, Any>, children: List<ComposeNode>) = ComposeNode("Div", props, children)
    }
}

/** Layout constraints from confix attributes. */
@JvmInline
value class LayoutConstraints(
    private val encoded: Long,
) {

    val width: Dp get() = Dp((encoded shr 32).toInt())
    val height: Dp get() = Dp((encoded and 0xFFFFFFFFL).toInt())
    val fillWidth: Boolean get() = (encoded shr 62) == 1
    val fillHeight: Boolean get() = (encoded shr 61) == 1

    fun withWidth(w: Dp): LayoutConstraints = copy((w.value.toLong() shl 32) and 0xFFFFFFFF00000000L or encoded and 0xFFFFFFFFL)
    fun withHeight(h: Dp): LayoutConstraints = copy(encoded and 0xFFFFFFFF00000000L or h.value.toLong().toInt())
    fun fillMaxWidth(): LayoutConstraints = copy(encoded or (1L shl 62))
    fun fillMaxHeight(): LayoutConstraints = copy(encoded or (1L shl 61))

    companion object {
        fun from(doc: ConfixDoc, path: JsPath): LayoutConstraints = 
            doc.at(path)
                .let { node ->
                    val w = node["width"]?.let { it.int }?.toDp() ?: Dp(0)
                    val h = node["height"]?.let { it.int }?.toDp() ?: Dp(0)
                    val fw = node["fillWidth"]?.boolean == true
                    val fh = node["fillHeight"]?.boolean == true
                    LayoutConstraints(
                        (w.value.toLong() shl 32) or
                        h.value.toLong() or
                        (if (fw) 1L shl 62 else 0) or
                        (if (fh) 1L shl 61 else 0)
                    )
                } ?: LayoutConstraints(0)

        inline fun <reified T> at(doc: ConfixDoc, path: JsPath): T? = TODO()
    }
}

/** Density-independent pixels — Valhalla inline class. */
@JvmInline
value class Dp(val value: Int) {
    infix fun toPx(density: Float = 1.0f): Int = (value * density).toInt()
    infix fun sp(): Sp = Sp(value)
}

/** Scale-independent pixels. */
@JvmInline
value class Sp(val value: Int)

/** Style facet — extracted from confix theme attributes. */
@JvmInline
value class StyleFacet(
    private val colors: Map<String, Color> = emptyMap(),
    private val spacing: Map<String, Dp> = emptyMap(),
    private val typography: Map<String, TextStyle> = emptyMap(),
) {

    fun color(key: String): Color? = colors[key]
    fun spacing(key: String): Dp? = spacing[key]
    fun textStyle(key: String): TextStyle? = typography[key]

    fun withColor(key: String, color: Color): StyleFacet = copy(colors = colors + key to color)
    fun withSpacing(key: String, dp: Dp): StyleFacet = copy(spacing = spacing + key to dp)

    companion object {
        fun from(doc: ConfixDoc, path: JsPath): StyleFacet = {
            val node = doc.at(path)
            val colors = node?.children?.filter { it.name == "color" }
                ?.associate { it.name to Color(it.value?.int ?: 0) } ?: emptyMap()
            val spacing = node?.children?.filter { it.name == "spacing" }
                ?.associate { it.name to Dp(it.value?.int ?: 0) } ?: emptyMap()
            val typography = node?.children?.filter { it.name == "textStyle" }
                ?.associate { it.name to TextStyle.parse(it.value?.string ?: "") } ?: emptyMap()
            StyleFacet(colors, spacing, typography)
        } ?: StyleFacet()
    }
}

/** Color — Valhalla packed ARGB. */
@JvmInline
value class Color(private val argb: Int) {
    val alpha: Float get() = ((argb shr 24) and 0xFF) / 255f
    val red: Float get() = ((argb shr 16) and 0xFF) / 255f
    val green: Float get() = ((argb shr 8) and 0xFF) / 255f
    val blue: Float get() = (argb and 0xFF) / 255f

    companion object {
        fun rgb(r: Int, g: Int, b: Int): Color = Color(0xFF000000 or (r shl 16) or (g shl 8) or b)
        fun argb(a: Int, r: Int, g: Int, b: Int): Color = Color((a shl 24) or (r shl 16) or (g shl 8) or b)
        val Black = Color(0xFF000000)
        val White = Color(0xFFFFFFFF)
        val Transparent = Color(0x00000000)
        fun fromHex(hex: String): Color = Color(Integer.parseInt(hex.removePrefix("#"), 16))
    }
}

/** Text style — packed typography attributes. */
@JvmInline
value class TextStyle(
    private val encoded: Long = 0,
) {
    val fontSize: Sp get() = Sp((encoded shr 48).toInt())
    val fontWeight: FontWeight get() = FontWeight(((encoded shr 40) and 0xFF).toInt())
    val color: Color get() = Color((encoded and 0xFFFFFFFFL).toInt())
    val letterSpacing: Dp get() = Dp(((encoded shr 32) and 0xFF).toInt())
    val lineHeight: Float get() = ((encoded shr 24) and 0xFF) / 100f

    fun withFontSize(sp: Sp): TextStyle = copy(Byte.toInt() shl 48 or encoded and 0x00FFFFFFFFFFFFFFFFL)
    fun withWeight(w: FontWeight): TextStyle = copy(w.value.toLong() shl 40 or encoded and 0xFF00FFFFFFFFFFFFL)
    fun withColor(c: Color): TextStyle = copy(c.argb.toLong() or encoded and 0xFFFFFFFFFF000000L)

    companion object {
        fun parse(spec: String): TextStyle = TODO()
    }
}

/** Font weight — Valhalla enum packed as Int. */
enum class FontWeight(val value: Int) {
    THIN(100), EXTRA_LIGHT(200), LIGHT(300), NORMAL(400),
    MEDIUM(500), SEMI_BOLD(600), BOLD(700), EXTRA_BOLD(800), BLACK(900)
}

/** Modifier chain — valhalla inline class. */
@JvmInline
value class Modifier(val chain: List<ModifierOp> = emptyList()) {
    infix fun then(other: Modifier): Modifier = Modifier(chain + other.chain)

    infix fun fillMaxWidth(): Modifier = copy(chain + ModifierOp.FillMaxWidth)
    infix fun fillMaxHeight(): Modifier = copy(chain + ModifierOp.FillMaxHeight)
    infix fun padding(all: Dp): Modifier = copy(chain + ModifierOp.Padding(all))
    infix fun padding(horizontal: Dp, vertical: Dp): Modifier = copy(chain + ModifierOp.PaddingHV(horizontal, vertical))
    infix fun align(alignment: Alignment): Modifier = copy(chain + ModifierOp.Align(alignment))
    infix fun background(color: Color): Modifier = copy(chain + ModifierOp.Background(color))
    infix fun clickable(onClick: () -> Unit): Modifier = copy(chain + ModifierOp.Clickable(onClick))

    sealed class ModifierOp {
        object FillMaxWidth : ModifierOp()
        object FillMaxHeight : ModifierOp()
        data class Padding(val dp: Dp) : ModifierOp()
        data class PaddingHV(val horizontal: Dp, val vertical: Dp) : ModifierOp()
        data class Align(val alignment: Alignment) : ModifierOp()
        data class Background(val color: Color) : ModifierOp()
        data class Clickable(val action: () -> Unit) : ModifierOp()
    }
}

enum class Alignment { TopStart, TopCenter, TopEnd, CenterStart, Center, CenterEnd, BottomStart, BottomCenter, BottomEnd }

/** ConfixDoc extension for window composition. */
fun ConfixDoc.window(block: WindowConfixBuilder.() -> Unit): ComposeNode = {
    val cursor = FacetedCursor(Cursor.empty())
    val signals = FacetedSignal(Signal.Const(this))
    val builder = WindowConfixBuilder(this, cursor, signals)
    builder.block()
    builder.compose()
}