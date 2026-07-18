package borg.trikeshed.manimwm.manim




/**
 * RTS specific UI components using ManimWM abstractions.
 * These are intended to be drawn in the HUD layer (screen space).
 */

class SelectionBox(var startPoint: Point = pt(0.0, 0.0), var endPoint: Point = pt(0.0, 0.0)) : Rectangle(0.0, 0.0) {
    init {
        color = Color.GREEN
        // In a real implementation this would update the rectangle bounds based on start/end
    }

    fun updateSelection(newEnd: Point) {
        endPoint = newEnd
        // update bounds
    }
}

class Waypoint(val target: Point) : Dot(target, 0.1) {
    init {
        color = Color.BLUE
    }
}

class CommandNode(val text: String, position: Point) : Group() {
    init {
        val bg = Rectangle(2.0, 0.5)
        bg.color = Color.BLACK
        val label = Text(text, fontSize = 24.0)
        label.color = Color.WHITE
        add(bg, label)
        moveTo(position)
    }
}
