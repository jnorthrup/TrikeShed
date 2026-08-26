package borg.trikeshed.graal.subvm

import borg.trikeshed.btrfs.UserspaceBtrfs
import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PosixToolboxTest {

    private fun fs(): PosixFs {
        val fileOps = InMemoryFileOperations()
        val btrfs = UserspaceBtrfs("/mem/vol", fileOps)
        check(btrfs.createSubvolume("live"))
        return BtrfsPosixFs(btrfs, "live")
    }

    @Test
    fun normalizeRejectsTraversal() {
        assertEquals("workspace/x", PosixToolbox.normalize("/workspace/x"))
        assertEquals("", PosixToolbox.normalize("/"))
        assertEquals(null, PosixToolbox.normalize("/../etc"))
        assertEquals(null, PosixToolbox.normalize("workspace/../../etc"))
        assertEquals(null, PosixToolbox.normalize("  "))
    }

    @Test
    fun catEchoMkdirLsRoundTrip() {
        val fs = fs()
        assertEquals(0, PosixToolbox.run(fs, listOf("mkdir", "/workspace")).exit)
        val echo = PosixToolbox.run(fs, listOf("echo", "hello", "llm"))
        assertEquals("hello llm\n", echo.stdout)

        // write via cp from an echo'd file: touch + cat
        assertEquals(0, PosixToolbox.run(fs, listOf("touch", "/workspace/a.txt")).exit)
        val catEmpty = PosixToolbox.run(fs, listOf("cat", "/workspace/a.txt"))
        assertEquals(0, catEmpty.exit)
        assertEquals("", catEmpty.stdout)

        val ls = PosixToolbox.run(fs, listOf("ls", "/workspace"))
        assertEquals("a.txt\n", ls.stdout)

        val catMissing = PosixToolbox.run(fs, listOf("cat", "/workspace/nope"))
        assertEquals(1, catMissing.exit)
        assertTrue(catMissing.stderr.contains("No such file"))
    }

    @Test
    fun cpMvRmLifecycle() {
        val fs = fs()
        PosixToolbox.run(fs, listOf("mkdir", "/d"))
        // seed content via touch then cp needs content — use the fs seam directly for seeding
        check(fs.writeFile("d/src.txt", "payload".encodeToByteArray()))

        assertEquals(0, PosixToolbox.run(fs, listOf("cp", "/d/src.txt", "/d/dst.txt")).exit)
        assertEquals("payload", PosixToolbox.run(fs, listOf("cat", "/d/dst.txt")).stdout)

        assertEquals(0, PosixToolbox.run(fs, listOf("mv", "/d/dst.txt", "/d/moved.txt")).exit)
        assertEquals(1, PosixToolbox.run(fs, listOf("cat", "/d/dst.txt")).exit)
        assertEquals("payload", PosixToolbox.run(fs, listOf("cat", "/d/moved.txt")).stdout)

        assertEquals(0, PosixToolbox.run(fs, listOf("rm", "/d/moved.txt")).exit)
        assertEquals(1, PosixToolbox.run(fs, listOf("cat", "/d/moved.txt")).exit)
        // rm -f on missing file is silent success
        assertEquals(0, PosixToolbox.run(fs, listOf("rm", "-f", "/d/moved.txt")).exit)
        // rm on a directory fails
        assertEquals(1, PosixToolbox.run(fs, listOf("rm", "/d")).exit)
    }

    @Test
    fun grepHeadTailWc() {
        val fs = fs()
        check(fs.writeFile("log.txt", "alpha one\nbeta two\nALPHA three\ngamma four\n".encodeToByteArray()))

        val grep = PosixToolbox.run(fs, listOf("grep", "alpha", "/log.txt"))
        assertEquals("alpha one\n", grep.stdout)

        val grepI = PosixToolbox.run(fs, listOf("grep", "-i", "alpha", "/log.txt"))
        assertEquals("alpha one\nALPHA three\n", grepI.stdout)

        val noMatch = PosixToolbox.run(fs, listOf("grep", "zeta", "/log.txt"))
        assertEquals(1, noMatch.exit)

        val head = PosixToolbox.run(fs, listOf("head", "-n", "2", "/log.txt"))
        assertEquals("alpha one\nbeta two\n", head.stdout)

        val tail = PosixToolbox.run(fs, listOf("tail", "-n", "1", "/log.txt"))
        assertEquals("gamma four\n", tail.stdout)

        val wc = PosixToolbox.run(fs, listOf("wc", "/log.txt"))
        assertEquals(0, wc.exit)
        assertTrue(wc.stdout.contains("4"), "wc lines: ${wc.stdout}")
    }

    @Test
    fun unknownToolAndTrueFalse() {
        val fs = fs()
        assertEquals(127, PosixToolbox.run(fs, listOf("sed", "x")).exit)
        assertEquals(0, PosixToolbox.run(fs, listOf("true")).exit)
        assertEquals(1, PosixToolbox.run(fs, listOf("false")).exit)
        assertEquals(0, PosixToolbox.run(fs, listOf("pwd")).exit)
    }

    @Test
    fun traversalNeverEscapesSubvolume() {
        val fs = fs()
        check(fs.writeFile("secret.txt", "hidden".encodeToByteArray()))
        // every tool rejects .. paths outright
        for (tool in listOf("cat", "rm", "cp", "mv", "head", "tail", "wc", "grep")) {
            val r = when (tool) {
                "grep" -> PosixToolbox.run(fs, listOf(tool, "x", "/../secret.txt"))
                "cp", "mv" -> PosixToolbox.run(fs, listOf(tool, "/../secret.txt", "/x"))
                else -> PosixToolbox.run(fs, listOf(tool, "/../secret.txt"))
            }
            assertTrue(r.exit != 0, "$tool should reject traversal")
        }
    }
}
