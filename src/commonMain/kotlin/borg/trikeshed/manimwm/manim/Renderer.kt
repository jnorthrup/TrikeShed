package borg.trikeshed.manimwm.manim

import borg.trikeshed.manimwm.ManimWindowElement

/**
 * Generic rendering abstraction decoupled from concrete geometries (e.g. rectangles).
 * Dispatches Mobject drawing instructions to the platform via the ManimWindowElement.
 */
interface Renderer {
    /**
     * Renders a full Scene to the provided window.
     */
    fun render(scene: Scene, window: ManimWindowElement)

    /**
     * Clears the render surface.
     */
    fun clear(window: ManimWindowElement)
}

/**
 * A basic abstract renderer implementing the render separation from concrete geometries.
 * Now supports drawing World space (applying camera transform) then HUD space.
 */
abstract class AbstractRenderer : Renderer {
    override fun render(scene: Scene, window: ManimWindowElement) {
        clear(window)

        applyCameraTransform(scene.camera, window)
        for (mob in scene.worldMobjects) {
            dispatchRender(mob, window)
        }
        resetCameraTransform(window)

        for (mob in scene.hudMobjects) {
            dispatchRender(mob, window)
        }
    }

    protected abstract fun applyCameraTransform(camera: Camera, window: ManimWindowElement)
    protected abstract fun resetCameraTransform(window: ManimWindowElement)

    protected fun dispatchRender(mobject: Mobject, window: ManimWindowElement) {
        when(mobject) {
            is Line -> renderLine(mobject, window)
            is Circle -> renderCircle(mobject, window)
            is Rectangle -> renderRectangle(mobject, window)
            is Dot -> renderDot(mobject, window)
            is Tex -> renderTex(mobject, window)
            is Text -> renderText(mobject, window)
            is MathTex -> renderMathTex(mobject, window)
            is MarkupText -> renderMarkupText(mobject, window)
            is Group -> {
                // Groups just contain subobjects
            }
        }
        for (submob in mobject.subobjects) {
             dispatchRender(submob, window)
        }
    }

    protected abstract fun renderLine(line: Line, window: ManimWindowElement)
    protected abstract fun renderCircle(circle: Circle, window: ManimWindowElement)
    protected abstract fun renderRectangle(rectangle: Rectangle, window: ManimWindowElement)
    protected abstract fun renderDot(dot: Dot, window: ManimWindowElement)
    protected abstract fun renderTex(tex: Tex, window: ManimWindowElement)
    protected abstract fun renderText(text: Text, window: ManimWindowElement)
    protected abstract fun renderMathTex(mathTex: MathTex, window: ManimWindowElement)
    protected abstract fun renderMarkupText(markupText: MarkupText, window: ManimWindowElement)
}
