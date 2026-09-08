package borg.trikeshed.graal.subvm

import borg.trikeshed.context.ElementState
import java.io.IOException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GuestModulesMountTableTest {

    @Test
    fun closeAllClosesTheActualMountedClassLoader() {
        val dir = Files.createTempDirectory("guest-mount-table-")
        try {
            val jar = probeJar(dir)
            val table = GuestModules.MountTable()
            val loader = URLClassLoader(arrayOf(jar.toUri().toURL()), ClassLoader.getPlatformClassLoader())
            val mounted = table.loader("probe") { loader }

            assertSame(loader, mounted)
            assertEquals(listOf("probe"), table.mounted())
            assertEquals(ElementState.OPEN, table.lifecycle())
            assertNotNull(mounted.loadClass("probe.First").getConstructor().newInstance())
            val stream = assertNotNull(
                mounted.getResourceAsStream("probe/Second.class"),
                "expected a real jar resource stream before close",
            )

            table.closeAll()

            assertEquals(ElementState.CLOSED, table.lifecycle())
            assertEquals(emptyList(), table.mounted())
            assertFailsWith<IOException> { stream.read() }
            assertFailsWith<ClassNotFoundException> { mounted.loadClass("probe.Second") }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun aClosedMountTableRefusesLateLoaders() {
        val table = GuestModules.MountTable()
        table.closeAll()
        val err = assertFailsWith<IllegalStateException> {
            table.loader("late") { URLClassLoader(emptyArray(), ClassLoader.getPlatformClassLoader()) }
        }
        assertTrue("closed" in err.message.orEmpty(), err.message.orEmpty())
        assertEquals(emptyList(), table.mounted())
    }

    @Test
    fun closedIsPublishedOnlyAfterTheOwningCloseFinishes() {
        val table = GuestModules.MountTable()
        val enteredClose = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val closeFinished = AtomicBoolean(false)
        val loader = object : URLClassLoader(emptyArray(), ClassLoader.getPlatformClassLoader()) {
            override fun close() {
                enteredClose.countDown()
                assertTrue(releaseClose.await(5, TimeUnit.SECONDS), "test did not release close")
                super.close()
                closeFinished.set(true)
            }
        }
        table.loader("slow") { loader }

        val first = Thread { table.closeAll() }
        first.start()
        assertTrue(enteredClose.await(5, TimeUnit.SECONDS), "first close did not enter loader.close")
        assertEquals(ElementState.DRAINING, table.lifecycle())
        assertTrue(!closeFinished.get(), "loader close finished before release")

        val secondFinished = AtomicBoolean(false)
        val second = Thread {
            table.closeAll()
            secondFinished.set(true)
        }
        second.start()
        Thread.sleep(100)
        assertEquals(ElementState.DRAINING, table.lifecycle(), "CLOSED published before loader.close completed")
        assertTrue(!secondFinished.get(), "second close should wait for the owning close")

        releaseClose.countDown()
        first.join(5_000)
        second.join(5_000)
        assertTrue(closeFinished.get(), "owning close did not finish")
        assertTrue(secondFinished.get(), "second close did not return")
        assertEquals(ElementState.CLOSED, table.lifecycle())
    }

    private fun probeJar(root: Path): Path {
        val source = root.resolve("src/probe").createDirectories()
        source.resolve("First.java").writeText(
            "package probe; public class First { public String value() { return \"first\"; } }",
        )
        source.resolve("Second.java").writeText(
            "package probe; public class Second { public String value() { return \"second\"; } }",
        )
        val classes = root.resolve("classes").createDirectories()
        val compiler = assertNotNull(ToolProvider.getSystemJavaCompiler(), "JDK compiler is required")
        val status = compiler.run(
            null,
            null,
            null,
            "-d",
            classes.toString(),
            source.resolve("First.java").toString(),
            source.resolve("Second.java").toString(),
        )
        assertEquals(0, status, "probe classes did not compile")

        val jar = root.resolve("probe.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            Files.walk(classes).use { paths ->
                paths.filter { Files.isRegularFile(it) }.forEach { file ->
                    val entryName = classes.relativize(file).toString().replace(java.io.File.separatorChar, '/')
                    out.putNextEntry(JarEntry(entryName))
                    Files.copy(file, out)
                    out.closeEntry()
                }
            }
        }
        return jar
    }
}
