package borg.trikeshed.job

import borg.trikeshed.collections.associative.Item
import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.util.toLowerHex
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** State heads must survive the actual raw-command, receipt, canonical and repository boundaries. */
@OptIn(ExperimentalCoroutinesApi::class)
class JobStateTest {
    private val id = JobId.of("case-a")
    private val initial = CanonicalCbor.encodeMap(mapOf("phase" to "idle", "lane" to "brief"))
    private val admitted = CanonicalCbor.encodeMap(mapOf("phase" to "admitted", "lane" to "argue", "claimId" to 17L))
    private val completed = CanonicalCbor.encodeMap(mapOf("phase" to "completed", "lane" to "argue", "claimId" to 17L))

    private fun legacyFields(): Map<String, Any?> = mapOf(
        "jobId" to mapOf("value" to id.value), "revision" to 2L,
        "causalKey" to "case", "lifecycle" to "active", "dependencies" to emptyList<Any>(),
        "attemptCount" to 1, "parentJobId" to null, "attemptId" to "",
    )

    private fun rawState(key: String, revision: Any?, prior: Any?, next: Any?): Map<String, Any?> = mapOf(
        "operation" to "state", "jobId" to id.value, "idempotencyKey" to key,
        "expectedRevision" to revision, "priorStateCid" to prior, "stateCid" to next,
    )

    private fun stateCommand(key: String, revision: Long, prior: String?, next: String): ByteArray =
        CanonicalCbor.encodeMap(mapOf(
            "type" to "borg.trikeshed.job.JobCommand.State",
            "value" to mapOf("jobId" to mapOf("value" to id.value), "idempotencyKey" to key,
                "expectedRevision" to revision, "priorStateCid" to prior, "stateCid" to next),
        ))

    private fun activeLog(log: JobLog = JobLog.inMemory()): JobLog = log.also {
        // Replay seeds an actually active case without racing a live automatic Start.
        it.append(1L, CanonicalCbor.encode(JobCommand.Submit(id, "submit")))
        it.append(2L, CanonicalCbor.encode(JobCommand.Start(id, "start", 1L)))
    }

    private fun head(nexus: JobSupervisorElement): Any? =
        CanonicalCbor.decodeMap(CanonicalCbor.encode(assertNotNull(nexus.snapshot(id))))["stateCid"]

    private suspend fun close(nexus: JobSupervisorElement) {
        if (nexus.rootJob.isActive) nexus.drain() else nexus.cancel()
    }

    @Test fun finalSnapshotBytesMustStillMatchAfterSynchronizationBeforeReceiptPublication() = runTest {
        val reference = ContentId.of(initial)
        for (corrupt in listOf(false, true)) {
            var armed = false
            var stateSnapshotCid: ContentId? = null
            var invalid = false
            var failedSnapshotReads = 0
            val cas = object : CasStore() {
                override fun put(bytes: ByteArray): ContentId {
                    val cid = super.put(bytes)
                    if (armed && runCatching { CanonicalCbor.decodeMap(bytes)["stateCid"] }.getOrNull() == reference.value)
                        stateSnapshotCid = cid
                    return cid
                }
                override fun sync() {
                    super.sync()
                    if (armed && stateSnapshotCid != null) invalid = true
                }
                override fun get(value: ContentId): ByteArray? {
                    if (invalid && value == stateSnapshotCid) {
                        failedSnapshotReads++
                        return if (corrupt) byteArrayOf(2) else null
                    }
                    return super.get(value)
                }
            }
            cas.put(initial)
            val log = activeLog()
            val nexus = JobSupervisorElement.open(this, casStore = cas, jobLog = log)
            try {
                armed = true
                nexus.submitRaw(jsonOf(rawState("snapshot-sync-failed", 2L, null, reference.value)).encodeToByteArray())
                runCurrent()
                val snapshotCid = assertNotNull(stateSnapshotCid, "The fault follows an actual state-bearing snapshot put")
                assertNotEquals(reference, snapshotCid)
                assertContentEquals(initial, cas.get(reference), "The referenced State itself remains valid")
                val failure = nexus.committed.tryReceive()
                assertTrue(failure.isClosed, "A vanished or corrupt snapshot cannot publish Accepted")
                assertContains(assertNotNull(failure.exceptionOrNull()).message.orEmpty(), snapshotCid.value)
                assertTrue(failedSnapshotReads > 0, "Snapshot read-back must follow its own synchronization")
                assertEquals(2L, nexus.snapshot(id)?.revision)
                assertNull(head(nexus))
                assertNull(nexus.receipt("snapshot-sync-failed"))
                assertEquals(3, log.replay().count(), "The State was logged before the final snapshot barrier")
            } finally { armed = false; close(nexus) }
        }
    }

    @Test fun replayCannotReconstructAReceiptFromSnapshotBytesLostAtTheirSynchronization() = runTest {
        val reference = ContentId.of(initial)
        for (corrupt in listOf(false, true)) {
            var stateSnapshotCid: ContentId? = null
            var invalid = false
            var failedSnapshotReads = 0
            val cas = object : CasStore() {
                override fun put(bytes: ByteArray): ContentId {
                    val cid = super.put(bytes)
                    if (runCatching { CanonicalCbor.decodeMap(bytes)["stateCid"] }.getOrNull() == reference.value)
                        stateSnapshotCid = cid
                    return cid
                }
                override fun sync() {
                    super.sync()
                    if (stateSnapshotCid != null) invalid = true
                }
                override fun get(value: ContentId): ByteArray? {
                    if (invalid && value == stateSnapshotCid) {
                        failedSnapshotReads++
                        return if (corrupt) byteArrayOf(3) else null
                    }
                    return super.get(value)
                }
            }
            cas.put(initial)
            val log = activeLog()
            log.append(3L, stateCommand("replayed-snapshot", 2L, null, reference.value))
            val reopened = runCatching { JobSupervisorElement.open(this, casStore = cas, jobLog = log) }
            reopened.getOrNull()?.let { close(it) }
            val snapshotCid = assertNotNull(stateSnapshotCid, "Replay reached actual state-bearing snapshot retention")
            assertNotEquals(reference, snapshotCid)
            assertContentEquals(initial, cas.get(reference), "The referenced State itself remains valid")
            assertContains(assertNotNull(reopened.exceptionOrNull(), "Reopen must fail before exposing a broken receipt").message.orEmpty(), snapshotCid.value)
            assertTrue(failedSnapshotReads > 0, "Reconstructed snapshots need fresh post-sync verification")
        }
    }

    @Test fun ordinarySnapshotAndCommandBytesKeepTheirExistingCids() {
        val snapshot = JobSnapshot(id, 2L, "case", "active", attemptCount = 1)
        val bytes = CanonicalCbor.encode(snapshot)
        assertEquals(
            "a8656a6f624964a16576616c756566636173652d61687265766973696f6e0269617474656d70744964606963617573616c4b65796463617365696c6966656379636c65666163746976656b706172656e744a6f624964f66c617474656d7074436f756e74016c646570656e64656e6369657380",
            bytes.toLowerHex(), "Adding an absent state head must not add a null property",
        )
        assertEquals("sha256:e91109812d20bf813f6601eef372bc1f8eaa43760dc8aabf53dd2c66b1aaf0b1", ContentId.of(bytes).value)
        assertEquals(snapshot, CanonicalCbor.decodeJobSnapshot(bytes))
        assertEquals(
            "sha256:ac393d7f3fd0a1d80b378bd218f54ca2591d8065262ab2fedd7729254c4535e9",
            ContentId.of(CanonicalCbor.encode(JobCommand.Start(id, "start", 1L))).value,
            "Adding a new command subtype must not rename or re-encode existing subtypes",
        )
    }

    @Test fun explicitStateCommandBytesRoundTripBothHeadFields() {
        val cid = ContentId.of(initial).value
        val bytes = stateCommand("initialize", 2L, null, cid)
        val command = CanonicalCbor.decodeJobCommand(bytes)
        assertEquals("state", command.operationName)
        assertContentEquals(bytes, CanonicalCbor.encode(command))
        val fields = assertIs<Map<*, *>>(CanonicalCbor.decodeMap(CanonicalCbor.encode(command))["value"])
        assertTrue(fields.containsKey("priorStateCid"), "Initialization explicitly declares the absent prior head")
        assertNull(fields["priorStateCid"])
        assertEquals(cid, fields["stateCid"])
    }

    @Test fun suppliedStateWalCannotCoerceMalformedRevisionFieldsDuringReopen() = runTest {
        val reference = ContentId.of(initial)
        fun payload(revision: Any?): ByteArray = CanonicalCbor.encodeMap(mapOf(
            "type" to "borg.trikeshed.job.JobCommand.State",
            "value" to mapOf("jobId" to mapOf("value" to id.value), "idempotencyKey" to "malformed-wal",
                "expectedRevision" to revision, "priorStateCid" to null, "stateCid" to reference.value),
        ))
        val canonical = payload(2L)
        val key = "expectedRevision".encodeToByteArray()
        val offset = (0..canonical.size - key.size).single { index ->
            key.indices.all { canonical[index + it] == key[it] }
        } + key.size
        assertEquals(2, canonical[offset].toInt(), "The unsigned-overflow fixture replaces the actual revision value")
        // CBOR uint64 2^63, expressed as bytes to avoid Long overflow or JS numeric rounding.
        val unsignedOverflow = canonical.copyOfRange(0, offset) +
            byteArrayOf(0x1b, 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0) +
            canonical.copyOfRange(offset + 1, canonical.size)
        // Item keeps the CBOR numeric type explicit where JS erases Int/Double runtime tests.
        val fraction = payload(Item.Flt(2.5))
        val floatingOverflow = payload(Item.Flt(9223372036854775808.0))
        assertEquals(0xfb.toByte(), fraction[offset], "The malformed revision is an actual CBOR float")
        assertEquals(0xfb.toByte(), floatingOverflow[offset], "Overflow is an actual CBOR float")
        assertEquals(2.5, (CanonicalCbor.decodeMap(fraction)["value"] as Map<*, *>)["expectedRevision"])
        for ((name, bytes) in listOf(
            "string" to payload("2"), "fraction" to fraction,
            "floating-overflow" to floatingOverflow, "unsigned-overflow" to unsignedOverflow,
        )) {
            val cas = CasStore.inMemory()
            cas.put(initial)
            val log = activeLog()
            log.append(3L, bytes)
            val reopened = runCatching { JobSupervisorElement.open(this, casStore = cas, jobLog = log) }
            reopened.getOrNull()?.let { close(it) }
            assertNotNull(reopened.exceptionOrNull(),
                "$name: malformed State WAL fields must fail before accepting or silently discarding that record")
            assertContentEquals(initial, cas.get(reference), "$name: the retained target is valid")
            assertEquals(3, log.replay().count())
        }
    }

    @Test fun snapshotCodecRetainsAPresentStateHeadWithoutChangingOtherFields() {
        val cid = ContentId.of(initial).value
        val fields = legacyFields() + ("stateCid" to cid)
        val bytes = CanonicalCbor.encodeMap(fields)
        val snapshot = CanonicalCbor.decodeJobSnapshot(bytes)
        assertEquals("active", snapshot.lifecycle)
        assertEquals(2L, snapshot.revision)
        assertContentEquals(bytes, CanonicalCbor.encode(snapshot))
        assertEquals(cid, CanonicalCbor.decodeMap(CanonicalCbor.encode(snapshot))["stateCid"])
    }

    @Test fun rawStateAdmissionAndCompletionCommitTheActualHeadAndReplayIt() = runTest {
        val cas = CasStore.inMemory()
        val log = activeLog()
        val nexus = JobSupervisorElement.open(this, casStore = cas, jobLog = log)
        val heads = listOf(initial, admitted, completed).map { bytes -> cas.put(bytes).also { cas.sync() } }
        var prior: String? = null
        var revision = 2L
        var lastReceipt: ContentId? = null
        try {
            for ((index, cid) in heads.withIndex()) {
                val key = "state-$index"
                nexus.submitRaw(jsonOf(rawState(key, revision, prior, cid.value)).encodeToByteArray())
                runCurrent()
                assertIs<JobEvent.Accepted>(nexus.committed.tryReceive().getOrNull(),
                    "Raw State must enter the real command channel, not disappear at lowering")
                revision++
                assertEquals(revision, nexus.snapshot(id)?.revision)
                assertEquals("active", nexus.snapshot(id)?.lifecycle, "A stage is not another lifecycle Start")
                assertEquals(cid.value, head(nexus))
                val receipt = assertNotNull(nexus.receipt(key))
                val snapshot = CanonicalCbor.decodeJobSnapshot(assertNotNull(cas.get(receipt)))
                assertEquals(cid.value, CanonicalCbor.decodeMap(CanonicalCbor.encode(snapshot))["stateCid"])
                assertContentEquals(listOf(initial, admitted, completed)[index], cas.get(cid))
                assertNotEquals(lastReceipt, receipt)
                lastReceipt = receipt
                prior = cid.value
            }
        } finally { close(nexus) }
        val reopened = JobSupervisorElement.open(this, casStore = cas, jobLog = log)
        try {
            assertEquals(5L, reopened.snapshot(id)?.revision)
            assertEquals("active", reopened.snapshot(id)?.lifecycle)
            assertEquals(heads.last().value, head(reopened))
            assertEquals(lastReceipt, reopened.receipt("state-2"))
        } finally { close(reopened) }
    }

    @Test fun staleRevisionWrongPriorHeadAndTerminalWritesCannotReplaceTheCommittedHead() = runTest {
        val cas = CasStore.inMemory()
        val first = cas.put(initial)
        val next = cas.put(admitted)
        val nexus = JobSupervisorElement.open(this, casStore = cas, jobLog = activeLog())
        try {
            nexus.submitRaw(jsonOf(rawState("initialize", 2L, null, first.value)).encodeToByteArray())
            runCurrent()
            assertIs<JobEvent.Accepted>(nexus.committed.tryReceive().getOrNull())
            for ((key, revision, prior) in listOf(
                Triple("stale", 2L, first.value), Triple("wrong-head", 3L, null),
            )) {
                nexus.submitRaw(jsonOf(rawState(key, revision, prior, next.value)).encodeToByteArray())
                runCurrent()
                assertIs<JobEvent.Rejected>(nexus.committed.tryReceive().getOrNull(), key)
                assertEquals(3L, nexus.snapshot(id)?.revision)
                assertEquals(first.value, head(nexus))
                assertNull(nexus.receipt(key))
            }
            nexus.submit(JobCommand.Complete(id, "terminal", 3L))
            runCurrent()
            assertIs<JobEvent.Accepted>(nexus.committed.tryReceive().getOrNull())
            assertEquals(first.value, head(nexus), "A terminal snapshot still identifies its case evidence")
            nexus.submitRaw(jsonOf(rawState("revive", 4L, first.value, next.value)).encodeToByteArray())
            runCurrent()
            assertIs<JobEvent.Rejected>(nexus.committed.tryReceive().getOrNull())
            assertEquals(4L, nexus.snapshot(id)?.revision)
            assertEquals("closed", nexus.snapshot(id)?.lifecycle)
            assertEquals(first.value, head(nexus))
        } finally { close(nexus) }
    }

    @Test fun rejectedStateKeysCannotBeReusedForCorrectedRequestsLiveOrAfterReplay() = runTest {
        val cas = CasStore.inMemory()
        val first = cas.put(initial)
        val next = cas.put(admitted)
        val log = activeLog()
        val nexus = JobSupervisorElement.open(this, casStore = cas, jobLog = log)
        val rejected = listOf(
            rawState("stale-key", 2L, first.value, next.value),
            rawState("prior-key", 3L, null, next.value),
        )
        try {
            nexus.submitRaw(jsonOf(rawState("initialize", 2L, null, first.value)).encodeToByteArray())
            runCurrent()
            assertIs<JobEvent.Accepted>(nexus.committed.tryReceive().getOrNull())
            for (request in rejected) {
                nexus.submitRaw(jsonOf(request).encodeToByteArray())
                runCurrent()
                assertIs<JobEvent.Rejected>(nexus.committed.tryReceive().getOrNull())
                val key = request["idempotencyKey"] as String
                nexus.submitRaw(jsonOf(rawState(key, 3L, first.value, next.value)).encodeToByteArray())
                runCurrent()
                assertContains(assertIs<JobEvent.Rejected>(nexus.committed.tryReceive().getOrNull()).reason, "duplicate")
                assertEquals(3L, nexus.snapshot(id)?.revision)
                assertEquals(first.value, head(nexus))
                assertNull(nexus.receipt(key))
            }
        } finally { close(nexus) }
        val reopened = JobSupervisorElement.open(this, casStore = cas, jobLog = log)
        try {
            for (request in rejected) {
                val key = request["idempotencyKey"] as String
                reopened.submitRaw(jsonOf(rawState(key, 3L, first.value, next.value)).encodeToByteArray())
                runCurrent()
                assertContains(assertIs<JobEvent.Rejected>(reopened.committed.tryReceive().getOrNull()).reason, "duplicate")
                assertEquals(3L, reopened.snapshot(id)?.revision)
                assertEquals(first.value, head(reopened))
                assertNull(reopened.receipt(key))
            }
        } finally { close(reopened) }
    }

    @Test fun exactNegativeZeroUsesTheSameRevisionValueAsOtherNumericZeroForms() = runTest {
        val cas = CasStore.inMemory()
        val cid = cas.put(initial).value
        val nexus = JobSupervisorElement.open(this, casStore = cas, jobLog = activeLog())
        try {
            for ((index, zero) in listOf("-0", "-0.0", "-0e0", "0", "0.0", "0e5").withIndex()) {
                val text = """{"operation":"state","jobId":"case-a","idempotencyKey":"zero-$index","expectedRevision":$zero,"priorStateCid":null,"stateCid":"$cid"}"""
                nexus.submitRaw(text.encodeToByteArray())
                runCurrent()
                val refused = assertIs<JobEvent.Rejected>(nexus.committed.tryReceive().getOrNull())
                assertContains(refused.reason, "expected 0, actual 2", message = "The input lowered to exact zero: $zero")
                assertEquals(2L, nexus.snapshot(id)?.revision)
                assertNull(head(nexus))
            }
        } finally { close(nexus) }
    }

    @Test fun duplicateStateKeepsItsOriginalReceiptAfterLaterHeadsAndReopen() = runTest {
        val cas = CasStore.inMemory()
        val first = cas.put(initial)
        val second = cas.put(admitted)
        val different = cas.put(completed)
        val log = activeLog()
        val nexus = JobSupervisorElement.open(this, casStore = cas, jobLog = log)
        var firstReceipt: ContentId? = null
        var secondReceipt: ContentId? = null
        try {
            val original = rawState("head-a", 2L, null, first.value)
            nexus.submitRaw(jsonOf(original).encodeToByteArray())
            runCurrent()
            assertIs<JobEvent.Accepted>(nexus.committed.tryReceive().getOrNull())
            firstReceipt = assertNotNull(nexus.receipt("head-a"))
            nexus.submitRaw(jsonOf(rawState("head-b", 3L, first.value, second.value)).encodeToByteArray())
            runCurrent()
            assertIs<JobEvent.Accepted>(nexus.committed.tryReceive().getOrNull())
            secondReceipt = assertNotNull(nexus.receipt("head-b"))
            assertNotEquals(firstReceipt, secondReceipt)
            for (request in listOf(original, rawState("head-a", 4L, second.value, different.value))) {
                nexus.submitRaw(jsonOf(request).encodeToByteArray())
                runCurrent()
                val refused = assertIs<JobEvent.Rejected>(nexus.committed.tryReceive().getOrNull())
                assertContains(refused.reason, "duplicate")
                assertEquals(4L, nexus.snapshot(id)?.revision)
                assertEquals(second.value, head(nexus))
                assertEquals(firstReceipt, nexus.receipt("head-a"), "A request key cannot be rebound to a later head")
            }
        } finally { close(nexus) }
        val reopened = JobSupervisorElement.open(this, casStore = cas, jobLog = log)
        try {
            assertEquals(4L, reopened.snapshot(id)?.revision)
            assertEquals(second.value, head(reopened))
            assertEquals(firstReceipt, reopened.receipt("head-a"))
            assertEquals(secondReceipt, reopened.receipt("head-b"))
            val original = CanonicalCbor.decodeJobSnapshot(assertNotNull(cas.get(assertNotNull(firstReceipt))))
            assertEquals(3L, original.revision)
            assertEquals(first.value, CanonicalCbor.decodeMap(CanonicalCbor.encode(original))["stateCid"])
        } finally { close(reopened) }
    }

    @Test fun stateCasSyncFailureCannotPublishOrAppendTheProposedHead() = runTest {
        var armed = false
        val cas = object : CasStore() {
            override fun sync() {
                if (armed) error("state CAS sync refused")
                super.sync()
            }
        }
        val cid = cas.put(initial)
        val log = activeLog()
        val nexus = JobSupervisorElement.open(this, casStore = cas, jobLog = log)
        try {
            armed = true
            nexus.submitRaw(jsonOf(rawState("sync-failed", 2L, null, cid.value)).encodeToByteArray())
            runCurrent()
            val failure = nexus.committed.tryReceive()
            assertTrue(failure.isClosed)
            assertContains(assertNotNull(failure.exceptionOrNull()).message.orEmpty(), "state CAS sync refused")
            assertEquals(2L, nexus.snapshot(id)?.revision)
            assertNull(head(nexus))
            assertNull(nexus.receipt("sync-failed"))
            assertEquals(2, log.replay().count())
        } finally { armed = false; close(nexus) }
    }

    @Test fun stateBytesMustStillMatchAfterTheSynchronizationBarrier() = runTest {
        val cid = ContentId.of(initial)
        for (corrupt in listOf(false, true)) {
            var armed = false
            var synchronized = false
            var preSyncReads = 0
            var postSyncReads = 0
            val cas = object : CasStore() {
                override fun sync() {
                    super.sync()
                    if (armed) synchronized = true
                }
                override fun get(value: ContentId): ByteArray? {
                    if (value != cid || !armed) return super.get(value)
                    return if (synchronized) {
                        postSyncReads++
                        if (corrupt) byteArrayOf(5) else null
                    } else {
                        preSyncReads++
                        initial.copyOf()
                    }
                }
            }
            cas.put(initial)
            val log = activeLog()
            val nexus = JobSupervisorElement.open(this, casStore = cas, jobLog = log)
            try {
                armed = true
                nexus.submitRaw(jsonOf(rawState("post-sync-failed", 2L, null, cid.value)).encodeToByteArray())
                runCurrent()
                val failure = nexus.committed.tryReceive()
                assertTrue(failure.isClosed)
                assertContains(assertNotNull(failure.exceptionOrNull()).message.orEmpty(), cid.value)
                assertTrue(preSyncReads > 0, "The referenced bytes existed and matched before synchronization")
                assertTrue(postSyncReads > 0, "A fresh read-back must follow synchronization")
                assertEquals(2L, nexus.snapshot(id)?.revision)
                assertNull(head(nexus))
                assertNull(nexus.receipt("post-sync-failed"))
                assertEquals(2, log.replay().count())
            } finally { armed = false; close(nexus) }
        }
    }

    @Test fun rejectedLoggedStatesDoNotRequireTheirUnusedTargetBlobsDuringReplay() = runTest {
        val first = ContentId.of(initial)
        val absent = ContentId.of(admitted)
        var unusedReads = 0
        val cas = object : CasStore() {
            override fun get(value: ContentId): ByteArray? {
                if (value == absent) { unusedReads++; return null }
                return super.get(value)
            }
        }
        cas.put(initial)
        val log = activeLog()
        log.append(3L, stateCommand("head-a", 2L, null, first.value))
        log.append(4L, stateCommand("stale", 2L, first.value, absent.value))
        log.append(5L, stateCommand("wrong-prior", 3L, null, absent.value))
        log.append(6L, stateCommand("head-a", 3L, first.value, absent.value))
        val nexus = JobSupervisorElement.open(this, casStore = cas, jobLog = log)
        try {
            assertEquals(3L, nexus.snapshot(id)?.revision)
            assertEquals(first.value, head(nexus))
            assertNotNull(nexus.receipt("head-a"))
            assertNull(nexus.receipt("stale"))
            assertNull(nexus.receipt("wrong-prior"))
            assertEquals(0, unusedReads, "Only accepted State references become retained head obligations")
        } finally { close(nexus) }
    }

    @Test fun finalSnapshotSyncCannotPublishAHeadWhoseReferencedStateWasLost() = runTest {
        val reference = ContentId.of(initial)
        for (corrupt in listOf(false, true)) {
            var armed = false
            var stateSnapshotPut = false
            var invalid = false
            val cas = object : CasStore() {
                override fun put(bytes: ByteArray): ContentId {
                    val cid = super.put(bytes)
                    if (armed && runCatching { CanonicalCbor.decodeMap(bytes)["stateCid"] }.getOrNull() == reference.value)
                        stateSnapshotPut = true
                    return cid
                }
                override fun sync() {
                    super.sync()
                    if (armed && stateSnapshotPut) invalid = true
                }
                override fun get(value: ContentId): ByteArray? =
                    if (value == reference && invalid) if (corrupt) byteArrayOf(4) else null else super.get(value)
            }
            cas.put(initial)
            val log = activeLog()
            val nexus = JobSupervisorElement.open(this, casStore = cas, jobLog = log)
            try {
                armed = true
                nexus.submitRaw(jsonOf(rawState("late-state-failure", 2L, null, reference.value)).encodeToByteArray())
                runCurrent()
                val failure = nexus.committed.tryReceive()
                assertTrue(stateSnapshotPut, "The injected failure follows an actual state-bearing snapshot put")
                assertTrue(failure.isClosed)
                assertContains(assertNotNull(failure.exceptionOrNull()).message.orEmpty(), reference.value)
                assertEquals(2L, nexus.snapshot(id)?.revision, "The proposed snapshot was never externally published")
                assertNull(head(nexus))
                assertNull(nexus.receipt("late-state-failure"))
                assertEquals(3, log.replay().count(), "The State command was already logged before the final snapshot barrier")
            } finally { close(nexus) }
            try {
                val reopened = runCatching { JobSupervisorElement.open(this, casStore = cas, jobLog = log) }
                reopened.getOrNull()?.let { close(it) }
                assertContains(assertNotNull(reopened.exceptionOrNull()).message.orEmpty(), reference.value,
                    message = "Reopen cannot turn that interrupted State into a valid retained head")
            } finally { armed = false }
        }
    }

    @Test fun missingOrCorruptReferencedBytesCannotPublishAnAcceptedState() = runTest {
        val cid = ContentId.of(initial)
        for (corrupt in listOf(false, true)) {
            val cas = object : CasStore() {
                override fun get(value: ContentId): ByteArray? =
                    if (value == cid) if (corrupt) byteArrayOf(7) else null else super.get(value)
            }
            val log = activeLog()
            val nexus = JobSupervisorElement.open(this, casStore = cas, jobLog = log)
            try {
                nexus.submitRaw(jsonOf(rawState("unreadable", 2L, null, cid.value)).encodeToByteArray())
                runCurrent()
                val failure = nexus.committed.tryReceive()
                assertTrue(failure.isClosed, "Missing State support is not a successful storage refusal")
                assertContains(assertNotNull(failure.exceptionOrNull()).message.orEmpty(), cid.value)
                assertNull(nexus.receipt("unreadable"))
                assertEquals(2L, nexus.snapshot(id)?.revision)
                assertNull(head(nexus))
                assertEquals(2, log.replay().count(), "A missing/corrupt target must fail before WAL publication")
            } finally { close(nexus) }
        }
    }

    @Test fun stateLogFailureCannotPublishItsRevisionOrHead() = runTest {
        var fail = false
        val log = activeLog(object : JobLog() {
            override fun append(sequence: Long, payload: ByteArray) {
                if (fail) error("state WAL refused")
                super.append(sequence, payload)
            }
        })
        val cas = CasStore.inMemory()
        val cid = cas.put(initial)
        val nexus = JobSupervisorElement.open(this, casStore = cas, jobLog = log)
        try {
            fail = true
            nexus.submitRaw(jsonOf(rawState("not-committed", 2L, null, cid.value)).encodeToByteArray())
            runCurrent()
            val failure = nexus.committed.tryReceive()
            assertTrue(failure.isClosed)
            assertContains(assertNotNull(failure.exceptionOrNull()).message.orEmpty(), "state WAL refused")
            assertEquals(2L, nexus.snapshot(id)?.revision)
            assertNull(head(nexus))
            assertNull(nexus.receipt("not-committed"))
        } finally { close(nexus) }
    }

    @Test fun reopenVerifiesTheAcceptedHeadsActualCasBytes() = runTest {
        val cid = ContentId.of(initial)
        for (corrupt in listOf(false, true)) {
            val cas = object : CasStore() {
                override fun get(value: ContentId): ByteArray? =
                    if (value == cid) if (corrupt) byteArrayOf(9) else null else super.get(value)
            }
            val log = activeLog()
            log.append(3L, stateCommand("recorded-head", 2L, null, cid.value))
            val failure = runCatching { JobSupervisorElement.open(this, casStore = cas, jobLog = log) }
            failure.getOrNull()?.let { close(it) }
            assertContains(assertNotNull(failure.exceptionOrNull()).message.orEmpty(), cid.value,
                message = "Reopen must diagnose the actual referenced blob, not merely an unknown command subtype")
        }
    }

    @Test fun rawStateRejectsMissingTypedFieldsAndInexactRevisionsBeforeEnqueue() = runTest {
        val cas = CasStore.inMemory()
        val cid = cas.put(initial).value
        val valid = rawState("invalid", 2L, null, cid)
        val malformed = listOf(
            valid - "stateCid", valid - "priorStateCid", valid - "expectedRevision",
            valid + ("stateCid" to "sha256:broken"), valid + ("stateCid" to 5),
            valid + ("priorStateCid" to "broken"), valid + ("expectedRevision" to 2.5),
            valid + ("expectedRevision" to "2"), valid + ("expectedRevision" to true),
            valid + ("expectedRevision" to -1L), valid + ("jobId" to 7), valid + ("idempotencyKey" to 8),
        )
        val log = activeLog()
        val nexus = JobSupervisorElement.open(this, casStore = cas, jobLog = log)
        try {
            // A literal decimal prevents JS fixture widening from manufacturing a different input.
            val overflow = """{"operation":"state","jobId":"case-a","idempotencyKey":"overflow","expectedRevision":9223372036854775808,"priorStateCid":null,"stateCid":"$cid"}"""
            for (text in malformed.map(::jsonOf) + overflow) {
                assertFailsWith<IllegalArgumentException> { nexus.submitRaw(text.encodeToByteArray()) }
                runCurrent()
                assertTrue(nexus.committed.tryReceive().isFailure)
                assertEquals(2L, nexus.snapshot(id)?.revision)
                assertNull(head(nexus))
            }
            assertEquals(2, log.replay().count())
        } finally { close(nexus) }
    }

    @Test fun theSchemaPlanRecognizesStateAsAnExplicitCommand() {
        val cid = ContentId.of(initial).value
        val frame = rawState("schema-state", 2L, null, cid) + mapOf(
            "schemaVersion" to "1.0.0", "frameKind" to "command", "workspaceId" to "tribunal",
            "sequence" to 2L, "cid" to ContentId.of(byteArrayOf(1)).value,
            "causalKey" to "tribunal/case-a", "timestampMs" to 0L,
        )
        val plan = borg.trikeshed.lcnc.TribunalInstance.schemaPlan()
        val result = plan.validate(confixDoc(jsonOf(frame)))
        assertTrue(result.valid, "The schema's command contract must serve the typed State operation: ${result.errors}")
        assertFalse(plan.validate(confixDoc(jsonOf(frame - "stateCid"))).valid)
        assertFalse(plan.validate(confixDoc(jsonOf(frame + ("stateCid" to "broken")))).valid)
    }

    private class Log : DurableAppendLog {
        val frames = mutableListOf<Pair<Long, ByteArray>>()
        override fun append(sequence: Long, payload: ByteArray): Long {
            require(sequence > (frames.lastOrNull()?.first ?: 0L))
            frames.add(sequence to payload.copyOf())
            return sequence
        }
        override suspend fun replay(onFrame: suspend (Long, ByteArray) -> Unit): Long {
            for ((sequence, payload) in frames) onFrame(sequence, payload.copyOf())
            return frames.lastOrNull()?.first ?: 0L
        }
        override fun flush() = Unit
        override fun injectCorruptionAfter(sequence: Long) { frames.removeAll { it.first > sequence } }
    }

    /** Frozen repository wire framing; production's private record codec is intentionally not called. */
    private class Bytes {
        private val bytes = mutableListOf<Byte>()
        fun byte(value: Int) { bytes.add(value.toByte()) }
        fun int(value: Int) { for (shift in 24 downTo 0 step 8) byte(value ushr shift) }
        fun long(value: Long) { for (shift in 56 downTo 0 step 8) byte((value ushr shift).toInt()) }
        fun field(value: ByteArray) { int(value.size); bytes.addAll(value.toList()) }
        fun header(version: Int) { byte(0x4A); byte(0x52); byte(0x31); byte(0); int(version) }
        fun result(): ByteArray = bytes.toByteArray()
    }

    @Test fun repositoryCommitRefusesAStateReferenceThatDisappearsAfterSync() = runTest {
        val head = ContentId.of(initial)
        for (corrupt in listOf(false, true)) {
            var synchronized = false
            var preSyncReads = 0
            var postSyncReads = 0
            val cas = object : CasStore() {
                override fun sync() { super.sync(); synchronized = true }
                override fun get(value: ContentId): ByteArray? {
                    if (value != head) return super.get(value)
                    return if (synchronized) {
                        postSyncReads++
                        if (corrupt) byteArrayOf(1) else null
                    } else { preSyncReads++; initial.copyOf() }
                }
            }
            cas.put(initial)
            val snapshot = CanonicalCbor.decodeJobSnapshot(
                CanonicalCbor.encodeMap(legacyFields() + ("stateCid" to head.value)))
            val log = Log()
            val failure = runCatching { JobRepository(log, cas).commit(id, snapshot, byteArrayOf(7)) }.exceptionOrNull()
            assertContains(assertNotNull(failure).message.orEmpty(), head.value)
            assertTrue(preSyncReads > 0)
            assertTrue(postSyncReads > 0, "Repository commit requires a fresh state read after sync")
            assertTrue(log.frames.isEmpty(), "No repository head may be logged after losing its referenced state")
        }
    }

    @Test fun repositoryCommitPropagatesStateSyncFailureBeforeAppending() = runTest {
        val cas = object : CasStore() {
            override fun sync(): Unit = error("repository state sync refused")
        }
        val head = cas.put(initial)
        val snapshot = CanonicalCbor.decodeJobSnapshot(
            CanonicalCbor.encodeMap(legacyFields() + ("stateCid" to head.value)))
        val log = Log()
        val failure = runCatching { JobRepository(log, cas).commit(id, snapshot, byteArrayOf(7)) }.exceptionOrNull()
        assertContains(assertNotNull(failure).message.orEmpty(), "repository state sync refused")
        assertTrue(log.frames.isEmpty())
    }

    @Test fun checkpointHydrationRefusesADanglingStateHead() = runTest {
        val head = ContentId.of(initial)
        for (corrupt in listOf(false, true)) {
            val cas = object : CasStore() {
                override fun get(value: ContentId): ByteArray? =
                    if (value == head) if (corrupt) byteArrayOf(3) else null else super.get(value)
            }
            val bytes = CanonicalCbor.encodeMap(legacyFields() + ("stateCid" to head.value))
            val snapshotCid = cas.put(bytes)
            val tree = borg.trikeshed.collections.btree.CowBPlusTree(cas)
            val rootCid = tree.insert(null,
                borg.trikeshed.collections.btree.BTreeKey("state", byteArrayOf(1), id, 2L),
                borg.trikeshed.collections.btree.BTreeValue(snapshotCid, 2L))
            val schemaCid = cas.put(byteArrayOf(2))
            val log = Log()
            val repository = JobRepository(log, cas)
            repository.checkpoint(JobCheckpoint(1L, rootCid, schemaCid, emptyMap()))
            val failure = runCatching { JobRepository(log, cas).recover() }.exceptionOrNull()
            assertContains(assertNotNull(failure).message.orEmpty(), head.value,
                message = "Checkpoint hydration cannot return a valid case with missing/corrupt state bytes")
        }
    }

    @Test fun repositoryStillReadsTheExistingJr1HeadlessSnapshot() = runTest {
        val snapshot = JobSnapshot(id, 2L, "case", "active", attemptCount = 1)
        val cas = CasStore.inMemory()
        val cid = cas.put(CanonicalCbor.encode(snapshot))
        val v1 = Bytes().apply {
            header(1); field(cid.value.encodeToByteArray()); field(id.value.encodeToByteArray())
            long(2L); field("case".encodeToByteArray()); field("active".encodeToByteArray())
            int(0); int(1); byte(0); field(byteArrayOf()); field(byteArrayOf(4))
        }.result()
        val log = Log().also { it.append(1L, v1) }
        val recovered = JobRepository(log, cas).recover()
        assertEquals(snapshot, recovered.snapshot(id))
        assertEquals(1L, recovered.committedSequence)
    }

    @Test fun repositoryReopenRefusesAValidSnapshotWithMissingOrCorruptStateBytes() = runTest {
        val head = ContentId.of(initial)
        for (corrupt in listOf(false, true)) {
            val cas = object : CasStore() {
                override fun get(value: ContentId): ByteArray? =
                    if (value == head) if (corrupt) byteArrayOf(6) else null else super.get(value)
            }
            val canonical = CanonicalCbor.encodeMap(legacyFields() + ("stateCid" to head.value))
            val cid = cas.put(canonical)
            val frame = Bytes().apply {
                header(2); field(cid.value.encodeToByteArray()); field(canonical); field(byteArrayOf(8))
            }.result()
            val log = Log().also { it.append(1L, frame) }
            val failure = runCatching { JobRepository(log, cas).recover() }.exceptionOrNull()
            assertContains(assertNotNull(failure,
                "A valid snapshot with a dangling head cannot disappear or report successful recovery").message.orEmpty(), head.value)
        }
    }

    @Test fun repositoryV2ReadsAndWritesTheCanonicalSnapshotIncludingItsStateHead() = runTest {
        val cas = CasStore.inMemory()
        val head = cas.put(initial)
        val canonical = CanonicalCbor.encodeMap(legacyFields() + ("stateCid" to head.value))
        val cid = cas.put(canonical)
        val v2 = Bytes().apply {
            header(2); field(cid.value.encodeToByteArray()); field(canonical); field(byteArrayOf(8))
        }.result()
        val log = Log().also { it.append(1L, v2) }
        val repository = JobRepository(log, cas)
        val snapshot = assertNotNull(repository.recover().snapshot(id), "A valid v2 head must not disappear on recovery")
        assertContentEquals(canonical, CanonicalCbor.encode(snapshot))
        repository.commit(id, snapshot, byteArrayOf(9))
        val written = log.frames.last().second
        assertContentEquals(byteArrayOf(0x4A, 0x52, 0x31, 0, 0, 0, 0, 2), written.copyOfRange(0, 8))
        val reopened = JobRepository(log, cas).recover()
        assertEquals(head.value, CanonicalCbor.decodeMap(CanonicalCbor.encode(assertNotNull(reopened.snapshot(id))))["stateCid"])
    }
}
