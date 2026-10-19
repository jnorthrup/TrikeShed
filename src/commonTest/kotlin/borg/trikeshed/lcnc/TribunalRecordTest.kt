package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TribunalRecordTest {
    @Test fun retainedBytesPrecedeLifecycleAndReport() = runTest {
        var synced = false
        val cas = object : CasStore() { override fun sync() { synced = true } }
        val record = tribunalRecord(cas) { cid ->
            assertTrue(synced)
            assertEquals("synthetic verdict", cas.get(cid)!!.decodeToString())
            "lifecycle-receipt"
        }
        val runner = TribunalNodes.registry(TribunalDialog { _, _, _ -> error("no model") }, record).getValue("tribunal.record")
        val report = runner.run(LcncNode("record", "tribunal.record"), mapOf("text?" to "synthetic verdict"))["report"] as Map<*, *>
        assertEquals("lifecycle-receipt", report["recorded"])
        assertEquals("synthetic verdict", cas.get(ContentId(report["cid"] as String))!!.decodeToString())
    }

    @Test fun missingOrCorruptRetentionCannotCompleteLifecycle() = runTest {
        for (cas in listOf(
            object : CasStore() { override fun get(cid: ContentId): ByteArray? = null },
            object : CasStore() { override fun get(cid: ContentId): ByteArray = "different".encodeToByteArray() },
            object : CasStore() { override fun put(bytes: ByteArray): ContentId = ContentId.of("wrong".encodeToByteArray()) },
        )) {
            var completed = false
            val record = tribunalRecord(cas) { completed = true; it.value }
            assertFailsWith<IllegalStateException> { record("synthetic verdict") }
            assertFalse(completed)
        }
    }

    @Test fun unboundOrBlankRecordRefusesAndCancellationPropagates() = runTest {
        val runner = TribunalNodes.registry(TribunalDialog { _, _, _ -> error("no model") }).getValue("tribunal.record")
        val node = LcncNode("record", "tribunal.record")
        assertFailsWith<IllegalStateException> { runner.run(node, mapOf("text" to "verdict")) }
        assertFailsWith<IllegalArgumentException> { runner.run(node, mapOf("text" to " ")) }
        val record = tribunalRecord(CasStore.inMemory()) { throw CancellationException("fixture") }
        assertFailsWith<CancellationException> { record("verdict") }
    }

    @Test fun legacyInstanceReplaysOnlyItsOwnVerdictAndRejectsAnother() = runTest {
        val instance = TribunalInstance.open(this, TribunalInstance.schemaPlan(), LcncPresets.all().getValue("preset-tribunal"))
        try {
            instance.awaitRootSeeds()
            val cid = ContentId.of("verdict".encodeToByteArray())
            val receipt = instance.record(cid)
            assertEquals("closed", instance.lifecycle("deliberate"))
            assertEquals(receipt, instance.record(cid))
            assertEquals(2L, instance.revision("deliberate"))
            assertFailsWith<IllegalStateException> { instance.record(ContentId.of("other verdict".encodeToByteArray())) }
            assertEquals(2L, instance.revision("deliberate"))
        } finally { instance.nexus.drain() }
    }
}
