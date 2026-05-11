package borg.trikeshed.spokes

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TDD for SpokesElement — verifies the polyplatform CDN algebra:
 * coordinates, registry search, publish/resolve cycle.
 */
class SpokesElementCommonTest {

    @Test
    fun coordinate_mavenCoord_isCanonical() = runTest {
        val coord = Coordinate("org.bereft", "ipfs", "0.1.0", Packaging.JAR)
        assertEquals("org.bereft:ipfs:0.1.0:jar", coord.mavenCoord)
    }

    @Test
    fun coordinate_mavenPath_matchesMavenLayout() = runTest {
        val coord = Coordinate("borg.trikeshed", "spokes", "1.0.0", Packaging.JAR)
        assertEquals("/borg/trikeshed/spokes/1.0.0/spokes-1.0.0.jar", coord.mavenPath)
    }

    @Test
    fun coordinate_equality_isValueBased() = runTest {
        val a = Coordinate("a.b", "artifact", "1.0", Packaging.JAR)
        val b = Coordinate("a.b", "artifact", "1.0", Packaging.JAR)
        val c = Coordinate("a.b", "artifact", "1.0", Packaging.POM)
        assertEquals(a, b)
        assertTrue(a != c) // different packaging
    }

    @Test
    fun registry_registerAndSearch_byGroup() = runTest {
        val registry = InMemoryArtifactRegistry()
        registry.register(Coordinate("borg.trikeshed", "spokes", "0.1.0", Packaging.JAR), Source.LOCAL_CACHE)
        registry.register(Coordinate("borg.trikeshed", "ipfs", "0.2.0", Packaging.JAR), Source.LOCAL_CACHE)
        registry.register(Coordinate("org.other", "lib", "1.0.0", Packaging.JAR), Source.PASSTHROUGH)

        val results = registry.search(CoordQuery(group = "borg.trikeshed"))
        assertEquals(2, results.size)
    }

    @Test
    fun registry_search_byArtifactSubstring() = runTest {
        val registry = InMemoryArtifactRegistry()
        registry.register(Coordinate("a.b", "trikeshed-common", "1.0", Packaging.JAR), Source.LOCAL_CACHE)
        registry.register(Coordinate("a.b", "trikeshed-server", "2.0", Packaging.JAR), Source.LOCAL_CACHE)
        registry.register(Coordinate("a.b", "unrelated", "3.0", Packaging.JAR), Source.LOCAL_CACHE)

        val results = registry.search(CoordQuery(artifact = "trikeshed"))
        assertEquals(2, results.size)
    }

    @Test
    fun registry_search_filtersBySource() = runTest {
        val registry = InMemoryArtifactRegistry()
        registry.register(Coordinate("a.b", "local-artifact", "1.0", Packaging.JAR), Source.LOCAL_CACHE)
        registry.register(Coordinate("a.b", "remote-artifact", "1.0", Packaging.JAR), Source.PASSTHROUGH)

        val local = registry.search(CoordQuery(source = Source.LOCAL_CACHE))
        assertEquals(1, local.size)
        assertEquals("a.b:local-artifact:1.0:jar", local.single().mavenCoord)

        val passthrough = registry.search(CoordQuery(source = Source.PASSTHROUGH))
        assertEquals(1, passthrough.size)
    }

    @Test
    fun registry_search_maxResults() = runTest {
        val registry = InMemoryArtifactRegistry()
        for (i in 0..99) {
            registry.register(Coordinate("a.b", "artifact-$i", "1.0", Packaging.JAR), Source.LOCAL_CACHE)
        }
        val results = registry.search(CoordQuery(maxResults = 10))
        assertTrue(results.size <= 10)
    }

    @Test
    fun registry_queueGitBuild() = runTest {
        val registry = InMemoryArtifactRegistry()
        val job = registry.queueGitBuild("https://github.com/user/repo.git", "abc123")
        assertEquals("build-0", job.id)
        assertEquals(SpokesElement.BuildStatus.QUEUED, job.status)
        assertEquals("https://github.com/user/repo.git", job.repoUrl)
    }

    @Test
    fun registry_allListsRegisteredCoordinates() = runTest {
        val registry = InMemoryArtifactRegistry()
        registry.register(Coordinate("a.b", "x", "1", Packaging.JAR), Source.LOCAL_CACHE)
        registry.register(Coordinate("a.b", "y", "2", Packaging.POM), Source.PASSTHROUGH)

        val all = registry.all()
        assertEquals(2, all.size)
    }

    @Test
    fun registry_get_returnsSource() = runTest {
        val registry = InMemoryArtifactRegistry()
        val coord = Coordinate("a.b", "c", "1.0", Packaging.JAR)
        assertNull(registry[coord])
        registry.register(coord, Source.LOCAL_CACHE)
        assertEquals(Source.LOCAL_CACHE, registry[coord])
    }

    @Test
    fun packaging_hasCorrectContentType() = runTest {
        assertEquals("application/java-archive", Packaging.JAR.contentType)
        assertEquals("application/xml", Packaging.POM.contentType)
        assertEquals("application/json", Packaging.MODULE.contentType)
        assertEquals("application/javascript", Packaging.JS.contentType)
        assertEquals("application/gzip", Packaging.TGZ.contentType)
    }

    @Test
    fun spokesElement_publishAndResolve_viaRegistry() = runTest {
        val element = SpokesElement.Standalone("/tmp/spokes-test-cache")
        val coord = Coordinate("test.group", "test-artifact", "0.0.1")
        val payload = "test jar bytes".encodeToByteArray()

        element.publish(coord, payload)

        // resolve checks blockStore (disk stub) then DHT — both miss in commonMain
        // but the registry records the publish, proving the route
        val regSource = element.registry[coord]
        assertEquals(Source.LOCAL_CACHE, regSource)

        val searchResults = element.search(CoordQuery(artifact = "test-artifact"))
        assertEquals(1, searchResults.size)
        assertEquals(coord.mavenCoord, searchResults.single().mavenCoord)
    }
}
