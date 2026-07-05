package borg.trikeshed.forge.codebase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class PanamaInductionTest {

    @Test
    fun threeServiceEnsembleIsPresent() {
        val services = PanamaInduction.services
        assertEquals(3, services.size, "panama ensemble must have exactly three services")
        assertEquals(listOf("gswormk", "cache-tier", "columnar"), services.map { it.name })
    }

    @Test
    fun eachServiceHasRealManifestAndBuildCommand() {
        PanamaInduction.services.forEach { service ->
            assertTrue(service.manifestPath.contains("panama"), "${service.name} manifest must point at panama")
            assertTrue(service.buildCommand.isNotBlank(), "${service.name} must have a build command")
            assertTrue(service.classpath.isNotEmpty(), "${service.name} must carry real classpath entries")
            assertTrue(service.entryPoints.isNotEmpty(), "${service.name} must declare entry points")
        }
    }

    @Test
    fun rolesMatchTheIoCachingTiers() {
        val byName = PanamaInduction.services.associateBy { it.name }
        assertEquals(ServiceRole.BRAIN, byName["gswormk"]?.role, "gswormk is the hot/realtime brain")
        assertEquals(ServiceRole.CACHE_TIER, byName["cache-tier"]?.role, "cache-tier is the warm distributed cache")
        assertEquals(ServiceRole.ARCHIVE, byName["columnar"]?.role, "columnar is the cold ISAM archive")
    }

    @Test
    fun languagesSpanThePolyglotDemand() {
        val langs = PanamaInduction.services.map { it.language }.toSet()
        assertTrue(CodebaseLanguage.TYPESCRIPT in langs, "gswormk demands a TypeScript adapter")
        assertTrue(CodebaseLanguage.KOTLIN_JVM in langs, "cache-tier + columnar demand a Kotlin/JVM adapter")
    }

    @Test
    fun forgeProjectStateIsDistinctFromMasterBlackboard() {
        val induction = PanamaInduction.induction
        assertTrue(induction.forgeProjectState.isDistinctFromMaster)
    }

    @Test
    fun classpathSeparationIsEnforcedStructurally() {
        val forgeRoots = PanamaInduction.induction.forgeProjectState.classpathRoots.toSet()
        val masterJars = PanamaInduction.induction.masterBlackboardClasspath.ccekJars.toSet()
        val overlap = forgeRoots intersect masterJars
        assertTrue(overlap.isEmpty(), "forge project roots must not overlap master blackboard jars: $overlap")
    }

    @Test
    fun classpathSeparationInvariantRejectsOverlap() {
        val badForge = ForgeProjectState(
            project = PanamaInduction.project,
            classpathRoots = listOf("build/libs/TrikeShed-jvm-1.0.jar"), // overlaps master
        )
        val badMaster = MasterBlackboardClasspath(
            trikeshedRoot = ".",
            ccekJars = listOf("build/libs/TrikeShed-jvm-1.0.jar"),
        )
        assertFailsWith<IllegalArgumentException> {
            CodebaseInduction(badForge, badMaster)
        }
    }

    @Test
    fun polyglotResolutionProducesLanguageTaggedDagEntries() {
        val entries = PanamaInduction.resolveDagEntries()
        assertTrue(entries.isNotEmpty(), "ensemble must resolve to DAG entries")

        val jsEntries = entries.filter { it.language == CodebaseLanguage.TYPESCRIPT }
        val jvmEntries = entries.filter { it.language == CodebaseLanguage.KOTLIN_JVM }
        assertTrue(jsEntries.isNotEmpty(), "gswormk must produce JS-tagged entries")
        assertTrue(jvmEntries.isNotEmpty(), "cache-tier + columnar must produce JVM-tagged entries")
    }

    @Test
    fun dagCoordinatesAreLanguageNamespacedAndNeverCollideAcrossServices() {
        val entries = PanamaInduction.resolveDagEntries()
        val coordinates = entries.map { it.coordinate }
        assertEquals(coordinates.size, coordinates.toSet().size, "DAG coordinates must be unique")

        entries.forEach { entry ->
            assertTrue(
                entry.coordinate.startsWith("${entry.language.adapterKind}:"),
                "coordinate must be namespaced by language adapter kind: ${entry.coordinate}",
            )
        }
    }

    @Test
    fun cacheManagerIoSymbolIsResolvedAsJsExport() {
        val entries = PanamaInduction.resolveDagEntries()
        val cacheManager = entries.firstOrNull {
            it.symbolName.contains("cache-manager")
        }
        assertTrue(cacheManager != null, "gswormk cache-manager.ts must resolve as a DAG entry")
        assertEquals(CodebaseLanguage.TYPESCRIPT, cacheManager.language)
        assertEquals(BlackboardSymbolKind.EXPORT, cacheManager.kind)
        assertEquals("gswormk", cacheManager.originService)
    }

    @Test
    fun isamCursorSymbolIsResolvedAsJvmClass() {
        val entries = PanamaInduction.resolveDagEntries()
        val isam = entries.firstOrNull {
            it.symbolName.contains("ISAMCursor")
        }
        assertTrue(isam != null, "columnar ISAMCursor must resolve as a DAG entry")
        assertEquals(CodebaseLanguage.KOTLIN_JVM, isam.language)
        assertEquals(BlackboardSymbolKind.CLASS, isam.kind)
        assertEquals("columnar", isam.originService)
    }

    @Test
    fun cacheTierSymbolIsResolvedAsJvmClass() {
        val entries = PanamaInduction.resolveDagEntries()
        val cacheTier = entries.firstOrNull {
            it.symbolName.contains("CacheTier") && it.originService == "cache-tier"
        }
        assertTrue(cacheTier != null, "cache-tier CacheTier must resolve as a DAG entry")
        assertEquals(CodebaseLanguage.KOTLIN_JVM, cacheTier.language)
        assertEquals("cache-tier", cacheTier.originService)
    }
}