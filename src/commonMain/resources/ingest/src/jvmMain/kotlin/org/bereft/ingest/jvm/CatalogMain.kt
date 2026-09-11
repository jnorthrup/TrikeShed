package org.bereft.ingest.jvm

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.size
import java.io.File

/**
 * CLI entrypoint: scan a directory and emit its media catalog to stdout (TSV)
 * or to a file. This is the tool that catalogs `../tika4all/`.
 *
 * Usage:
 *   CatalogMain <root-dir> [--out <file>] [--no-header]
 *
 * Run from the ingest project:
 *   ./gradlew :run --args="../tika4all --out catalog.tsv"
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("Usage: CatalogMain <root-dir> [--out <file>] [--no-header]")
        return
    }
    val root = File(args[0])
    if (!root.isDirectory) {
        System.err.println("Not a directory: ${root.absolutePath}")
        return
    }
    var out: File? = null
    var header = true
    var i = 1
    while (i < args.size) {
        when (args[i]) {
            "--out" -> { out = File(args[++i]); i++ }
            "--no-header" -> { header = false; i++ }
            else -> i++
        }
    }

    val catalog = DirectoryCatalog(root)
    val series = catalog.entries()
    val n = series.size
    // Aggregate counts by media type and facet — read the Series via index,
    // don't materialize a second copy.
    val byType = LinkedHashMap<String, Int>()
    val byFacet = LinkedHashMap<String, Int>()
    for (i in 0 until n) {
        val e = series.b(i)
        byType[e.mediaType] = (byType[e.mediaType] ?: 0) + 1
        byFacet[e.formatFacet] = (byFacet[e.formatFacet] ?: 0) + 1
    }
    val sortedTypes = byType.entries.sortedByDescending { it.value }
    val sortedFacets = byFacet.entries.sortedByDescending { it.value }
    val summary = buildString {
        append("# catalog of ${root.absolutePath}\n")
        append("# $n files scanned\n")
        append("# by media type:\n")
        for (entry in sortedTypes) append("#   ${entry.key}: ${entry.value}\n")
        append("# by facet:\n")
        for (entry in sortedFacets) append("#   ${entry.key}: ${entry.value}\n")
    }

    val full = summary + catalog.toTsv(header = header)
    if (out != null) {
        out.writeText(full)
        println("Wrote ${catalog.size} entries to ${out.absolutePath}")
    } else {
        print(full)
    }
}
