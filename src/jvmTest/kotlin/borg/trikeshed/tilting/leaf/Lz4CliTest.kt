package borg.trikeshed.tilting.leaf

import borg.trikeshed.tilting.lz4.Lz4Frame
import borg.trikeshed.tilting.lz4.Lz4Kotlin
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Frames written here decode with the lz4 command; the command's frames (checksums, sizes) decode here. */
class Lz4CliTest {
    private val cli = listOf("/opt/homebrew/bin/lz4", "/usr/bin/lz4", "/usr/local/bin/lz4").firstOrNull { File(it).canExecute() }

    private fun lz4(vararg args: String): ByteArray {
        val process = ProcessBuilder(listOf(cli!!) + args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = process.inputStream.readBytes()
        assertEquals(0, process.waitFor(), "lz4 ${args.joinToString(" ")}")
        return out
    }

    @Test
    fun framesInteroperateWithTheLz4Command() {
        if (cli == null) return
        val sql = "SELECT p.id, p.body, p.created_at, u.username FROM posts p JOIN users u ON u.id = p.user_id;"
        val input = ByteArray(300_000) { i -> if (i % 11 < 8) sql[(i * 7) % sql.length].code.toByte() else (i * 131).toByte() }
        val dir = File(System.getProperty("java.io.tmpdir"))
        val plain = File.createTempFile("leaf", ".bin", dir).apply { writeBytes(input); deleteOnExit() }
        val ours = File.createTempFile("leaf", ".lz4", dir).apply { writeBytes(Lz4Frame(Lz4Kotlin()).encode(input, 0, input.size)); deleteOnExit() }
        assertContentEquals(input, lz4("-d", "-c", ours.path))
        for (flags in listOf(arrayOf<String>(), arrayOf("-BX", "--content-size", "-B4"), arrayOf("-B5", "--no-frame-crc"))) {
            val theirs = lz4("-c", *flags, plain.path)
            val out = ByteArray(input.size)
            assertEquals(input.size, Lz4Frame(Lz4Kotlin()).decodeInto(theirs, 0, theirs.size, out), flags.joinToString(" "))
            assertContentEquals(input, out, flags.joinToString(" "))
        }
    }
}
