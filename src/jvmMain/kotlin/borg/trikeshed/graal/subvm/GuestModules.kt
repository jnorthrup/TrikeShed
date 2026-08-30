package borg.trikeshed.graal.subvm

import java.io.File
import java.net.URLClassLoader
import java.security.MessageDigest

/**
 * Guest module classpaths, mounted by the daemon — not by TrikeShed's build.
 *
 * Until now a `vm.*` lego could see a library exactly one way: the library had to be an
 * `implementation` dependency of `jvmMain`, because [InProcessIsolate] builds its context with
 * `allowHostClassLookup { bounds.hostTrusted }` — resolve from the HOST classpath, or not at all.
 * That is why `stanford-corenlp` (GPL v3, ~450MB of models) and Tika's parser graph sat on the
 * library's classpath with, in CoreNLP's case, not one compile-time reference anywhere in `src/`.
 * TrikeShed was paying Oroboros's runtime bill.
 *
 * A module is a directory of jars produced by the standalone `utils/subvm` build
 * (`./gradlew -p utils/subvm install`), which TrikeShed neither consumes nor is consumed by.
 * The daemon mounts `<module>/lib` into a [URLClassLoader] and hands it to the guest context
 * through `Context.Builder.hostClassLoader`. Nothing is added to this JVM's own classpath.
 *
 * PARENT IS THE PLATFORM LOADER, DELIBERATELY. Parenting to the app loader would let a guest
 * reach `borg.trikeshed.*` and every host dependency by name, which is the isolation this exists
 * to create. The platform loader supplies `java.*`/`javax.*` (the corenlp lego needs
 * `java.util.Properties`) and nothing of ours.
 */
object GuestModules {

    /** Override for a deployment whose modules do not live beside the checkout. */
    const val HOME_ENV = "TRIKESHED_SUBVM_HOME"

    private val loaders = java.util.concurrent.ConcurrentHashMap<String, URLClassLoader>()

    /**
     * The `utils/subvm` directory: `$TRIKESHED_SUBVM_HOME` when set, else the nearest ancestor of
     * the working directory that contains `utils/subvm`. Returns null when nothing is installed —
     * an absent module is a lego that cannot run, never a daemon that cannot boot.
     */
    fun root(): File? {
        System.getenv(HOME_ENV)?.takeIf { it.isNotBlank() }?.let { env ->
            return File(env).takeIf { it.isDirectory }
        }
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "utils/subvm")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        return null
    }

    fun libDir(module: String): File? = root()?.let { File(it, "$module/lib").takeIf { d -> d.isDirectory } }

    /**
     * A module's optional loose-class directory. A guest module has the SAME shape as a TrikeShed
     * deploy — `classes/` then `lib/` — so a module is mounted the way the daemon itself is
     * launched (see `bin/oroboros-daemon`: `build/live/classes` plus the jars in
     * `build/staging/lib` — no fat jar, no cache glob). One shape for both means a drag-and-drop
     * bundle needs no translation, and `classes/` first is what lets loose classes shadow a jar
     * while debugging.
     */
    fun classesDir(module: String): File? = root()?.let { File(it, "$module/classes").takeIf { d -> d.isDirectory } }

    fun manifestFile(module: String): File? = root()?.let { File(it, "$module/MANIFEST.tsv").takeIf { f -> f.isFile } }

    /** Jars of a module, sorted so a mounted classpath is deterministic. */
    fun jars(module: String): List<File> =
        libDir(module)?.listFiles { f -> f.isFile && f.name.endsWith(".jar") }?.sortedBy { it.name } ?: emptyList()

    /** The mount order the deploy uses: `classes/` first, then `lib/` sorted. */
    fun classpath(module: String): List<File> = listOfNotNull(classesDir(module)) + jars(module)

    fun isInstalled(module: String): Boolean = classpath(module).isNotEmpty()

    /** Installed module names, whether or not they carry a manifest. */
    fun installed(): List<String> =
        root()?.listFiles { f -> f.isDirectory }?.map { it.name }?.filter { isInstalled(it) }?.sorted() ?: emptyList()

    /**
     * The mounted classloader for a module, created once and cached. Null when the module is not
     * installed, so a caller can say "run `./gradlew -p utils/subvm install<Module>`" rather than
     * fail with a ClassNotFound from inside a guest script.
     */
    fun loaderFor(module: String): URLClassLoader? {
        if (!isInstalled(module)) return null
        return loaders.computeIfAbsent(module) {
            URLClassLoader(
                // File.toURI() appends the trailing slash for an existing directory, which is
                // what URLClassLoader needs to treat `classes/` as a directory rather than a jar.
                classpath(module).map { f -> f.toURI().toURL() }.toTypedArray(),
                ClassLoader.getPlatformClassLoader(),
            )
        }
    }

    /** Whether a binary name resolves in this module — the predicate `allowHostClassLookup` wants. */
    fun canResolve(loader: ClassLoader, binaryName: String): Boolean =
        runCatching { Class.forName(binaryName, false, loader) }.isSuccess

    data class ManifestEntry(val file: String, val size: Long, val sha256: String)

    /** Parsed MANIFEST.tsv rows (comment and header lines dropped). */
    fun manifest(module: String): List<ManifestEntry> {
        val f = manifestFile(module) ?: return emptyList()
        return f.readLines().mapNotNull { line ->
            if (line.startsWith("#") || line.isBlank()) return@mapNotNull null
            val cols = line.split('\t')
            if (cols.size != 3 || cols[0] == "file") return@mapNotNull null
            ManifestEntry(cols[0], cols[1].toLongOrNull() ?: return@mapNotNull null, cols[2])
        }
    }

    data class Verification(val module: String, val ok: Boolean, val checked: Int, val problems: List<String>)

    /**
     * Re-hash what is actually on disk against MANIFEST.tsv. A mounted classpath is code the daemon
     * executes; a jar that drifted from what was resolved should be visible, not silent. Missing
     * manifest is reported as unverifiable rather than as success.
     */
    fun verify(module: String): Verification {
        val entries = manifest(module)
        if (entries.isEmpty()) return Verification(module, false, 0, listOf("no MANIFEST.tsv for $module"))
        val byName = jars(module).associateBy { it.name }
        val problems = buildList {
            for (e in entries) {
                val f = byName[e.file]
                if (f == null) { add("missing: ${e.file}"); continue }
                if (f.length() != e.size) { add("size drift: ${e.file} ${f.length()} != ${e.size}"); continue }
                val actual = MessageDigest.getInstance("SHA-256").digest(f.readBytes())
                    .joinToString("") { b -> "%02x".format(b) }
                if (actual != e.sha256) add("sha256 drift: ${e.file}")
            }
            val extra = byName.keys - entries.map { it.file }.toSet()
            for (x in extra) add("unmanifested jar on the mounted classpath: $x")
        }
        return Verification(module, problems.isEmpty(), entries.size, problems)
    }
}
