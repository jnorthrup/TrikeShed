package org.bereft.ingest.jvm

import borg.trikeshed.lib.*
import borg.trikeshed.lib.Series
import org.bereft.ingest.Catalog
import org.bereft.ingest.MediaFormatInfo
import java.io.File

/**
 * Scan a directory tree into a lazy [Catalog] (a [Series] of [MediaFormatInfo]).
 *
 * PRELOAD compliance: the walk produces a backing array of [MediaFormatInfo]
 * and wraps it in a Series via `n j { i -> arr[i] }` — size paired with an
 * index oracle, no copy per access. The caller projects with `α` rather than
 * mapping `(0 until n)`.
 *
 * @param root the directory to scan
 * @param channel the detector; defaults to [JvmMediaFormatChannel]
 * @param skipDirs directory basenames to skip (e.g. VCS, caches)
 */
class DirectoryCatalog(
    private val root: File,
    private val channel: JvmMediaFormatChannel = JvmMediaFormatChannel(),
    private val skipDirs: Set<String> = setOf(".git", ".pijul", ".idea", ".aider.tags.cache.v4", ".claude", "build", "node_modules"),
) : Catalog {

    private val entries: Array<MediaFormatInfo> = scan()

    override fun entries(): Series<MediaFormatInfo> = entries.size j { i -> entries[i] }

    /** Tab-separated catalog line per entry, one row per file. */
    fun toTsv(header: Boolean = true): String = buildString {
        if (header) {
            append("path\tmedia_type\tfacet\tconfidence\tprojections\tsize_bytes\n")
        }
        for (e in entries) {
            append(e.toString()).append('\n')
        }
    }

    private fun scan(): Array<MediaFormatInfo> {
        if (!root.exists()) return emptyArray()
        val out = mutableListOf<MediaFormatInfo>()
        root.walkTopDown()
            .onEnter { dir -> dir.name !in skipDirs }
            .filter { it.isFile }
            .sortedBy { it.absolutePath }
            .forEach { f ->
                runCatching { channel.detect(f.absolutePath) }
                    .onSuccess { out.add(it) }
            }
        return out.toTypedArray()
    }
}

private infix fun Int.j(getter: (Int) -> MediaFormatInfo): Series<MediaFormatInfo> =
    object : borg.trikeshed.lib.Join<Int, (Int) -> MediaFormatInfo> {
        override val a: Int get() = this@j
        override val b: (Int) -> MediaFormatInfo get() = getter
    }
