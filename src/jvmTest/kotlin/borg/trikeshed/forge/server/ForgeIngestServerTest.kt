package borg.trikeshed.forge.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForgeIngestServerTest {
    private val plan = "TARGET: x\n\n6. Work packages\n\nG0 — Root graph\nbody\n\nG1 — Seed\nDepends on: G0\n\n7. Acceptance\n"

    @Test
    fun markdownPassesThroughAndGates() {
        val r = ForgeIngestServer.ingest("plan.md", plan.encodeToByteArray(), persistUser = null)
        assertEquals(plan, r["markdown"])
        assertTrue(r["plan"] as Boolean)
        assertFalse(r["persisted"] as Boolean)
    }

    @Test
    fun proseIsRefusedByTheGate() {
        val r = ForgeIngestServer.ingest("hi.txt", "hi\n\nnot a plan\n".encodeToByteArray(), persistUser = "nobody")
        assertFalse(r["plan"] as Boolean)
        assertFalse(r["persisted"] as Boolean)   // persist requested but gate says no
    }

    @Test
    fun tikaCandidateGetsAHeading() {
        // csv is a Tika candidate (html/md/txt pass through verbatim): text extracted, wrapped under "# <original name>"
        val r = ForgeIngestServer.ingest("note.csv", "a,b\nhello forge,2\n".encodeToByteArray(), persistUser = null)
        val md = r["markdown"] as String
        assertTrue(md.startsWith("# note.csv\n"), md)
        assertTrue(md.contains("hello forge"), md)
    }
}
