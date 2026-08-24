package borg.trikeshed.hermes

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.parse.json.JsonSupport
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * CLI that turns the python+js sleeve reporting into single-language TDD-red analysis.
 * Each facet is analyzed independently; output is `build/reports/hermes-single-language-port.json`.
 */
object HermesSingleLanguagePortCli {
    @JvmStatic
    fun main(args: Array<String>) {
        val opts = args.asList()
        fun v(flag: String) = opts.indexOf(flag).takeIf { it >= 0 }?.let { opts.getOrNull(it + 1) }
        val root = Path.of(v("--root") ?: "${System.getProperty("user.home")}/.hermes/hermes-agent").toAbsolutePath().normalize()
        val report = Path.of(v("--report") ?: "build/reports/hermes-single-language-port.json").toAbsolutePath().normalize()
        val pySleeve = Path.of(v("--py-sleeve") ?: "graalpy-sleeve/hermes").toAbsolutePath().normalize()
        val jsSleeve = Path.of(v("--js-sleeve") ?: "graaljs-sleeve").toAbsolutePath().normalize()

        HermesPythonPort().use { port ->
            val pyInv = if (Files.isDirectory(root)) port.inventory(root, pySleeve.takeIf { Files.isDirectory(it) }) else port.inventorySources(emptyMap())
            val pyReport = SingleLanguagePortAnalyzer.fromInventory(VmFacet.GRAAL_PYTHON, pyInv)

            val jsReport = SingleLanguagePortReport(
                facet = VmFacet.GRAAL_JS,
                modules = pyInv.modules.size,
                ready = 0,
                blockedNative = pyInv.modules.size,
                blockedTransitive = 0,
                significantGaps = run { val s = pyInv.significantGaps(); (0 until s.size).map { s[it] } },
                banlistEntries = pyInv.banlist.size,
                sleeveSpineCid = if (Files.isDirectory(jsSleeve)) "sleeve:js-present" else "sleeve:js-missing-RED",
                upstreamSpineCid = pyInv.upstreamSpineCid,
                ontologySpineCid = pyInv.ontology.cid.hex,
            )

            val summary = SingleLanguagePortAnalyzer.summarize(listOf(pyReport, jsReport))
            val payload = summary.toMutableMap().apply {
                put("reports", mapOf("python" to pyReport.toMap(), "js" to jsReport.toMap()))
                put("note", "TDD-red: each facet must be GREEN for single-language polyglot. pyqt/tkinter→braille, Native→host delegate, rest→Jules parts. graaljs-sleeve missing is intentional RED.")
            }
            report.parent?.let(Files::createDirectories)
            Files.writeString(report, JsonSupport.stringify(payload), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            println(JsonSupport.stringify(mapOf(
                "report" to report.toString(),
                "python" to pyReport.verdict,
                "js" to jsReport.verdict,
                "allGreen" to summary["allGreen"],
                "totalBlocked" to summary["totalBlocked"],
            )))
        }
    }
}
