package borg.trikeshed.landscape

import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LandscapeNavigationTest {

    @Test
    fun maxZoomDividesBySubjectScale() {
        assertEquals(4.0, LandscapeNavigation.maxZoom())
        assertEquals(2.0, LandscapeNavigation.maxZoom(2.0))
        assertEquals(4e9, LandscapeNavigation.maxZoom(1e-9))
        assertEquals(4.0, LandscapeNavigation.maxZoom(0.0))
        assertEquals(4.0, LandscapeNavigation.maxZoom(Double.NaN))
        assertEquals(4.0, LandscapeNavigation.maxZoom(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun wheelFactorIsClampedExponential() {
        assertEquals(1.0, LandscapeNavigation.wheelFactor(Double.NaN))
        assertEquals(1.0, LandscapeNavigation.wheelFactor(Double.POSITIVE_INFINITY))
        assertEquals(exp(.35), LandscapeNavigation.wheelFactor(-1000.0), 1e-12)
        assertEquals(exp(-.35), LandscapeNavigation.wheelFactor(1000.0), 1e-12)
        assertEquals(exp(-100.0 * .0025), LandscapeNavigation.wheelFactor(100.0), 1e-12)
        assertEquals(exp(40.0 * .0025), LandscapeNavigation.wheelFactor(-40.0), 1e-12)
    }

    @Test
    fun zoomAtKeepsTheAnchorWorldPointStationary() {
        val camera = LandscapeCamera(100.0, 50.0, 1.0)
        val anchor = Pt(300.0, 200.0)
        val zoomed = LandscapeNavigation.zoomAt(camera, 2.0, anchor)
        assertEquals(2.0, zoomed.z)
        // the world point under the anchor must project to the same screen point
        val beforeX = (anchor.x - camera.x) / camera.z
        val afterX = (anchor.x - zoomed.x) / zoomed.z
        assertEquals(beforeX, afterX, 1e-9)
        val beforeY = (anchor.y - camera.y) / camera.z
        val afterY = (anchor.y - zoomed.y) / zoomed.z
        assertEquals(beforeY, afterY, 1e-9)
    }

    @Test
    fun zoomAtClampsAndTreatsNaNAsKeep() {
        val camera = LandscapeCamera(0.0, 0.0, 1.0)
        val anchor = Pt(0.0, 0.0)
        assertEquals(LandscapeNavigation.minZoom, LandscapeNavigation.zoomAt(camera, 1e-12, anchor).z)
        assertEquals(4.0, LandscapeNavigation.zoomAt(camera, 1e12, anchor).z)
        assertEquals(1.0, LandscapeNavigation.zoomAt(camera, Double.NaN, anchor).z)
        assertEquals(4e9, LandscapeNavigation.zoomAt(camera, 1e12, anchor, ceiling = 1e12).z)
    }

    @Test
    fun calloutPrefersTheOriginAndAvoidsThePointer() {
        val pointer = Pt(400.0, 300.0)
        val size = Extent(200.0, 100.0)
        val bounds = Rect(0.0, 0.0, 1000.0, 800.0)
        val placement = LandscapeNavigation.callout(pointer, size, bounds)
        assertEquals(Pt(pointer.x + 28.0, pointer.y + 18.0), placement!!.at)
        assertEquals(CalloutSide.RIGHT, placement.side)
    }

    @Test
    fun calloutClampsIntoBoundsAndRanksSubjectCoverage() {
        val pointer = Pt(5.0, 5.0)
        val size = Extent(200.0, 100.0)
        val bounds = Rect(0.0, 0.0, 1000.0, 800.0)
        val placement = LandscapeNavigation.callout(pointer, size, bounds)!!
        assertTrue(placement.at.x >= bounds.left && placement.at.x + size.width <= bounds.right)
        assertTrue(placement.at.y >= bounds.top && placement.at.y + size.height <= bounds.bottom)
        // every candidate overlaps the pointer exclusion zone near the corner, so null
        assertNull(LandscapeNavigation.callout(Pt(0.0, 0.0), size, Rect(0.0, 0.0, 20.0, 20.0)))
    }

    @Test
    fun calloutHonorsPreferredOriginAndRejectsOversize() {
        val pointer = Pt(400.0, 300.0)
        val size = Extent(200.0, 100.0)
        val bounds = Rect(0.0, 0.0, 1000.0, 800.0)
        val preferred = Pt(700.0, 100.0)
        val placement = LandscapeNavigation.callout(pointer, size, bounds, preferred = preferred)!!
        assertEquals(preferred, placement.at)
        assertNull(LandscapeNavigation.callout(pointer, Extent(2000.0, 100.0), bounds))
        assertNull(LandscapeNavigation.callout(pointer, Extent(200.0, 900.0), bounds))
    }

    @Test
    fun calloutTailIsClampedToTheCalloutBody() {
        val pointer = Pt(400.0, 300.0)
        val size = Extent(200.0, 100.0)
        val bounds = Rect(0.0, 0.0, 1000.0, 800.0)
        val placement = LandscapeNavigation.callout(pointer, size, bounds)!!
        assertTrue(placement.tail in 12.0..(size.height - 12.0))
    }

    @Test
    fun clipCurveKeepsInsidePiecesAndDropsOutsideOnes() {
        val box = ClipBox(left = 0.0, top = 0.0, right = 100.0, bottom = 100.0, pad = 0.0)
        val inside = Cubic(Pt(10.0, 10.0), Pt(20.0, 10.0), Pt(20.0, 20.0), Pt(30.0, 20.0))
        assertEquals(1, LandscapeNavigation.clipCurve(inside, box).size)
        val outside = Cubic(Pt(200.0, 200.0), Pt(300.0, 200.0), Pt(300.0, 300.0), Pt(400.0, 300.0))
        assertTrue(LandscapeNavigation.clipCurve(outside, box).isEmpty())
    }

    @Test
    fun clipCurveSplitsCrossingCurvesAndRespectsBudget() {
        val box = ClipBox(left = 0.0, top = 0.0, right = 1.0, bottom = 1.0, pad = 1e-3)
        // a long diagonal crossing the box needs subdivision, not one fat piece
        val crossing = Cubic(Pt(-10.0, -10.0), Pt(-5.0, -5.0), Pt(5.0, 5.0), Pt(10.0, 10.0))
        val pieces = LandscapeNavigation.clipCurve(crossing, box)
        assertTrue(pieces.size > 1)
        assertTrue(pieces.size <= 256)
        // non-finite control points yield nothing
        val broken = Cubic(Pt(0.0, 0.0), Pt(Double.NaN, 1.0), Pt(1.0, 1.0), Pt(2.0, 2.0))
        assertTrue(LandscapeNavigation.clipCurve(broken, box).isEmpty())
    }

    @Test
    fun splitIsExactAtTheMidpoint() {
        val c = Cubic(Pt(0.0, 0.0), Pt(0.0, 2.0), Pt(2.0, 2.0), Pt(2.0, 0.0))
        val (l, r) = c.split()
        assertEquals(c.p0, l.p0)
        assertEquals(c.p3, r.p3)
        assertEquals(l.p3, r.p0)
        assertEquals(Pt(1.0, 1.5), l.p3)
    }

    @Test
    fun pathDEmitsMoveOnlyOnDiscontinuity() {
        val a = Cubic(Pt(0.0, 0.0), Pt(1.0, 0.0), Pt(2.0, 0.0), Pt(3.0, 0.0))
        val b = Cubic(Pt(3.0, 0.0), Pt(4.0, 0.0), Pt(5.0, 0.0), Pt(6.0, 0.0))
        val c = Cubic(Pt(10.0, 10.0), Pt(11.0, 10.0), Pt(12.0, 10.0), Pt(13.0, 10.0))
        val continuous = LandscapeNavigation.pathD(listOf(a, b))
        assertEquals(1, continuous.count { it == 'M' })
        val broken = LandscapeNavigation.pathD(listOf(a, c))
        assertEquals(2, broken.count { it == 'M' })
        assertTrue(broken.startsWith("M 0.0 0.0 "))
        assertTrue(continuous.contains("C 1.0 0.0, 2.0 0.0, 3.0 0.0"))
    }

    @Test
    fun clipBoxPadsByThirtyTwoScreenPixels() {
        val camera = LandscapeCamera(64.0, 32.0, 2.0)
        val box = LandscapeNavigation.clipBox(800.0, 600.0, camera)
        assertEquals(16.0, box.pad)
        assertEquals((-64.0 - 32.0) / 2.0, box.left)
        assertEquals((800.0 - 64.0 + 32.0) / 2.0, box.right)
        assertEquals((-32.0 - 32.0) / 2.0, box.top)
        assertEquals((600.0 - 32.0 + 32.0) / 2.0, box.bottom)
    }

    @Test
    fun encodeDecodeRoundTripsBookmarks() {
        val camera = LandscapeCamera(12.5, -3.25, 2.0)
        val hash = LandscapeNavigation.encode(camera, "document/curation/abc")
        val decoded = LandscapeNavigation.decode(hash)
        assertEquals(camera, decoded!!.camera)
        assertEquals("document/curation/abc", decoded.focus)
    }

    @Test
    fun decodeRejectsMissingMalformedAndOutOfRange() {
        assertNull(LandscapeNavigation.decode("#x=1&y=2"))
        assertNull(LandscapeNavigation.decode("#x=1&y=2&z=abc"))
        assertNull(LandscapeNavigation.decode("#x=1&y=2&z=0.001"))
        assertNull(LandscapeNavigation.decode("#x=1&y=2&z=1e12"))
        assertNull(LandscapeNavigation.decode(""))
    }

    @Test
    fun formEncodingMatchesUrlSearchParams() {
        assertEquals("a+b%2Fc%3Dd", LandscapeNavigation.formEscape("a b/c=d"))
        assertEquals("a b/c=d", LandscapeNavigation.formUnescape("a+b%2Fc%3Dd"))
        assertEquals("%E2%82%AC", LandscapeNavigation.formEscape("€"))
        assertEquals("€", LandscapeNavigation.formUnescape("%E2%82%AC"))
        assertEquals("100% honest", LandscapeNavigation.formUnescape("100%25+honest"))
    }

    @Test
    fun focusKeysCarryProgramNodeAndObject() {
        assertEquals("program:demo", LandscapeNavigation.program("demo"))
        assertEquals("""node:["demo","n1"]""", LandscapeNavigation.node("demo", "n1"))
        assertEquals("object:sha256:00", LandscapeNavigation.`object`("sha256:00"))
        assertNotEquals(LandscapeNavigation.node("demo", "n1"), LandscapeNavigation.node("demo", "n2"))
    }
}
