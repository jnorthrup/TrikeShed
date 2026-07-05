package borg.trikeshed.windowtoolkit.dsl

import borg.trikeshed.windowtoolkit.context.WindowContextElement
import borg.trikeshed.windowtoolkit.confix.ConfixBlackboardWidget
import borg.trikeshed.windowtoolkit.ui.SkinNode
import borg.trikeshed.windowtoolkit.ui.StyleCursor
import borg.trikeshed.windowtoolkit.math.Series
import borg.trikeshed.windowtoolkit.math.Vec2
import borg.trikeshed.windowtoolkit.ui.skin

/**
 * Root builder for the Windowing toolkit DSL shell.
 */
class WindowShell(val context: WindowContextElement) {

    private val widgets = mutableListOf<ConfixBlackboardWidget>()
    private val layers = mutableListOf<SkinNode>()

    /**
     * Declares a Confix Blackboard widget layer.
     */
    fun confixDatagrid(configure: ConfixBlackboardWidget.() -> Unit) {
        val widget = ConfixBlackboardWidget()
        widget.configure()
        widgets.add(widget)
    }

    /**
     * Declares a geometric layout layer mapped with visual skins.
     */
    fun layoutLayer(geometry: Series<Vec2>, styles: StyleCursor) {
        layers.add(skin(geometry, styles))
    }

    suspend fun mount() {
        context.open()
        // Here we would typically attach widgets as subscribers to the context loop
        // for resizing, input, etc.
    }

    suspend fun unmount() {
        context.close()
        widgets.clear()
        layers.clear()
    }
}

/**
 * Entry point DSL function for the Window Toolkit.
 */
fun windowContext(block: WindowShell.() -> Unit): WindowShell {
    val context = WindowContextElement()
    val shell = WindowShell(context)
    shell.block()
    return shell
}
