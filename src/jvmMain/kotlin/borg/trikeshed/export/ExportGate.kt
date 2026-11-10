package borg.trikeshed.export

import borg.trikeshed.lcnc.RouteManifest
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import narchy.spacegraph.json
import java.io.File
import kotlin.system.exitProcess

/** A path a wire source matches requests against, and where. */
data class ServedPath(val file: String, val line: Int, val path: String)

/**
 * The route gate behind Gradle `exportGate` (run by `check`), from the repository root. Three accounts of the
 * daemon's surface must agree, and each disagreement fails the build:
 * 1. a [RouteManifest] line no wire source serves: a concrete segment no jvmMain/commonMain source names as a quoted
 *    or slash-led literal, or a `…` line with no `startsWith` or segment guard (the retired RouteParityGate's rule);
 * 2. a path a wire matches requests against that no manifest line covers ([CLAIMS]: module claims, `p == "/…"`
 *    comparisons on a local `p`/`path`, `"/…" ->` branches; another server's `exchange.requestURI.path` is not the daemon's);
 * 3. a hand-written spec operation no manifest line covers, or a components name two specs define differently.
 * The same run writes the export documents, so what the gate checked is what the daemon serves.
 */
object ExportGate {
    val CLAIMS: Series<Regex> = s_[
        Regex("""claim\(\s*id\s*,\s*"(/[^"]+)""""),
        Regex("""(?<![.\w])(?:p|path|reqPath)\s*==\s*"(/[^"]*)""""),
        Regex("""^\s*"(/[^"\s]+)"\s*->"""),
    ]

    fun sources(root: File): Series<File> = _l[File(root, "src/jvmMain/kotlin"), File(root, "src/commonMain/kotlin")]
        .filter { it.isDirectory }
        .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "RouteManifest.kt" }.toList() }
        .sortedBy { it.path }
        .toSeries()

    fun unserved(corpus: String, manifest: Map<String, List<RouteManifest.RouteEntry>> = RouteManifest.entries): Series<String> =
        manifest.values.flatten().filter { route ->
            val segments = route.path.trim('/').split('/')
            val concrete = segments.filter { it.isNotEmpty() && it != "…" && !(it.startsWith("{") && it.endsWith("}")) }
            val named = concrete.all { seg -> corpus.contains("\"$seg") || corpus.contains("/$seg") }
            if (!named) return@filter true
            if ("…" !in segments) return@filter false
            val first = concrete.firstOrNull().orEmpty()
            !(corpus.contains("startsWith(\"/$first") || corpus.contains("\"$first\""))
        }.map { it.toString() }.toSeries()

    fun served(files: Series<File>, root: File): Series<ServedPath> = files.view.flatMap { file ->
        val relative = file.relativeTo(root).path
        file.readLines().withIndex().flatMap { (index, text) ->
            CLAIMS.view.flatMap { pattern -> pattern.findAll(text).map { ServedPath(relative, index + 1, it.groupValues[1]) }.toList() }
        }
    }.distinct().toSeries()

    fun unlisted(served: Series<ServedPath>, manifest: Map<String, List<RouteManifest.RouteEntry>> = RouteManifest.entries): Series<ServedPath> {
        val lines = manifest.values.flatten().map { OpenApiExport.shape(it.path) }
        return served.filter { s -> lines.none { OpenApiExport.covers(it, OpenApiExport.shape(s.path)) } }
    }
}

/** `--root DIR` (repository), `--output DIR` (export documents), `--version V`, `--check` (exit 1 on any gap). */
fun main(args: Array<String>) {
    val options = args.toList()
    fun option(key: String): String? = options.indexOf(key).takeIf { it >= 0 }?.let { options.getOrNull(it + 1) }
    val root = File(option("--root") ?: ".").absoluteFile
    val files = ExportGate.sources(root)
    val corpus = files.view.joinToString("\n") { it.readText() }
    val unserved = ExportGate.unserved(corpus)
    val unlisted = ExportGate.unlisted(ExportGate.served(files, root))
    val specs = OpenApiExport.SPECS α { key -> key j File(root, "src/commonMain/resources/$key").readText() }
    val document = OpenApiExport.document(specs, version = option("--version") ?: "0")
    val specUnlisted = (document["x-trikeshed-unlisted"] as? List<*>).orEmpty().map { it.toString() }
    val conflicts = (document["x-trikeshed-conflicts"] as? List<*>).orEmpty().map { it.toString() }

    val routes = RouteManifest.entries.values.sumOf { it.size }
    val operations = (document["paths"] as? Map<*, *>).orEmpty().values.sumOf { item -> (item as? Map<*, *>).orEmpty().keys.count { it in OpenApiExport.HTTP_METHODS } }
    println("exportGate: ${files.size} sources, $routes manifest routes, $operations OpenAPI operations")
    fun report(title: String, gaps: List<String>) {
        if (gaps.isEmpty()) return
        println("  $title (${gaps.size}):")
        for (gap in gaps) println("    $gap")
    }
    report("manifest lines no wire source serves", unserved.view.toList())
    report("wire paths no manifest line lists", unlisted.view.map { "${it.path}  ${it.file}:${it.line}" })
    report("spec operations no manifest line covers", specUnlisted)
    report("components defined differently by two specs", conflicts)

    option("--output")?.let { dir ->
        val out = File(dir).apply { mkdirs() }
        File(out, "openapi.json").writeText(jsonOf(document))
        File(out, "client.ts").writeText(TypeScriptExport.client(document))
        File(out, "spacegraph.json").writeText(OpenApiExport.surface().json())
        println("exportGate: wrote openapi.json, client.ts, spacegraph.json to $out")
    }
    val gaps = unserved.size + unlisted.size + specUnlisted.size + conflicts.size
    if (gaps > 0 && "--check" in options) {
        println("exportGate: FAILED with $gaps gaps; settle each in RouteManifest, the wire, or the spec")
        exitProcess(1)
    }
}
