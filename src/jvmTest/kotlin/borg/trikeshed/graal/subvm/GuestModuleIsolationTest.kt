package borg.trikeshed.graal.subvm

import borg.trikeshed.pointcut.VmFacet
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * The isolation half of the guest-module claim, which until now was asserted only in a KDoc and a
 * commit message: that mounting a module NARROWS `allowHostClassLookup` from "anything on the host
 * classpath" to "resolvable in this module".
 *
 * `CoreNlpLegoExecutionTest` proves the positive direction — a mounted module's classes resolve.
 * It cannot prove the negative, and the negative is the half that matters: a JVM-facet guest runs
 * at `hostTrusted`, so without narrowing it could reach every class this daemon links, including
 * `borg.trikeshed.*`. These tests exercise the predicate that governs that, directly.
 */
class GuestModuleIsolationTest {

    private val module = "corenlp"

    private fun requireModule() {
        assertTrue(
            GuestModules.isInstalled(module),
            "guest module '$module' is not installed — run: ./gradlew -p utils/subvm installCorenlp",
        )
    }

    @Test
    fun aMountedModuleResolvesItsOwnClassesAndTheJdk() {
        requireModule()
        val loader = GuestModules.loaderFor(module) ?: error("no loader for $module")
        // What the corenlp lego's script actually names.
        assertTrue(GuestModules.canResolve(loader, "edu.stanford.nlp.pipeline.StanfordCoreNLP"))
        assertTrue(GuestModules.canResolve(loader, "edu.stanford.nlp.pipeline.CoreDocument"))
        // The JDK, via the platform parent — the lego needs java.util.Properties.
        assertTrue(GuestModules.canResolve(loader, "java.util.Properties"))
        assertTrue(GuestModules.canResolve(loader, "java.lang.String"))
    }

    @Test
    fun aMountedModuleCannotNameTheHostsOwnClasses() {
        requireModule()
        val loader = GuestModules.loaderFor(module) ?: error("no loader for $module")
        // These are all on THIS JVM's classpath right now — the test is running with them loaded.
        // Parenting the module loader to the platform loader instead of the app loader is what
        // makes them unnameable. If this ever passes, the guest can reach the daemon's own
        // internals by name at OWN trust, and the narrowing claim is false.
        for (hostClass in listOf(
            "borg.trikeshed.graal.subvm.GuestModules",
            "borg.trikeshed.daemon.OroborosDaemon",
            "borg.trikeshed.lcnc.SubVmLegos",
            "borg.trikeshed.vm.GuestModuleLayout",
        )) {
            assertFalse(
                GuestModules.canResolve(loader, hostClass),
                "guest module '$module' can name a host class: $hostClass",
            )
        }
    }

    @Test
    fun theHostItselfCanNameThoseClasses() {
        // Control. Without this, the test above would also pass if canResolve simply never
        // resolved anything — a broken predicate would read as perfect isolation.
        val app = this::class.java.classLoader
        assertTrue(GuestModules.canResolve(app, "borg.trikeshed.graal.subvm.GuestModules"))
        assertTrue(GuestModules.canResolve(app, "borg.trikeshed.daemon.OroborosDaemon"))
    }

    @Test
    fun anUninstalledModuleMountsNothingRatherThanFallingBackToTheHost() {
        // A silent fallback to the host classpath would be the worst outcome: the lego would work,
        // the isolation would be gone, and nothing would say so.
        assertFalse(GuestModules.isInstalled("definitely-not-installed"))
        kotlin.test.assertNull(GuestModules.loaderFor("definitely-not-installed"))
    }

    @Test
    fun verifyReportsAnIntactMountAsIntact() {
        requireModule()
        // GuestModules.verify() hashes real bytes against the real MANIFEST.tsv. It had no caller
        // and no jvm-side test; the pure comparator was covered in commonTest with synthetic rows.
        val v = GuestModules.verify(module)
        assertTrue(v.ok, "installed module reported as drifted: ${v.problems}")
        assertTrue(v.checked > 0, "verify checked nothing")
    }

    @Test
    fun theIsolateBuildsWithAModuleMountedAtJvmFacet() {
        requireModule()
        // Construction is where hostClassLoader/allowHostClassLookup are wired; a throw here means
        // the seam is broken regardless of what the predicate says.
        val iso = InProcessIsolate(id = "isolation-probe", facet = VmFacet.JVM, guestModule = module)
        assertTrue(iso.bounds.hostTrusted, "JVM facet should still be hostTrusted")
    }
}

/**
 * Mount-time verification. Before this, `GuestModules.verify()` existed, was documented as making a
 * drifted jar "visible, not silent", and had no production caller at all — the check was wired to
 * nothing. It now runs inside `loaderFor`, at the one point that makes a classpath executable.
 */
class GuestModuleMountVerificationTest {

    private fun withHome(block: (java.io.File) -> Unit) {
        val home = java.nio.file.Files.createTempDirectory("subvm-verify-").toFile()
        val prior = System.getProperty(GuestModules.HOME_PROPERTY)
        System.setProperty(GuestModules.HOME_PROPERTY, home.absolutePath)
        try { block(home) } finally {
            if (prior == null) System.clearProperty(GuestModules.HOME_PROPERTY)
            else System.setProperty(GuestModules.HOME_PROPERTY, prior)
            home.deleteRecursively()
        }
    }

    private fun module(home: java.io.File, name: String, manifest: String?): java.io.File {
        val lib = java.io.File(home, "$name/lib").apply { mkdirs() }
        java.io.File(lib, "payload.jar").writeBytes(byteArrayOf(1, 2, 3, 4))
        manifest?.let { java.io.File(home, "$name/${borg.trikeshed.vm.GuestModuleLayout.MANIFEST}").writeText(it) }
        return java.io.File(home, name)
    }

    @Test
    fun aDriftedJarIsRefusedAtMountRatherThanExecuted() = withHome { home ->
        // Manifest claims a sha the bytes do not have.
        module(home, "drifted", "file\tsize\tsha256\npayload.jar\t4\tdeadbeef\n")
        val err = runCatching { GuestModules.loaderFor("drifted") }.exceptionOrNull()
        assertTrue(err is IllegalStateException, "expected refusal, got $err")
        val m = err.message.orEmpty()
        assertTrue("failed verification" in m, m)
        assertTrue("sha256 drift" in m, m)
        assertTrue("utils/subvm" in m, "the message must say how to fix it: $m")
    }

    @Test
    fun anUnmanifestedJarOnTheClasspathIsAlsoRefused() = withHome { home ->
        // The case a loop over manifest rows cannot see: a jar nothing accounts for.
        val dir = module(home, "smuggled", "file\tsize\tsha256\n")
        java.io.File(dir, "lib/extra.jar").writeBytes(byteArrayOf(9))
        val err = runCatching { GuestModules.loaderFor("smuggled") }.exceptionOrNull()
        assertTrue(err is IllegalStateException, "expected refusal, got $err")
    }

    @Test
    fun aModuleWithNoManifestStillMountsBecauseDebugDropsAreLegitimate() = withHome { home ->
        // `classes/` dropped in by hand while debugging has no manifest and must still work —
        // but unverified, and it says so on stdout rather than passing as checked.
        module(home, "handmade", manifest = null)
        val loader = GuestModules.loaderFor("handmade")
        kotlin.test.assertNotNull(loader, "an unmanifested module must still mount")
    }

    @Test
    fun anIntactModuleMountsNormally() = withHome { home ->
        val sha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(byteArrayOf(1, 2, 3, 4)).joinToString("") { "%02x".format(it) }
        module(home, "intact", "file\tsize\tsha256\npayload.jar\t4\t$sha\n")
        kotlin.test.assertNotNull(GuestModules.loaderFor("intact"))
    }
}
