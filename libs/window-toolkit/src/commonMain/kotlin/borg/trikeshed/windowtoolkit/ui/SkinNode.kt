package borg.trikeshed.windowtoolkit.ui

import borg.trikeshed.windowtoolkit.math.Series
import borg.trikeshed.windowtoolkit.math.Vec2
import borg.trikeshed.windowtoolkit.math.Join
import borg.trikeshed.windowtoolkit.math.j
import borg.trikeshed.windowtoolkit.math.size
import borg.trikeshed.windowtoolkit.math.α

/**
 * A basic abstraction for a "Row" of metadata attributes configuring style.
 */
class StyleRow(val attributes: Map<String, Any>) {
    fun get(key: String): Any? = attributes[key]
}

/**
 * "Cursor" abstraction matching styles to geometric coordinates.
 */
typealias StyleCursor = Series<StyleRow>

/**
 * A "Skin" abstraction mapping a styling/presentation configuration
 * across a Cursor containing geometric dimensions, utilizing pure Join composition.
 */
data class SkinNode(val geometry: Series<Vec2>, val styles: StyleCursor) {
    init {
        require(geometry.size == styles.size) { "Geometry and styles must have matched bounds" }
    }
}

/**
 * Combine Geometry and Styles. Pure join composition.
 */
fun skin(geometry: Series<Vec2>, styles: StyleCursor): SkinNode = SkinNode(geometry, styles)

/**
 * Projects a single uniform style across the entire geometry datagrid.
 */
fun Series<Vec2>.applyUniformStyle(style: StyleRow): SkinNode {
    val size = this.size
    val uniformCursor: StyleCursor = size j { style }
    return skin(this, uniformCursor)
}
