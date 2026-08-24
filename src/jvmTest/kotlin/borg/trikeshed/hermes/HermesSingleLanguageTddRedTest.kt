package borg.trikeshed.hermes

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.pointcut.VmFacet
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * TDD-RED: single-language polyglot porting analysis.
 *
 * python + js sleeve reporting is now this. Each facet must be GREEN to run hermes
 * as a single-language polyglot isolate (no native). Until then these tests are RED
 * and drive Jules replacement parts (PyQt/tkinter→braille, NiceGUI→braille, etc.).
 *
 * To go GREEN: shrink blockedTotal to 0 per facet — sleeve the gap or delegate to host,
 * ship the rest to Jules. The analyzer `SingleLanguagePortReport` is the report.
 */
class HermesSingleLanguageTddRedTest {

    private fun syntheticPyInventory(blocked: Boolean): HermesPortInventory {
        val port = HermesPythonPort()
        return if (blocked) {
            port.inventorySources(mapOf(
                "app" to ("app.py" to "import PyQt5\nimport tkinter\nimport sqlite3"),
                "consumer" to ("consumer.py" to "import app"),
            ))
        } else {
            port.inventorySources(
                sources = mapOf("app" to ("app.py" to "from hermes.braille import Frame\nx=Frame('ok')")),
                sleeveSources = mapOf("hermes.braille" to ("hermes/braille/__init__.py" to "class Frame: pass")),
            )
        }
    }

    @Test
    fun pythonSingleLanguageIsRedUntilBrailleReplacesPyQtAndTk() {
        val inv = syntheticPyInventory(blocked = true)
        val report = SingleLanguagePortAnalyzer.fromInventory(VmFacet.GRAAL_PYTHON, inv)
        // TDD-RED: this is intentionally RED — PyQt/tkinter are blocked, braille not yet wired for app
        assertTrue(report.isRed, "expected RED for single-language python until PyQt/tkinter → braille. Got GREEN with ${report.ready}/${report.modules} ready")
        assertTrue(report.blockedTotal > 0)
        assertTrue(report.significantGaps.any { it.root in setOf("PyQt5", "tkinter", "sqlite3") },
            "expected PyQt5/tkinter/sqlite3 in gaps, got ${report.significantGaps.map { it.root }}")
        // replacement hint must mention braille
        assertTrue(report.toMap()["replacementHint"].toString().contains("braille", ignoreCase = true))
    }

    @Test
    fun pythonSingleLanguageGoesGreenWhenBrailleSleeveShadowsBlockedRoots() {
        val inv = syntheticPyInventory(blocked = false)
        val report = SingleLanguagePortAnalyzer.fromInventory(VmFacet.GRAAL_PYTHON, inv)
        // GREEN path: all blocked roots sleeved/replaced — drives the actual sleeve work
        assertTrue(report.isGreen, "expected GREEN when braille shadows blocked roots, got ${report.verdict} — blocked=${report.blockedTotal} gaps=${report.significantGaps.map { it.root }}")
        assertEquals(0, report.blockedTotal)
    }

    @Test
    fun jsSingleLanguageIsRedUntilSleeveExists() {
        // No graaljs-sleeve yet — JS facet is RED by construction. This drives creation of graaljs-sleeve.
        val pyInv = syntheticPyInventory(blocked = true)
        val gaps = pyInv.significantGaps()
        val gapList = (0 until gaps.size).map { gaps[it] }
        val jsReport = SingleLanguagePortReport(
            facet = VmFacet.GRAAL_JS,
            modules = pyInv.modules.size,
            ready = 0,
            blockedNative = pyInv.modules.size,
            blockedTransitive = 0,
            significantGaps = gapList,
            banlistEntries = pyInv.banlist.size,
            sleeveSpineCid = "sleeve:js-missing-RED",
            upstreamSpineCid = pyInv.upstreamSpineCid,
            ontologySpineCid = pyInv.ontology.cid.hex,
        )
        assertTrue(jsReport.isRed, "expected RED for GRAAL_JS until graaljs-sleeve exists")
        assertTrue(jsReport.sleeveSpineCid.contains("RED"))
        assertEquals(pyInv.modules.size, jsReport.blockedNative)
    }

    @Test
    fun singleLanguageSummarizerReportsAnyRed() {
        val red = syntheticPyInventory(true).let { SingleLanguagePortAnalyzer.fromInventory(VmFacet.GRAAL_PYTHON, it) }
        val greenInv = syntheticPyInventory(false)
        val green = SingleLanguagePortAnalyzer.fromInventory(VmFacet.GRAAL_PYTHON, greenInv)
        // we synthesize a green JS report
        val greenJs = SingleLanguagePortReport(VmFacet.GRAAL_JS, 1, 1, 0, 0, emptyList(), 0, "ok", "ok", "ok")
        val summaryRed = SingleLanguagePortAnalyzer.summarize(listOf(red, greenJs))
        val summaryGreen = SingleLanguagePortAnalyzer.summarize(listOf(green, greenJs))
        assertEquals(true, summaryRed["anyRed"])
        assertEquals(false, summaryRed["allGreen"])
        assertEquals(false, summaryGreen["anyRed"])
        assertEquals(true, summaryGreen["allGreen"])
    }
}
