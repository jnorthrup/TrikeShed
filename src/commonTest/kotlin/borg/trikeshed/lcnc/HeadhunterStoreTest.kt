package borg.trikeshed.lcnc

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class HeadhunterStoreTest {
    class Log : DurableAppendLog {
        val frames = SeriesBuffer<Join<Long, ByteArray>>()
        var committed = 0
        var failFlush = false
        var commitBeforeFailure = false
        override fun append(sequence: Long, payload: ByteArray): Long {
            frames.add(sequence j payload.copyOf())
            return sequence
        }
        override fun flush() {
            if (!failFlush || commitBeforeFailure) committed = frames.size
            check(!failFlush) { "fsync failed" }
        }
        override suspend fun replay(onFrame: suspend (Long, ByteArray) -> Unit): Long {
            for (frame in frames.snapshot().take(committed).view) onFrame(frame.a, frame.b.copyOf())
            return if (committed == 0) 0 else frames[committed - 1].a
        }
        fun crash() { while (frames.size > committed) frames.removeLast(); failFlush = false }
        override fun injectCorruptionAfter(sequence: Long) = error("Not used")
    }

    class Rig(val cas: CasStore = CasStore.inMemory(), val log: Log = Log()) {
        val board = ConfixBlackboard.empty()
        var now = 10L
        val store = HeadhunterStore(board, cas, log) { now++ }
        suspend fun save(kind: String, fields: HeadhunterRecord, old: HeadhunterRecord? = null): HeadhunterRecord =
            store.save(kind, old?.get("id") as? String, old?.get("cid") as? String, fields)
        suspend fun evidence(text: String = "Implemented Kotlin channels") =
            save("evidence", mapOf("title" to "Experience", "text" to text, "category" to "experience"))
        suspend fun listing(text: String = "Kotlin developer", extra: HeadhunterRecord = emptyMap()) =
            save("listing", mapOf("title" to "Developer", "text" to text) + extra)
        suspend fun application(listing: HeadhunterRecord, extra: HeadhunterRecord = emptyMap()) =
            save("application", mapOf("listingId" to listing["id"], "status" to "research") + extra)
        suspend fun artifact(application: HeadhunterRecord, source: HeadhunterRecord, old: HeadhunterRecord? = null): HeadhunterRecord =
            save("artifact", mapOf(
                "applicationId" to application["id"], "type" to "resume", "format" to "markdown",
                "title" to "Tailored resume", "content" to fields(source)["text"],
                "sources" to listOf(HeadhunterStore.reference(source)), "generation" to "assembled", "reviewStatus" to "proposed",
            ), old)
    }

    @Test fun retryAndReplayRetainTheSameVersionAndDetachedHistory() = runTest {
        val first = Rig()
        val input = linkedMapOf<String, Any?>("title" to "Experience", "text" to "One", "category" to "experience")
        val one = first.save("evidence", input)
        input["text"] = "caller changed its input"
        assertEquals("One", fields(one)["text"])
        val savedHistory = first.store.history(id(one))
        val two = first.save("evidence", fields(one) + ("text" to "Two"), one)
        val boardRevision = first.board.snapshot().revision
        val retry = first.store.save("evidence", id(two), cid(one), linkedMapOf("text" to "Two", "category" to "experience", "title" to "Experience"))
        assertEquals(cid(two), cid(retry), "An acknowledged or uncertain retry must not fork history")
        assertEquals(boardRevision, first.board.snapshot().revision)
        assertEquals(2, first.log.committed)
        assertEquals(1, savedHistory.size)
        assertEquals(cid(one), cid(savedHistory[0]))
        first.store.restore()
        assertEquals(boardRevision, first.board.snapshot().revision, "Replay preserves the JSON number representation used at publication")

        val second = Rig(first.cas, first.log)
        assertEquals(1, second.store.restore())
        val restored = second.store.record(id(one))!!
        assertEquals(cid(two), cid(restored))
        assertEquals(cid(one), restored["previousCid"])
        assertEquals("Two", fields(restored)["text"])
        val history = second.store.history(id(one))
        assertEquals(2, history.size)
        assertEquals(cid(one), cid(history[0]))
        assertEquals(cid(two), cid(history[1]))
        val revision = second.board.snapshot().revision
        second.store.restore()
        assertEquals(revision, second.board.snapshot().revision, "Replay is not a new publication")
        assertEquals(restored, second.board.get("headhunter/${id(one)}"))
    }

    @Test fun numericJsonFieldsHaveOneCanonicalIdentityBeforeAndAfterReplay() = runTest {
        val rig = Rig()
        val source = rig.save("source", mapOf("title" to "Careers", "origin" to "https://example.test", "extraction" to mapOf("limit" to 12)))
        val again = rig.save("source", mapOf("extraction" to mapOf("limit" to 12.0), "origin" to "https://example.test", "title" to "Careers"))
        assertEquals(source["id"], again["id"])
        assertEquals(source["cid"], again["cid"])
        val reopened = Rig(rig.cas, rig.log)
        reopened.store.restore()
        val retry = reopened.save("source", HeadhunterStore.fieldsOf(source))
        assertEquals(source["cid"], retry["cid"])
        assertEquals(1, rig.log.committed)
    }

    @Test fun concurrentCorrectionsCannotSilentlyOverwriteOneAnother() = runTest {
        val rig = Rig()
        val original = rig.evidence()
        val a = async { runCatching { rig.save("evidence", fields(original) + ("text" to "A"), original) } }
        val b = async { runCatching { rig.save("evidence", fields(original) + ("text" to "B"), original) } }
        val results = s_[a.await(), b.await()]
        assertEquals(1, results.view.count { it.isSuccess })
        assertEquals(1, results.view.count { it.exceptionOrNull() is IllegalArgumentException })
        assertEquals(2, rig.store.history(id(original)).size)
        assertEquals(2, rig.log.committed)
        assertFailsWith<IllegalArgumentException> {
            rig.store.save("evidence", id(original), null, fields(original) + ("text" to "Unversioned overwrite"))
        }
    }

    @Test fun artifactSourcesRemainPinnedThroughCorrectionsAndStaleReviewIsRejected() = runTest {
        val rig = Rig()
        val evidence = rig.evidence()
        val application = rig.application(rig.listing())
        val artifact = rig.artifact(application, evidence)
        val approved = rig.save("review", mapOf("subjectId" to id(artifact), "subjectCid" to cid(artifact), "decision" to "approved", "notes" to "Checked against source"))
        val correction = rig.save("evidence", fields(evidence) + ("text" to "Implemented and reviewed Kotlin channels"), evidence)
        val ref = HeadhunterStore.references(fields(rig.store.record(id(artifact))!!))[0]
        assertEquals(cid(evidence), ref.b)
        assertEquals(fields(evidence), fields(rig.store.version(ref.a, ref.b)!!))
        val edited = rig.artifact(application, correction, artifact)
        assertEquals(cid(artifact), edited["previousCid"])
        assertEquals(cid(correction), HeadhunterStore.references(fields(edited))[0].b)
        assertEquals(cid(artifact), fields(approved)["subjectCid"])
        assertFailsWith<IllegalArgumentException> {
            rig.save("review", mapOf("subjectId" to id(artifact), "subjectCid" to cid(artifact), "decision" to "approved", "notes" to "Stale page"))
        }
        assertFailsWith<IllegalArgumentException> {
            rig.save("artifact", fields(edited) + mapOf("reviewStatus" to "reviewed", "reviewId" to id(approved)), edited)
        }
        assertFailsWith<IllegalArgumentException> {
            rig.save("artifact", fields(edited) + ("sources" to listOf(mapOf("id" to id(application), "cid" to cid(evidence)))), edited)
        }
    }

    @Test fun preparedActionsAndUserReportsCannotAssertConfirmedDelivery() = runTest {
        val rig = Rig()
        val application = rig.application(rig.listing())
        val prepared = rig.save("action", mapOf("applicationId" to id(application), "type" to "email", "status" to "prepared", "notes" to "Awaiting review"))
        val report = rig.save("action", fields(prepared) + mapOf("status" to "reported", "notes" to "User says they emailed yesterday"), prepared)
        assertEquals("research", fields(rig.store.record(id(application))!!)["status"])
        assertEquals("reported", fields(report)["status"])
        assertFailsWith<IllegalArgumentException> { rig.save("action", fields(report) + ("status" to "confirmed"), report) }
        assertFailsWith<IllegalArgumentException> { rig.save("application", fields(application) + ("status" to "applied"), application) }
        assertFailsWith<IllegalArgumentException> { rig.save("action", mapOf("applicationId" to id(application), "type" to "phone", "status" to "reported")) }
        val other = rig.application(rig.listing("Another opening"))
        val artifact = rig.artifact(application, rig.evidence())
        assertFailsWith<IllegalArgumentException> {
            rig.save("action", mapOf("applicationId" to id(other), "type" to "email", "status" to "prepared", "artifactId" to id(artifact), "artifactCid" to cid(artifact)))
        }
    }

    @Test fun collisionCandidatesCiteExactListingsRepresentationsContactsAndActionHistory() = runTest {
        val rig = Rig()
        val employer = rig.save("employer", mapOf("name" to "Example Employer"))
        val listing = rig.listing(extra = mapOf("employerId" to id(employer), "requisition" to "REQ-42", "url" to "https://one.example/opening"))
        val contact = rig.save("contact", mapOf("name" to "Recruiter", "email" to "recruiter@example.test"))
        val application = rig.application(listing, mapOf("contactId" to id(contact)))
        val duplicate = rig.listing("Different source excerpt", mapOf("employerId" to id(employer), "requisition" to "req-42", "url" to "https://two.example/opening"))
        val prior = rig.application(duplicate)
        val representation = rig.save("representation", mapOf("employerId" to id(employer), "status" to "active"))
        val action = rig.save("action", mapOf("applicationId" to id(prior), "type" to "email", "status" to "reported", "notes" to "A prior outreach reported by the user"))
        val aliasContact = rig.save("contact", mapOf("name" to "Same mailbox", "email" to "Recruiter@example.test"))
        val differentListing = rig.listing("Similar Kotlin job at another employer")
        val sameMailbox = rig.application(differentListing, mapOf("contactId" to id(aliasContact)))
        val unrelated = rig.application(rig.listing("Kotlin channels engineer; semantic similarity is not identity"))
        val matches = rig.store.collisions(id(application))
        assertTrue(matches.view.any { it["id"] == id(duplicate) && it["reason"] == "same-employer-requisition" })
        assertTrue(matches.view.any { it["id"] == id(prior) })
        assertTrue(matches.view.any { it["id"] == id(representation) && it["reason"] == "employer-representation" })
        assertTrue(matches.view.any { it["id"] == id(action) })
        assertTrue(matches.view.any { it["id"] == id(sameMailbox) && it["reason"] == "same-contact-email" })
        assertFalse(matches.view.any { it["id"] == id(unrelated) })
        for (match in matches.view) {
            val proof = (match["evidence"] as List<*>).first() as HeadhunterRecord
            for (ref in HeadhunterStore.references(proof).view) assertNotNull(rig.store.version(ref.a, ref.b))
        }
        rig.save("representation", fields(representation) + ("status" to "released"), representation)
        assertFalse(rig.store.collisions(id(application)).view.any { it["id"] == id(representation) })
    }

    @Test fun sourceProceduresHaveVersionsButCredentialsNeverEnterTheLedger() = runTest {
        val rig = Rig()
        val procedure = rig.save("source", mapOf("title" to "Careers", "origin" to "https://careers.example", "credentialRef" to "source-credentials/123", "extraction" to mapOf("selector" to "main")))
        val corrected = rig.save("source", fields(procedure) + ("extraction" to mapOf("selector" to "article", "notes" to "Skip unrelated navigation")), procedure)
        assertEquals(cid(procedure), corrected["previousCid"])
        for (secret in s_[
            mapOf("cookies" to "sensitive"),
            mapOf("extraction" to mapOf("headers" to mapOf("Authorization" to "sensitive"))),
            mapOf("extraction" to mapOf("headers" to listOf(mapOf("name" to "Cookie", "value" to "sensitive")))),
            mapOf("url" to "https://user:password@careers.example"),
            mapOf("url" to "https://careers.example?access_token=sensitive"),
        ].view) assertFailsWith<IllegalArgumentException> { rig.save("source", fields(corrected) + secret, corrected) }
        assertEquals(2, rig.log.committed)
        assertFalse(rig.log.frames.snapshot().view.any { "sensitive" in it.b.decodeToString() })
        val second = Rig(rig.cas, rig.log)
        second.store.restore()
        assertEquals(fields(corrected), fields(second.store.record(id(procedure))!!))
    }

    @Test fun laterApplicationAndContactEditsDoNotMoveRecordedActionsToANewRecipientOrListing() = runTest {
        val rig = Rig()
        val listing = rig.listing(extra = mapOf("url" to "https://example.test/roles/42"))
        val contact = rig.save("contact", mapOf("email" to "first@example.test"))
        val application = rig.application(listing, mapOf("contactId" to id(contact)))
        val action = rig.save("action", mapOf("applicationId" to id(application), "type" to "email", "status" to "reported", "notes" to "User reports contact"))
        assertEquals(cid(application), fields(action)["applicationCid"])
        assertEquals(cid(contact), fields(action)["contactCid"])
        assertEquals(cid(listing), fields(action)["listingCid"])
        val mirror = rig.listing("Mirrored description", mapOf("url" to "https://example.test/roles/42"))
        rig.save("contact", fields(contact) + ("email" to "second@example.test"), contact)
        rig.save("listing", fields(listing) + ("url" to "https://example.test/roles/updated"), listing)
        rig.save("application", fields(application) + ("listingId" to id(rig.listing("Unrelated opening"))), application)
        val prospective = rig.application(mirror, mapOf("notes" to "A separate attempt"))
        assertTrue(rig.store.collisions(id(prospective)).view.any { it["id"] == id(action) })
        val corrected = rig.save("action", fields(action) + ("notes" to "User corrected the reported date"), action)
        assertEquals(cid(application), fields(corrected)["applicationCid"])
        assertEquals(cid(contact), fields(corrected)["contactCid"])
        assertEquals(cid(listing), fields(corrected)["listingCid"])
    }

    @Test fun uncertainFlushStopsWritesAndRecoveryUsesTheDurableCommit() = runTest {
        for (commitBeforeFailure in s_[false, true].view) {
            val rig = Rig()
            val original = rig.evidence("Before")
            rig.log.failFlush = true
            rig.log.commitBeforeFailure = commitBeforeFailure
            assertFailsWith<IllegalStateException> { rig.save("evidence", fields(original) + ("text" to "After"), original) }
            assertEquals(original, rig.board.get("headhunter/${id(original)}"), "Uncertain writes are not published")
            assertFailsWith<IllegalStateException> { rig.evidence("Do not append across the uncertain frame") }
            rig.log.crash()
            val reopened = Rig(rig.cas, rig.log)
            reopened.store.restore()
            val head = reopened.store.record(id(original))!!
            assertEquals(if (commitBeforeFailure) "After" else "Before", fields(head)["text"])
            assertEquals(if (commitBeforeFailure) 2 else 1, reopened.store.history(id(original)).size)
            val after = reopened.save("evidence", fields(head) + ("text" to "Recovered"), head)
            assertEquals(cid(head), after["previousCid"])
        }
    }

    @Test fun missingOrMismatchedVersionsDoNotPartiallyPublishRecovery() = runTest {
        val missing = object : CasStore() {
            var absent: String? = null
            override fun get(cid: ContentId): ByteArray? = if (cid.value == absent) null else super.get(cid)
        }
        val rig = Rig(missing)
        rig.evidence("First record")
        val last = rig.evidence("Second record")
        missing.absent = cid(last)
        val reopened = Rig(missing, rig.log)
        assertFailsWith<IllegalStateException> { reopened.store.restore() }
        assertEquals(0, reopened.board.keys().size, "A failed replay does not advertise a partial recovered board")
        missing.absent = null
        assertEquals(2, reopened.store.restore())
        val body = mapOf("id" to id(last), "kind" to "evidence", "previousCid" to null, "updatedAtMs" to 100, "fields" to fields(last))
        val broken = missing.put(HeadhunterStore.canonical(body).encodeToByteArray())
        rig.log.append(3, HeadhunterStore.canonical(mapOf("id" to id(last), "cid" to broken.value)).encodeToByteArray())
        rig.log.flush()
        val brokenReplay = Rig(missing, rig.log)
        assertFailsWith<IllegalStateException> { brokenReplay.store.restore() }
        assertEquals(0, brokenReplay.board.keys().size)
    }

    companion object {
        fun id(record: HeadhunterRecord) = record["id"] as String
        fun cid(record: HeadhunterRecord) = record["cid"] as String
        fun fields(record: HeadhunterRecord) = HeadhunterStore.fieldsOf(record)
    }
}
