package narchy.spacegraph

import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import narchy.spacegraph.graphics.spi.*
import kotlin.math.*
import kotlin.test.*

class SpatialViewTest {
    private val viewport = Viewport(1000, 700)
    private fun measured(id: String, x: Double, y: Double, w: Double, h: Double, scale: Double = 1.0) =
        MeasuredNode(id, Rect(x, y, w, h), emptySeriesOf(), scale = scale)

    @Test fun recursionCompoundsDepthAndThicknessWithInheritedScale() {
        val program = LcncProgram("recursive", s_[LcncNode("a", "scope", children = s_[
            LcncNode("b", "scope", children = s_[LcncNode("c", "scope", children = s_[LcncNode("d", "text.value")])])])], emptySeriesOf())
        val scene = LcncExtrusion.project(program, s_[
            measured("a", 0.0, 0.0, 800.0, 800.0), measured("b", 100.0, 100.0, 400.0, 400.0, .5),
            measured("c", 150.0, 150.0, 200.0, 200.0, .25), measured("d", 175.0, 175.0, 100.0, 100.0, .125)])
        assertEquals(listOf(0.0, 55.0, 82.5, 96.25), scene.nodes.view.map { it.bounds.min.z })
        assertEquals(listOf(12.0, 6.0, 3.0, 3.5), scene.nodes.view.map { it.size.z })
        assertEquals(listOf("b", "c", "d"), scene.subtree("b").view.map { it.id })
        assertEquals("c", scene.branches["b"][0].id)
        assertEquals(4, scene.nodes[2].solids.size)
        assertEquals(1.0, scene.nodes[2].solids[0].size.x)
    }

    @Test fun hollowScopeIsNotPickedThroughItsOpening() {
        val scene = LcncExtrusion.project(LcncProgram("ring", s_[LcncNode("ring", "scope")], emptySeriesOf()),
            s_[measured("ring", 0.0, 0.0, 200.0, 200.0)])
        val view = SpatialView(viewport); view.update(scene, scene.camera(viewport), true); view.front()
        assertNull(view.pick(500.0, 350.0))
        val rim = view.camera.project(Vec3(2.0, -100.0, 12.0), viewport)!!
        assertEquals("ring", view.pick(rim.x, rim.y)?.a)
        assertTrue(FrameTriangles.vertices(view.frame()).size > 0)
        assertEquals(0, SvgGraphicsProvider.encode(view.frame()).b.refusals.size)
    }

    @Test fun focusHasNoWorldSizeFloorAndProjectionRoundTripsAfterNavigation() {
        val scene = LcncExtrusion.project(LcncProgram("tiny", s_[LcncNode("tiny", "text.value")], emptySeriesOf()),
            s_[measured("tiny", 0.0, 0.0, .00022, .00009, .000001)])
        val view = SpatialView(viewport); view.update(scene, scene.camera(viewport), true); view.focus("tiny")
        assertTrue((view.camera.position - view.camera.center).length < .001)
        view.orbit(100.0, 40.0); view.pan(20.0, -10.0); view.zoom(2.0)
        val p = scene.nodes[0].position
        val screen = view.camera.project(p, viewport)!!
        val back = view.point(screen.x, screen.y, p.z)!!
        assertTrue((back - p).length < 1e-12)
        assertEquals("tiny", view.pick(screen.x, screen.y)?.a)
        val vertices = FrameTriangles.vertices(view.frame())
        assertTrue(vertices.size > 0)
        assertTrue(vertices.view.all { it.a.x.isFinite() && it.a.y.isFinite() })
    }

    @Test fun cameraAndProviderProjectionNeverMutateTheProgram() {
        val program = LcncProgram("keep", s_[LcncNode("value", "text.value", mapOf("value" to "unchanged"))], emptySeriesOf())
        val before = LcncProgramConfix.toJson(program)
        val scene = LcncExtrusion.project(program)
        assertEquals("unchanged", scene.nodes[0].details[0].b)
        val view = SpatialView(viewport); view.update(scene, scene.camera(viewport), true)
        view.focus("value"); view.orbit(70.0, 20.0); view.zoom(.5); view.pan(11.0, 18.0)
        view.focus("value")
        val frame = view.frame()
        assertTrue(FrameTriangles.vertices(frame).size > 0)
        assertTrue(SvgGraphicsProvider.encode(frame).a.contains("unchanged"))
        assertEquals(before, LcncProgramConfix.toJson(program))
    }
}
