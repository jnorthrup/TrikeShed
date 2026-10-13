package borg.trikeshed.parse.confix

import borg.trikeshed.job.CanonicalCbor
import kotlin.test.*

class ConfixKeyNavigationTest {
    @Test fun cborAndJsonNavigationUseDecodedKeys() {
        val value = "Māori 医 — 🧬"
        val fields = mapOf("患者" to mapOf("résumé" to value), "ascii" to "short", "long-key-".repeat(5) to "long")
        val cbor = confixDoc(CanonicalCbor.encodeMap(fields), Syntax.CBOR)
        val json = confixDoc("""{"\u60a3\u8005":{"r\u00e9sum\u00e9":"Māori 医 — 🧬"},"ascii":"short"}""")
        for (doc in listOf(cbor, json)) {
            assertEquals(value, doc.value("患者", "résumé"))
            assertEquals(value, doc.scalar(StringBuilder("患者"), "résumé"))
            assertEquals("short", doc.value("ascii"))
            assertNotNull(doc.index.facet(ConfixIndexK.KeyToChild)("患者"))
            assertNull(doc.value("absent"))
        }
        assertEquals("long", cbor.value("long-key-".repeat(5)))
    }
}
