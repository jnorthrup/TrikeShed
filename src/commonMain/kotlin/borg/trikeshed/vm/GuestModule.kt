package borg.trikeshed.vm

/**
 * The SHAPE of a guest module, as Kotlin that every target can hold.
 *
 * A guest module is a mountable classpath: the unit that lets a `vm.*` lego call a real library
 * without that library being a dependency of TrikeShed. The daemon is the last mile — it turns this
 * shape into a classloader on one platform — but the shape itself is continuity, so it lives here
 * rather than in the assembler. Oroboros mounts modules; it does not get to define what one is.
 *
 * THE LAYOUT IS THE DEPLOY LAYOUT. A module directory is `classes/` then the jars in `lib/`, which
 * is exactly how a TrikeShed deploy is launched (`bin/oroboros-daemon`: `build/live/classes` plus
 * `build/staging/lib`, no fat jar, no cache glob). One shape for both means a module needs no
 * translation to be mounted, a deploy needs none to be published as a module, and `classes/` first
 * is what lets loose classes shadow a jar while debugging.
 *
 * Everything in this file is pure: layout names, manifest parse/render, and the comparison that
 * decides whether what is on disk is what was resolved. Reading bytes and building a classloader
 * are platform concerns and stay platform-side.
 */
object GuestModuleLayout {
    /** Loose classes, mounted FIRST so they shadow the jars — the debugging seam. */
    const val CLASSES = "classes"

    /** Resolved jars, mounted after `classes/`, in a stable order. */
    const val LIB = "lib"

    /** The reproducibility record beside them. */
    const val MANIFEST = "MANIFEST.tsv"

    /**
     * Mount order for a module's classpath roots, given what exists. Sorting the jars is not
     * cosmetic: a classpath whose order varies is a classpath whose shadowing varies.
     */
    fun mountOrder(hasClasses: Boolean, jars: List<String>): List<String> =
        (if (hasClasses) listOf(CLASSES) else emptyList()) + jars.sorted().map { "$LIB/$it" }
}

/** One resolved artifact: the name on disk, its length, and what it hashed to when resolved. */
data class GuestModuleEntry(val file: String, val size: Long, val sha256: String)

/**
 * A module's MANIFEST.tsv, parsed. [declared] are the coordinates that were asked for; [entries]
 * are what resolving them actually produced, which is nearly always a longer list — that gap is
 * the transitive closure, and recording both is what makes a mounted classpath auditable.
 */
data class GuestModuleManifest(
    val module: String,
    val declared: List<String> = emptyList(),
    val entries: List<GuestModuleEntry> = emptyList(),
) {
    val totalBytes: Long get() = entries.sumOf { it.size }

    fun render(): String = buildString {
        appendLine("# guest module\t$module")
        declared.forEach { appendLine("# declared\t$it") }
        appendLine("# resolved\t${entries.size} jars\t$totalBytes bytes")
        appendLine("file\tsize\tsha256")
        entries.forEach { appendLine("${it.file}\t${it.size}\t${it.sha256}") }
    }

    companion object {
        /**
         * Parse the TSV the `utils/subvm` build writes. Comment lines carry the declared
         * coordinates; the body is one row per artifact. A row that will not parse is skipped
         * rather than fatal — a manifest is evidence, and a torn line should cost its own row,
         * not the audit.
         */
        fun parse(tsv: String, fallbackModule: String = ""): GuestModuleManifest {
            var module = fallbackModule
            val declared = mutableListOf<String>()
            val entries = mutableListOf<GuestModuleEntry>()
            for (raw in tsv.lineSequence()) {
                val line = raw.trimEnd('\r')
                if (line.isBlank()) continue
                if (line.startsWith("#")) {
                    val cols = line.removePrefix("#").trim().split('\t')
                    if (cols.size >= 2) when (cols[0].trim()) {
                        "guest module" -> module = cols[1].trim()
                        "declared" -> declared += cols[1].trim()
                    }
                    continue
                }
                val cols = line.split('\t')
                if (cols.size != 3 || cols[0] == "file") continue
                val size = cols[1].toLongOrNull() ?: continue
                entries += GuestModuleEntry(cols[0], size, cols[2])
            }
            return GuestModuleManifest(module, declared, entries)
        }
    }
}

/** What an audit of a mounted classpath found. [checked] is how many manifest rows were compared. */
data class GuestModuleVerification(
    val module: String,
    val ok: Boolean,
    val checked: Int,
    val problems: List<String> = emptyList(),
)

/**
 * Compare what is mounted against what was resolved. Pure: the caller supplies [observed] having
 * already hashed the bytes, so this same decision runs on any target.
 *
 * A mounted classpath is code the daemon executes. Three things are reported and none are silent:
 * a manifest row with nothing behind it, a row whose bytes moved, and — the one an
 * entries-only loop misses — a jar sitting on the classpath that no manifest row accounts for.
 * An absent manifest is UNVERIFIABLE, never "fine": nothing to compare is not the same as nothing
 * wrong, and reporting it as success is how an unpinned classpath passes an audit.
 */
fun verifyGuestModule(
    manifest: GuestModuleManifest,
    observed: List<GuestModuleEntry>,
): GuestModuleVerification {
    if (manifest.entries.isEmpty()) {
        return GuestModuleVerification(
            manifest.module, ok = false, checked = 0,
            problems = listOf("no ${GuestModuleLayout.MANIFEST} entries for '${manifest.module}' — unverifiable, not verified"),
        )
    }
    val byName = observed.associateBy { it.file }
    val problems = buildList {
        for (e in manifest.entries) {
            val got = byName[e.file]
            when {
                got == null -> add("missing: ${e.file}")
                got.size != e.size -> add("size drift: ${e.file} ${got.size} != ${e.size}")
                got.sha256 != e.sha256 -> add("sha256 drift: ${e.file}")
            }
        }
        val accounted = manifest.entries.mapTo(HashSet()) { it.file }
        for (extra in byName.keys.filter { it !in accounted }.sorted()) {
            add("unmanifested artifact on the mounted classpath: $extra")
        }
    }
    return GuestModuleVerification(manifest.module, problems.isEmpty(), manifest.entries.size, problems)
}
