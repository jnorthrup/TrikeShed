package borg.trikeshed.graal.console

import borg.trikeshed.lib.*
import narchy.spacegraph.*
import narchy.spacegraph.graphics.spi.Rgba
import kotlin.math.ln

/**
 * The store on the SUMO ground. A row's content is typed by its file name ([classOf]): the smallest typing that gives
 * the Graal's territories homes on the ground; a name no rule knows, and every runtime row, is untyped. Each database's
 * documents of one class are a territory induced under that class (`[class, db]`; `[db]` in the untyped room), so a
 * territory's home depends on its class and its name alone.
 */
object StoreClasses {
    /** File name extension to the SUMO class its content is typed by. */
    val BY_EXTENSION: Map<String, String> = _m[
        "txt" j "Text", "md" j "Text",
        "html" j "Hypertext", "htm" j "Hypertext",
        "xml" j "XMLFile",
        "png" j "ImageFile", "jpg" j "ImageFile", "jpeg" j "ImageFile", "gif" j "ImageFile", "webp" j "ImageFile", "ico" j "ImageFile", "svg" j "ImageFile",
        "mp3" j "AudioFile", "wav" j "AudioFile",
        "mp4" j "MultimediaFile",
        "class" j "SoftwareFile", "jar" j "SoftwareFile", "war" j "SoftwareFile", "so" j "SoftwareFile", "dylib" j "SoftwareFile",
        "dll" j "SoftwareFile", "exe" j "SoftwareFile", "wasm" j "SoftwareFile", "o" j "SoftwareFile", "a" j "SoftwareFile",
        "kt" j "ComputerProgram", "kts" j "ComputerProgram", "java" j "ComputerProgram", "js" j "ComputerProgram", "ts" j "ComputerProgram",
        "py" j "ComputerProgram", "c" j "ComputerProgram", "h" j "ComputerProgram", "rs" j "ComputerProgram", "go" j "ComputerProgram",
        "json" j "DigitalData", "yaml" j "DigitalData", "yml" j "DigitalData", "toml" j "DigitalData", "csv" j "DigitalData",
        "pdf" j "Document",
        "zip" j "ComputerFile", "gz" j "ComputerFile", "bz2" j "ComputerFile", "xz" j "ComputerFile", "7z" j "ComputerFile",
    ]

    /** Topology's runtime categories: rows measured in the JVM, not stored content. */
    val RUNTIME = Topology.bit("blocks") or Topology.bit("live") or Topology.bit("allocation")

    /** The class of [row]'s content, or null. Git's paths and packs are files of the repository's own format. */
    fun classOf(row: TerrainRow): String? {
        if (row.bit and RUNTIME != 0) return null
        if (Topology.category(row.id, null) == "git") return "ComputerFile"
        return BY_EXTENSION[row.id.substringAfterLast('/').substringAfterLast('.', "").lowercase()]
    }

    /** A column for each database's documents of one class, standing in the territory induced for it, as tall as the log of their bytes. */
    fun ground(rows: List<TerrainRow>): Series<Stand> {
        val groups = LinkedHashMap<Pair<String?, String>, DoubleArray>()
        for (r in rows) {
            // An id that opens with a slash ("/memories/…", "/corpus/…") names its database after it.
            val db = r.id.trimStart('/').substringBefore('/').ifEmpty { r.id }
            val at = groups.getOrPut(classOf(r) to db) { DoubleArray(2) }
            at[0] += 1.0; at[1] += r.bytes
        }
        return groups.keys.sortedWith(compareBy<Pair<String?, String>> { it.first ?: "" }.thenBy { it.second }).map { k ->
            val (cls, db) = k; val g = groups.getValue(k)
            val path: Series<String> = if (cls == null) s_[db] else s_[cls, db]
            Stand("store/${cls ?: "untyped"}/$db", path, s_[db, (if (g[0] == 1.0) "1 document" else "${g[0].toLong()} documents") + " · " + Terrain.fmtBytes(g[1]), cls ?: "untyped"],
                ln(2 + g[1]) / ln(2.0) / 8, 1.0, STORE, noun = "store territories")
        }.toSeries()
    }

    val STORE = Rgba(150, 170, 200)
}
