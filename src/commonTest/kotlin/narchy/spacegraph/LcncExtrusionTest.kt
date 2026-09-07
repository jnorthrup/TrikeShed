package narchy.spacegraph

import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import narchy.spacegraph.graphics.spi.SvgGraphicsProvider
import kotlin.test.*

class LcncExtrusionTest {
    private val child = LcncNode("input", "scope.in", mapOf("name" to "message", "kind" to "text"))
    private val program = LcncProgram("geometry", s_[
        LcncNode("source", "text.value", mapOf("value" to "keep this"), -40.0, 10.0),
        LcncNode("scope", "scope", children = s_[child]),
    ], s_[LcncWire("source", "value", "scope", "message")])
    private fun measurement(id: String, x: Double, y: Double, width: Double, height: Double, vararg ports: MeasuredPort) =
        MeasuredNode(id, Rect(x, y, width, height), ports.toSeries())
    private val measured get() = s_[
        measurement("source", -120.0, 12.0, 240.0, 90.0, MeasuredPort("value", false, Vec3(120.0, 50.0))),
        measurement("scope", 200.0, 20.0, 600.0, 400.0, MeasuredPort("message", true, Vec3(200.0, 80.0))),
        measurement("input", 230.0, 120.0, 190.0, 80.0, MeasuredPort("value", false, Vec3(420.0, 160.0))),
    ]
    @Test fun measuredBoundsBecomeActualSolidsAndPorts(){
        val scene=LcncExtrusion.project(program, measured)
        assertEquals(Vec3(0.0,-57.0,14.0),scene.nodes[0].position)
        assertEquals(Vec3(240.0,90.0,28.0),scene.nodes[0].size)
        assertTrue(scene.nodes.view.all { it.measured })
        assertEquals(Vec3(120.0,-50.0,31.0),scene.cables[0].points[0])
        assertEquals(Vec3(200.0,-80.0,15.0),scene.cables[0].points[3])
        assertEquals("text",scene.cables[0].to.kind)
    }
    @Test fun containmentExtrudesWithoutChangingTheSource(){
        val before=LcncProgramConfix.toJson(program)
        val scene=LcncExtrusion.project(program, measured, 180.0)
        assertEquals(194.0,scene.nodes[2].position.z)
        assertEquals("scope",scene.nodes[2].parent)
        assertEquals(before,LcncProgramConfix.toJson(program))
    }
    @Test fun collapsedAncestorsDoNotLeaveFloatingChildren(){
        val p=program.copy(nodes=s_[program.nodes[0],program.nodes[1].copy(collapsed=true)])
        assertEquals(listOf("source","scope"),LcncExtrusion.project(p,measured).nodes.view.map { it.id })
    }
    @Test fun allProvidersCanUseTheSameSceneAndFramedCamera(){
        val scene=LcncExtrusion.project(program,measured)
        for(viewport in listOf(Viewport(1000,700),Viewport(390,600))){
            val camera=scene.camera(viewport)
            for(n in scene.nodes.view)for(p in n.corners.view)assertTrue(viewport.bounds.contains(camera.project(p,viewport)!!))
            val frame=LcncExtrusion.frame(scene,camera,viewport)
            assertTrue(frame.items.view.filterIsInstance<narchy.spacegraph.graphics.spi.DrawItem.Text>().all { it.clip != null })
            assertTrue(frame.items.size>scene.nodes.view.sumOf { it.solids.size })
            val svg=SvgGraphicsProvider.encode(frame)
            assertTrue(svg.a.contains("data-entity=\"source\""))
            assertEquals(0,svg.b.refusals.size)
            assertEquals("right-handed-y-up-z-extrusion",LcncExtrusion.value(scene,camera)["coordinateSystem"])
        }
    }
    @Test fun invalidGeometryIsRefused(){
        assertFailsWith<IllegalArgumentException>{LcncExtrusion.project(program,s_[measurement("foreign",0.0,0.0,1.0,1.0)])}
        assertFailsWith<IllegalArgumentException>{LcncExtrusion.project(program,measured,Double.NaN)}
        val row=mapOf("id" to "source","x" to 0,"y" to 0,"width" to -2,"height" to 3)
        assertFailsWith<IllegalArgumentException>{LcncExtrusion.measurements(listOf(row))}
        assertFailsWith<IllegalArgumentException>{LcncExtrusion.measurements(listOf(row+mapOf("width" to 2),row+mapOf("width" to 2)))}
    }
    @Test fun missingMeasurementAndEndpointsAreExplicit(){
        val scene=LcncExtrusion.project(program)
        assertFalse(scene.nodes[0].measured)
        val missing=program.copy(wires=s_[LcncWire("source","missing","scope","message")])
        assertEquals(1,LcncExtrusion.project(missing,measured).issues.size)
        assertEquals(0,LcncExtrusion.project(missing,measured).cables.size)
    }
}
