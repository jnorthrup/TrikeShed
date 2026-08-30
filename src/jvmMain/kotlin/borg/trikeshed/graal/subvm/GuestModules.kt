package borg.trikeshed.graal.subvm

import borg.trikeshed.vm.GuestModuleEntry
import borg.trikeshed.vm.GuestModuleLayout
import borg.trikeshed.vm.GuestModuleManifest
import borg.trikeshed.vm.GuestModuleVerification
import borg.trikeshed.vm.verifyGuestModule
import java.io.File
import java.net.URLClassLoader
import java.security.MessageDigest

/**
 * The LAST MILE of a guest module: find the directory, read the bytes, build the classloader.
 *
 * Every decision about what a guest module IS — the `classes/` then `lib/` layout, the manifest
 * format, what counts as a verified classpath — lives in [borg.trikeshed.vm.GuestModuleLayout],
 * [GuestModuleManifest] and [verifyGuestModule], in commonMain, because that shape is continuity.
 * This object only binds it to one platform. Oroboros assembles; it does not define.
 *
 * Why the concept had to exist at all: [InProcessIsolate] built its context with
 * `allowHostClassLookup { bounds.hostTrusted }`, so a guest resolved classes from the HOST
 * classpath or nowhere. That is why `stanford-corenlp` (GPL v3, ~472MB with models) was an
 * `implementation` dependency of `jvmMain` despite having no compile-time reference anywhere in
 * `src/` — TrikeShed was paying Oroboros's runtime bill. Modules are resolved by the standalone
 * `utils/subvm` build and mounted here instead.
 *
 * PARENT IS THE PLATFORM LOADER, DELIBERATELY. Parenting to the app loader would let a guest reach
 * `borg.trikeshed.*` and every host dependency by name, which is the isolation this exists to
 * create. The platform loader supplies `java.*`/`javax.*` — the corenlp lego needs
 * `java.util.Properties` — and nothing of ours.
 */
object GuestModules {

    /** Override for a deployment whose modules do not live beside the checkout. */
    const val HOME_ENV = "TRIKESHED_SUBVM_HOME"

    private val loaders = java.util.concurrent.ConcurrentHashMap<String, URLClassLoader>()

    /**
     * Mounted classpaths under a CCEK lifecycle.
     *
     * A mount is a resource: a [URLClassLoader] holding open jar handles, from which the daemon
     * executes code. The first version of this object kept them in a static map and closed none of
     * them, so a module remounted after a re-resolve leaked its predecessor and the daemon kept
     * executing from file handles nobody could name any more.
     *
     * Registering each mount with [borg.trikeshed.ccek.SupervisorJob] makes release structural:
     * [closeAll] closes every loader the supervisor holds, with no list here to keep exhaustive.
     * That is the same discount the daemon's own shutdown needed and did not have.
     */
    private val supervisor: borg.trikeshed.ccek.SupervisorJob =
        borg.trikeshed.ccek.RealSupervisorJob("guest-modules").also { it.open() }

    /** Module names currently mounted in this process. */
    fun mounted(): List<String> = loaders.keys.sorted()

    /** Lifecycle of the mount supervisor — OPEN until [closeAll]. */
    fun lifecycle(): borg.trikeshed.ccek.FanoutLifecycle = supervisor.lifecycle

    /**
     * Release every mounted classpath. After this the supervisor is CLOSED, so a later mount is
     * cancelled on arrival rather than silently retained — mounting during shutdown is a leak.
     */
    fun closeAll() {
        supervisor.drain()
        supervisor.close()
        loaders.clear()
    }

    /**
     * The `utils/subvm` directory: `$TRIKESHED_SUBVM_HOME` when set, else the nearest ancestor of
     * the working directory that contains `utils/subvm`. Null when nothing is installed — an absent
     * module is a lego that cannot run, never a daemon that cannot boot.
     */
    /** System-property form of [HOME_ENV]; wins over it, since a JVM can set it and env it cannot. */
    const val HOME_PROPERTY = "trikeshed.subvm.home"

    fun root(): File? {
        System.getProperty(HOME_PROPERTY)?.takeIf { it.isNotBlank() }?.let { prop ->
            return File(prop).takeIf { it.isDirectory }
        }
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

    fun moduleDir(module: String): File? = root()?.let { File(it, module).takeIf { d -> d.isDirectory } }

    fun libDir(module: String): File? =
        moduleDir(module)?.let { File(it, GuestModuleLayout.LIB).takeIf { d -> d.isDirectory } }

    fun classesDir(module: String): File? =
        moduleDir(module)?.let { File(it, GuestModuleLayout.CLASSES).takeIf { d -> d.isDirectory } }

    fun manifestFile(module: String): File? =
        moduleDir(module)?.let { File(it, GuestModuleLayout.MANIFEST).takeIf { f -> f.isFile } }

    /** Jars of a module, sorted so a mounted classpath is deterministic. */
    fun jars(module: String): List<File> =
        libDir(module)?.listFiles { f -> f.isFile && f.name.endsWith(".jar") }?.sortedBy { it.name } ?: emptyList()

    /** Classpath roots in the order [GuestModuleLayout] specifies: `classes/` first, then the jars. */
    fun classpath(module: String): List<File> = listOfNotNull(classesDir(module)) + jars(module)

    fun isInstalled(module: String): Boolean = classpath(module).isNotEmpty()

    /** Installed module names. */
    fun installed(): List<String> =
        root()?.listFiles { f -> f.isDirectory }?.map { it.name }?.filter { isInstalled(it) }?.sorted() ?: emptyList()

    /**
     * The mounted classloader for a module, created once and cached. Null when the module is not
     * installed, so a caller can name the install command rather than let a ClassNotFoundException
     * surface from inside a guest script where the cause is invisible.
     */
    fun loaderFor(module: String): URLClassLoader? {
        if (!isInstalled(module)) return null
        return loaders.computeIfAbsent(module) {
            // Verify BEFORE handing back a loader. A mounted classpath is code the daemon
            // executes, so "a drifted jar should be visible, not silent" has to be enforced at the
            // one place that makes it executable — otherwise verify() is a function nobody calls.
            // computeIfAbsent means this hashes the module's bytes once per process, not per eval.
            // The branch is on whether a manifest EXISTS, not on whether it has rows. A manifest
            // present but empty is not a debug drop — it is a manifest that accounts for nothing,
            // and every jar beside it is unaccounted for. Keying on `entries.isEmpty()` let exactly
            // that case mount unverified, which is what GuestModuleMountVerificationTest caught.
            if (manifestFile(module) == null) {
                // A hand-assembled module — `classes/` dropped in for debugging, per the deploy
                // convention — legitimately has no manifest. Allowed, but never silently: an
                // unpinned classpath the operator forgot about should still announce itself.
                println("[SUBVM] guest module '$module' has no ${GuestModuleLayout.MANIFEST} — mounting UNVERIFIED (${classpath(module).size} roots)")
            } else {
                val v = verify(module)
                check(v.ok) {
                    "guest module '$module' failed verification against ${GuestModuleLayout.MANIFEST} " +
                        "and was NOT mounted:\n  " + v.problems.joinToString("\n  ") +
                        "\nRe-resolve it: ./gradlew -p utils/subvm install${module.replaceFirstChar { it.uppercase() }}"
                }
            }
            URLClassLoader(
                // File.toURI() appends the trailing slash for an existing directory, which is what
                // URLClassLoader needs to treat `classes/` as a directory rather than a jar.
                classpath(module).map { f -> f.toURI().toURL() }.toTypedArray(),
                ClassLoader.getPlatformClassLoader(),
            ).also { loader ->
                // Under the supervisor from birth. A URLClassLoader holds open jar handles and is
                // the thing the daemon executes code from; releasing it must not depend on some
                // future shutdown path remembering this map exists.
                supervisor.hold(object : borg.trikeshed.ccek.CancelToken {
                    override fun cancel() {
                        runCatching { loader.close() }
                        loaders.remove(module, loader)
                    }
                })
            }
        }
    }

    /** Whether a binary name resolves in this module — the predicate `allowHostClassLookup` wants. */
    fun canResolve(loader: ClassLoader, binaryName: String): Boolean =
        runCatching { Class.forName(binaryName, false, loader) }.isSuccess

    /** The module's manifest as the common shape, or an empty one when absent. */
    fun manifest(module: String): GuestModuleManifest =
        manifestFile(module)?.let { GuestModuleManifest.parse(it.readText(), fallbackModule = module) }
            ?: GuestModuleManifest(module)

    /** Hash what is actually on the mounted classpath, so the pure comparator has something to compare. */
    fun observed(module: String): List<GuestModuleEntry> = jars(module).map { f ->
        GuestModuleEntry(
            f.name,
            f.length(),
            MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { b -> "%02x".format(b) },
        )
    }

    /** Read the bytes here; decide in commonMain. */
    fun verify(module: String): GuestModuleVerification = verifyGuestModule(manifest(module), observed(module))
}
