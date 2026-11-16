package borg.trikeshed.daemon

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuruMeditationTest {
    /** A minimal valid class: `public class <name> {}` for Java 8 (major 52), assembled by hand. */
    private fun classBytes(name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val d = java.io.DataOutputStream(out)
        d.writeInt(0xCAFEBABE.toInt()); d.writeShort(0); d.writeShort(52)
        d.writeShort(5)                                   // constant pool count (4 entries)
        d.writeByte(1); d.writeUTF(name.replace('.', '/'))   // 1 Utf8 this
        d.writeByte(7); d.writeShort(1)                      // 2 Class this
        d.writeByte(1); d.writeUTF("java/lang/Object")       // 3 Utf8 super
        d.writeByte(7); d.writeShort(3)                      // 4 Class super
        d.writeShort(0x0021); d.writeShort(2); d.writeShort(4)
        d.writeShort(0); d.writeShort(0); d.writeShort(0); d.writeShort(0)
        return out.toByteArray()
    }

    private fun loaderOver(dir: File, name: String): URLClassLoader =
        URLClassLoader(arrayOf(dir.toURI().toURL()), null)   // parent null: bootstrap only, so this loader alone sees the file

    @Test fun onlyLinkageErrorsInTheCauseChainCountAsClassFileCrashes() {
        assertTrue(GuruMeditation.isClassFileCrash(NoClassDefFoundError("a/B")))
        assertTrue(GuruMeditation.isClassFileCrash(RuntimeException("wrapped", VerifyError("bad"))))
        assertFalse(GuruMeditation.isClassFileCrash(IllegalStateException("plain")))
        assertNull(GuruMeditation.find(IllegalStateException("plain")))
    }

    @Test fun aTruncatedClassFileNamesTheLoaderThatOwnsIt() {
        val dir = Files.createTempDirectory("guru").toFile()
        try {
            val name = "guru.sample.Broken"
            val file = File(dir, name.replace('.', '/') + ".class").also { it.parentFile.mkdirs() }
            file.writeBytes(classBytes(name).copyOf(30))   // cut mid constant pool
            val loader = loaderOver(dir, name)
            val crash = try { loader.loadClass(name); null } catch (t: Throwable) { t }
            assertNotNull(crash)
            assertTrue(crash is ClassFormatError, "expected ClassFormatError, got $crash")

            val r = GuruMeditation.describe(crash, context = loader, named = name)
            assertEquals("ClassFormatError", r.kind)
            assertEquals(name, r.className)
            val owner = assertNotNull(r.loader)
            assertEquals(Integer.toHexString(System.identityHashCode(loader)).uppercase().padStart(8, '0'), owner.id)
            assertTrue(r.origin!!.endsWith("guru/sample/Broken.class"), r.origin)
            assertTrue(r.code.startsWith("#81000001."), r.code)
            assertTrue(r.code.endsWith("." + owner.id))
            assertEquals("bootstrap", r.chain.last().type)

            val text = GuruMeditation.text(r)
            assertTrue(text.startsWith("Software Failure."), text)
            assertTrue("Guru Meditation ${r.code}" in text)
            assertTrue(owner.id in text)
        } finally { dir.deleteRecursively() }
    }

    @Test fun aMissingClassIsRecoverableAndFindsNoLoader() {
        val r = GuruMeditation.describe(NoClassDefFoundError("guru/never/Published"), context = URLClassLoader(emptyArray(), null))
        assertEquals("NoClassDefFoundError", r.kind)
        assertEquals("guru.never.Published", r.className)
        assertNull(r.loader)
        assertNull(r.origin)
        assertTrue(r.code.startsWith("#00000004."), r.code)
        assertTrue(GuruMeditation.text(r).startsWith("Recoverable Alert."))
        assertTrue("no loader in the chain can see the class file" in GuruMeditation.text(r))
    }

    @Test fun jsonAndHtmlCarryTheGuruNumberAndEscape() {
        val r = GuruMeditation.describe(VerifyError("Bad \"type\" <on> stack"), target = String::class.java)
        val json = GuruMeditation.json(r)
        assertTrue(json.startsWith("{\"guru\":\"" + r.code + "\""), json)
        assertTrue("\\\"type\\\"" in json, json)
        assertTrue("\"error\":\"class_file_crash\"" in json)
        val html = GuruMeditation.html(r)
        assertTrue("&lt;on&gt;" in html && "<on>" !in html, html)
        assertTrue("Guru Meditation ${r.code}" in html)
    }

    @Test fun reportsAreKeptInABoundedRing() {
        repeat(40) { GuruMeditation.describe(NoClassDefFoundError("guru/ring/C$it")) }
        val recent = GuruMeditation.recent()
        assertEquals(16, recent.size)
        assertEquals("guru.ring.C39", recent.last().className)
    }
}
