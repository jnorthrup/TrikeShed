package borg.trikeshed.hermes

import borg.trikeshed.cas.LineSpine
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view
import borg.trikeshed.parse.json.JsonSupport
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

object HermesPythonPortCli {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.asList()
        val root = Path.of(value(options, "--root") ?: "${System.getProperty("user.home")}/.hermes/hermes-agent").toAbsolutePath().normalize()
        val report = Path.of(value(options, "--report") ?: "build/reports/hermes-python-port.json").toAbsolutePath().normalize()
        val queue = Path.of(value(options, "--queue") ?: "build/reports/hermes-graalpy-sleeve-queue.json").toAbsolutePath().normalize()
        val sleeve = Path.of(value(options, "--sleeve") ?: "graalpy-sleeve/hermes").toAbsolutePath().normalize()
        val entry = value(options, "--entry")
        val previous = previousOntology(report)
        Files.createDirectories(sleeve)

        HermesPythonPort().use { port ->
            val inventory = port.inventory(root, sleeve)
            val delta = inventory.ontology.deltaFrom(previous)
            var importFailure: Throwable? = null
            val imported = entry?.let {
                runCatching { port.importInVm(inventory, it).toString() }
                    .onFailure { failure -> importFailure = failure }
                    .getOrNull()
            }
            val payload = inventory.toMap().toMutableMap().apply {
                put("delta", delta.toMap())
                if (entry != null) put("vmImport", mapOf(
                    "entry" to entry,
                    "result" to imported,
                    "imported" to (importFailure == null),
                    "error" to importFailure?.message,
                    "nativeAccess" to false,
                ))
            }
            report.parent?.let(Files::createDirectories)
            Files.writeString(report, JsonSupport.stringify(payload), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            queue.parent?.let(Files::createDirectories)
            Files.writeString(queue, JsonSupport.stringify(mapOf(
                "upstreamSpineCid" to inventory.upstreamSpineCid,
                "sleeveSpineCid" to inventory.sleeveSpineCid,
                "ontologySpineCid" to inventory.ontology.cid.hex,
                "delta" to delta.toMap(),
                "significantGaps" to inventory.significantGaps().view.map { it.toMap() },
            )), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            println(JsonSupport.stringify(mapOf(
                "report" to report.toString(),
                "queue" to queue.toString(),
                "ontologySpineCid" to inventory.ontology.cid.hex,
                "added" to delta.added,
                "removed" to delta.removed,
                "proximity" to delta.proximity,
                "modules" to inventory.modules.size,
                "ready" to inventory.ready,
                "blockedNative" to inventory.blockedNative,
                "blockedTransitive" to inventory.blockedTransitive,
                "entry" to entry,
                "imported" to (entry != null && importFailure == null),
            )))
            importFailure?.let { throw IllegalStateException("Hermes VM import failed; triage report written to $report", it) }
        }
    }

    private fun previousOntology(report: Path): LineSpine? {
        if (!Files.isRegularFile(report)) return null
        return runCatching {
            val root = JsonSupport.parse(Files.readString(report)) as? Map<*, *> ?: return@runCatching null
            val ontology = root["ontology"] as? Map<*, *> ?: return@runCatching null
            val lines = (ontology["lines"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
            if (lines.isEmpty()) null else trimmedOntologyLineSpine(lines.toSeries())
        }.getOrNull()
    }

    private fun HermesOntologyDelta.toMap(): Map<String, Any?> = mapOf(
        "previousCid" to previousCid,
        "currentCid" to currentCid,
        "added" to added,
        "removed" to removed,
        "proximity" to proximity,
    )

    private fun value(args: List<String>, name: String): String? {
        val index = args.indexOf(name)
        require(index < 0 || index + 1 < args.size) { "$name requires a value" }
        return if (index < 0) null else args[index + 1]
    }
}