package borg.trikeshed.spokes

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM integration tests for Spokes — verifies disk-backed BlockStore,
 * JAR serving, and filesystem layout correctness.
 */
class SpokesJvmIntegrationTest {

    private val testCacheDir = "${System.getProperty("user.home")}/.m2/spokes-test-jvm-${System.nanoTime()}"

    @BeforeTest
    fun setUp() {
        // cache dir is lazily created by FileSystemBlockStore
    }

    @AfterTest
    fun tearDown() {
        val path = Paths.get(testCacheDir)
        if (Files.exists(path)) {
            Files.walk(path).sorted(java.util.Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun coordinate_mavenPath_servesJvmAbsolutePath() = runTest {
        val coord = Coordinate("borg.trikeshed", "spokes", "0.1.0", Packaging.JAR)
        assertEquals("/borg/trikeshed/spokes/0.1.0/spokes-0.1.0.jar", coord.mavenPath)
    }

    @Test
    fun publishingAnnouncesToRegistry() = runTest {
        val element = SpokesElement.Standalone(testCacheDir)
        val coord = Coordinate("integr.test", "jvm-artifact", "1.0.0")
        val payload = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())

        element.publish(coord, payload)

        val source = element.registry[coord]
        assertEquals(Source.LOCAL_CACHE, source)

        val results = element.search(CoordQuery(group = "integr.test"))
        assertEquals(1, results.size)
        assertEquals(coord.mavenCoord, results.single().mavenCoord)
    }

    @Test
    fun resolveMissReturnsNull() = runTest {
        val element = SpokesElement.Standalone(testCacheDir)
        val missing = Coordinate("does.not", "exist", "0.0.1")
        assertEquals(null, element.resolve(missing))
    }

    @Test
    fun multiplePublishDistinctCoords() = runTest {
        val element = SpokesElement.Standalone(testCacheDir)
        for (i in 0 until 5) {
            val coord = Coordinate("multi.test", "artifact-$i", "1.$i.0")
            element.publish(coord, "bytes-$i".encodeToByteArray())
        }
        val all = element.registry.all()
        assertEquals(5, all.size)
        assertTrue(all.all { it.group == "multi.test" })
    }
}
