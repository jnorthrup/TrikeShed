package borg.trikeshed.lcnc

import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.narsese.DocumentAppendLog
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeadhunterCredentialsTest {
    private fun path() = "/private/tmp/headhunter-auth-${Random.nextLong().toULong()}"

    @Test
    fun credentialsReopenAndContinueTheSameRevisionAndSequenceHistory() = runBlocking {
        val base = path()
        val casPath = "$base.cas"
        val logPath = "$base.wal"
        var firstRevision = ""
        HeadhunterCredentials.open("/private/tmp", casPath, logPath).use { owner ->
            owner.keys.storeSourceCredential("source-a", "https://jobs.example", "/", "Cookie", "session=isolated-secret-a")
            assertEquals("session=isolated-secret-a", owner.keys.readSourceCredential("source-a")?.get("value"))
            firstRevision = owner.database.docJson("source-credential:source-a")?.get("_rev") as String
            assertEquals(1L, owner.database.updateSeq)
            assertTrue(owner.keys.listProviders().isEmpty())
        }
        HeadhunterCredentials.open("/private/tmp", casPath, logPath).use { owner ->
            assertEquals(firstRevision, owner.database.docJson("source-credential:source-a")?.get("_rev"))
            assertEquals("session=isolated-secret-a", owner.keys.readSourceCredential("source-a")?.get("value"))
            owner.keys.storeSourceCredential("source-a", "https://jobs.example", "/", "Cookie", "session=isolated-secret-b")
            owner.keys.storeSourceCredential("source-b", "https://another.example", "/jobs", "Authorization", "Bearer isolated-secret-c")
            assertEquals(3L, owner.database.updateSeq)
            val frames = owner.database.store.changes.series()
            assertEquals(3, frames.size)
            assertEquals(0L, frames[0].sequence)
            assertEquals(1L, frames[1].sequence)
            assertEquals(2L, frames[2].sequence)
        }
        HeadhunterCredentials.open("/private/tmp", casPath, logPath).use { owner ->
            assertEquals("session=isolated-secret-b", owner.keys.readSourceCredential("source-a")?.get("value"))
            assertEquals("Bearer isolated-secret-c", owner.keys.readSourceCredential("source-b")?.get("value"))
            assertEquals(3L, owner.database.updateSeq)
        }
    }

    @Test
    fun staleWritesDoNotAppendAndPrivateLedgerContainsOnlyBodyReferences() = runBlocking {
        val base = path()
        val casPath = "$base.cas"
        val logPath = "$base.wal"
        val generalCas = CasStore.inMemory()
        val secret = "isolated-secret-never-in-public-cas"
        var bodyCid: ContentId? = null
        HeadhunterCredentials.open("/private/tmp", casPath, logPath).use { owner ->
            owner.keys.storeSourceCredential("source", "https://jobs.example", "/", "Cookie", secret)
            val first = owner.database.docJson("source-credential:source")!!
            val firstRev = first["_rev"] as String
            owner.keys.storeSourceCredential("source", "https://jobs.example", "/", "Cookie", "replacement-secret")
            val rejected = owner.database.put("source-credential:source", first - "_rev", firstRev)
            assertEquals("conflict", rejected["error"])
            assertEquals(2L, owner.database.updateSeq)
            assertEquals("replacement-secret", owner.keys.readSourceCredential("source")?.get("value"))
        }
        DocumentAppendLog.open(logPath, "/private/tmp").use { ledger ->
            assertEquals(2L, ledger.replay { _, payload ->
                val entry = CanonicalCbor.decodeMap(payload)
                assertEquals(setOf("id", "rev", "deleted", "bodyCid"), entry.keys)
                assertFalse(payload.decodeToString().contains(secret))
                assertFalse(payload.decodeToString().contains("replacement-secret"))
                bodyCid = ContentId(entry["bodyCid"] as String)
                assertNull(generalCas.get(bodyCid!!))
            })
        }
        assertNotNull(bodyCid)
        val closed = HeadhunterCredentials.open("/private/tmp", casPath, logPath)
        closed.close()
        assertFails { closed.keys.storeSourceCredential("another", "https://jobs.example", "/", "Cookie", "closed-secret") }
    }
}
