package org.xvm.cursor

import borg.trikeshed.lib.ReduxMutableSeries
import borg.trikeshed.lib.Series
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.xvm.runtime.VmPointcutPublisher
import java.nio.file.Files

/**
 * Firehose harness: watch all VmPointcutPublisher mutable state via a
 * ReduxMutableSeries flush path.  Contract: published == drained == decoded.
 */
class PointcutFirehoseTest {

    @BeforeEach
    fun setUp() {
        VmPointcutPublisher.reset()
    }

    @AfterEach
    fun tearDown() {
        VmPointcutPublisher.reset()
    }

    // ── PointcutHarness construction ────────────────────────────────────

    @Test
    fun `harness creates with ReduxMutableSeries backing`() {
        val harness = PointcutHarness()
        assertNotNull(harness.series)
    }

    @Test
    fun `harness series starts empty`() {
        val harness = PointcutHarness()
        assertEquals(0, harness.series.a)
    }

    // ── subscribe / unsubscribe ─────────────────────────────────────────

    @Test
    fun `harness subscribe returns valid sub id`() {
        val harness = PointcutHarness()
        val id = harness.subscribe()
        assertTrue(id >= 0)
        harness.unsubscribe()
    }

    @Test
    fun `harness unsubscribe is idempotent`() {
        val harness = PointcutHarness()
        harness.subscribe()
        harness.unsubscribe()
        harness.unsubscribe()  // second call must not throw
    }

    // ── event capture ───────────────────────────────────────────────────

    @Test
    fun `harness captures single event`() {
        val harness = PointcutHarness()
        harness.subscribe()
        VmPointcutPublisher.active = true
        VmPointcutPublisher.publish(0x10, "pkg.A.method", 1)
        VmPointcutPublisher.active = false
        harness.flush()
        assertEquals(1, harness.capturedCount())
    }

    @Test
    fun `harness captured event matches published opcode`() {
        val harness = PointcutHarness()
        harness.subscribe()
        VmPointcutPublisher.active = true
        VmPointcutPublisher.publish(0xA5, "pkg.B.field", 42)
        VmPointcutPublisher.active = false
        harness.flush()
        val evt = harness.eventAt(0)
        assertEquals(0xA5, evt.opcode)
    }

    // ── lossless contract ───────────────────────────────────────────────

    @Test
    fun `published count equals harness captured count`() {
        val n = 1000
        val harness = PointcutHarness()
        harness.subscribe()
        VmPointcutPublisher.active = true
        repeat(n) { i -> VmPointcutPublisher.publish(0x10, "pkg.C.m$i", i) }
        VmPointcutPublisher.active = false
        harness.flush()
        assertEquals(n, harness.capturedCount(), "published == captured")
    }

    @Test
    fun `captured events preserve seq order`() {
        val n = 500
        val harness = PointcutHarness()
        harness.subscribe()
        VmPointcutPublisher.active = true
        repeat(n) { i -> VmPointcutPublisher.publish(0x14, "pkg.D.run", i) }
        VmPointcutPublisher.active = false
        harness.flush()
        for (i in 0 until n) {
            assertEquals(i, harness.eventAt(i).seq, "seq at $i")
        }
    }

    // ── firehose: beyond RingSeries capacity ────────────────────────────

    @Test
    fun `firehose beyond ring capacity is lossless in harness`() {
        val n = 70_000  // exceeds RingSeries CAP (65536)
        val harness = PointcutHarness()
        harness.subscribe()
        VmPointcutPublisher.active = true
        repeat(n) { i -> VmPointcutPublisher.publish(0x10, "pkg.Fire.hot", i) }
        VmPointcutPublisher.active = false
        harness.flush()
        assertEquals(n, harness.capturedCount(), "harness must capture every event past ring cap")
    }

    @Test
    fun `firehose captured count equals wire record count`() {
        val n = 70_000
        val harness = PointcutHarness()
        harness.subscribe()
        VmPointcutPublisher.active = true
        repeat(n) { i -> VmPointcutPublisher.publish(0x10, "pkg.Fire.wire", i) }
        VmPointcutPublisher.active = false
        harness.flush()
        val wireBytes = harness.drainToWireproto()
        assertEquals(
            n * PointcutHarness.RECORD_SIZE,
            wireBytes.remaining(),
            "wire bytes must encode every captured event",
        )
    }

    @Test
    fun `firehose wire records decode to original seq and addr`() {
        val n = 200
        val harness = PointcutHarness()
        harness.subscribe()
        VmPointcutPublisher.active = true
        repeat(n) { i -> VmPointcutPublisher.publish(0x15, "pkg.Fire.decode", i * 3) }
        VmPointcutPublisher.active = false
        harness.flush()
        val wire = harness.drainToWireproto()
        for (i in 0 until n) {
            val evt = harness.fromWireproto(wire)
            assertEquals(i, evt.seq, "wire seq at $i")
            assertEquals(i * 3, evt.addr, "wire addr at $i")
        }
    }

    @Test
    fun `firehose writes tmpdir isam journal with partition groups`() {
        val n = 256
        val harness = PointcutHarness()
        harness.subscribe()
        VmPointcutPublisher.active = true
        repeat(n) { i -> VmPointcutPublisher.publish(0x10 + (i and 0x0F), "pkg.Fire.tmp", i * 7) }
        VmPointcutPublisher.active = false
        harness.flush()

        val dir = Files.createTempDirectory("pointcut-firehose-isam-")
        val dataFile = harness.writeTmpDirJournal(dir)
        val metaFile = dataFile.resolveSibling(dataFile.fileName.toString() + ".meta")
        val metaLines = Files.readAllLines(metaFile)

        assertTrue(Files.exists(dataFile), "ISAM data file must exist in tmpdir")
        assertTrue(Files.exists(metaFile), "ISAM meta file must exist in tmpdir")
        assertTrue(metaLines.last().contains("0:bytes"), "partition groups must include byte lane")
        assertTrue(metaLines.last().contains("1-3:ints"), "partition groups must include int lane")
        assertEquals(n.toLong() * 21L, Files.size(dataFile), "ISAM row width must match grouped firehose schema")
    }

    // ── reify / state ───────────────────────────────────────────────────

    @Test
    fun `series state after flush equals captured series`() {
        val n = 50
        val harness = PointcutHarness()
        harness.subscribe()
        VmPointcutPublisher.active = true
        repeat(n) { i -> VmPointcutPublisher.publish(0x1C, "pkg.E.go", i) }
        VmPointcutPublisher.active = false
        harness.flush()
        val state: Series<VmPointcutPublisher.PointcutEvent> = harness.reify()
        assertEquals(n, state.a, "reified state size == published")
    }

    @Test
    fun `series reify twice returns same state`() {
        val harness = PointcutHarness()
        harness.subscribe()
        VmPointcutPublisher.active = true
        repeat(10) { i -> VmPointcutPublisher.publish(0x20, "pkg.F.x", i) }
        VmPointcutPublisher.active = false
        harness.flush()
        val s1 = harness.reify()
        val s2 = harness.reify()
        assertEquals(s1.a, s2.a)
    }
}
