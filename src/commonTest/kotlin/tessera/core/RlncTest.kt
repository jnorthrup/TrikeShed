/*
 * Ported from Tessera — https://github.com/3x3xX3N0N/tessera (via jnorthrup/tessera @78bc042).
 * Copyright 2026 3x3xX3N0N. Licensed under the Apache License, Version 2.0; see tessera/NOTICE.md.
 * Changes: Frame.Repair(ByteBuffer) -> RepairSymbol(ByteArray); the threaded soak is not ported (JVM threads).
 */
package tessera.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RlncTest {
    private fun sym(i: Int, n: Int = 16) = ByteArray(n) { (i * 31 + it * 7 + 1).toByte() }

    /**
     * The transport's residual ARQ re-sends a lost source verbatim, and it can arrive after a repair has already made
     * that seq a pivot row with other unknowns still in it. Window 0..7, lost 4, 5, 6: repair A pivots on 4 with 5 and
     * 6 unknown; then source 4 arrives; the repairs that follow must solve 5 and 6 exactly (never a GF multiple).
     */
    @Test fun lateSourceForPivotThenRepairSolvesExactly() {
        var wrong = 0; var missing = 0
        for (seed in 1..64) {
            val enc = RlncEncoder(16, 8); val dec = RlncDecoder(16)
            val syms = (0 until 8).map { sym(it + seed * 8) }
            syms.forEachIndexed { i, s -> enc.push(i.toLong(), s) }
            for (i in listOf(0, 1, 2, 3, 7)) dec.onSource(i.toLong(), syms[i])
            dec.onRepair(enc.repair(seed * 7919))           // unknowns {4, 5, 6}: pivot 4
            assertNull(dec.get(4)); assertNull(dec.get(5)); assertNull(dec.get(6))
            dec.onSource(4, syms[4])                         // the late re-send of the pivot
            assertContentEquals(syms[4], dec.get(4))
            var extra = 0
            while ((dec.get(5) == null || dec.get(6) == null) && extra < 6) dec.onRepair(enc.repair(seed * 104729 + extra++))
            for (i in 5..6) {
                val d = dec.get(i.toLong())
                if (d == null) missing++ else if (!d.contentEquals(syms[i])) wrong++
            }
            assertEquals(0L, dec.rejected); assertEquals(0L, dec.inconsistent, "seed $seed")
        }
        assertEquals(0, wrong, "wrong solves across 64 seeds ($missing missing)")
        assertEquals(0, missing, "unsolved symbols across 64 seeds")
    }

    /** Same shape with the pivot's source arriving while the row still has three other unknowns, then repairs one by one. */
    @Test fun lateSourceWithManyUnknownsLeftRePivotsCleanly() {
        for (seed in 1..32) {
            val n = 12
            val enc = RlncEncoder(16, n); val dec = RlncDecoder(16)
            val syms = (0 until n).map { sym(it + seed * 16) }
            syms.forEachIndexed { i, s -> enc.push(i.toLong(), s) }
            val lost = setOf(2L, 5L, 6L, 9L, 10L)
            for (i in 0 until n) if (i.toLong() !in lost) dec.onSource(i.toLong(), syms[i])
            dec.onRepair(enc.repair(seed))                 // pivot 2, unknowns 5 6 9 10 left
            dec.onRepair(enc.repair(seed + 1000))          // pivot 5
            dec.onSource(2, syms[2])                        // late source for the first pivot: row keeps 3 unknowns
            dec.onSource(9, syms[9])                        // and another late source
            var extra = 0
            while (lost.any { dec.get(it) == null } && extra < 8) dec.onRepair(enc.repair(seed + 2000 + extra++))
            for (l in lost) assertContentEquals(syms[l.toInt()], assertNotNull(dec.get(l), "seed $seed seq $l"), "seed $seed seq $l")
            assertEquals(0L, dec.rejected); assertEquals(0L, dec.inconsistent)
        }
    }

    @Test fun validatorRejectsWhatItRefusesAndNothingLeaksIntoOtherRows() {
        val enc = RlncEncoder(16, 8)
        val syms = (0 until 8).map { sym(it) }
        syms.forEachIndexed { i, s -> enc.push(i.toLong(), s) }
        // refuse every solve for seq 5: it must never appear, and 4 must still come out right via a row that had both
        val dec = RlncDecoder(16) { seq, _ -> seq != 5L }
        for (i in listOf(0, 1, 2, 3, 6, 7)) dec.onSource(i.toLong(), syms[i])
        dec.onRepair(enc.repair(1)); dec.onRepair(enc.repair(2))
        assertNull(dec.get(5), "a refused solve is not learned")
        assertTrue(dec.rejected >= 1, "rejected=${dec.rejected}")
        dec.get(4)?.let { assertContentEquals(syms[4], it) }
        // the accepting validator sees the real symbols
        val ok = RlncDecoder(16) { seq, s -> s.contentEquals(syms[seq.toInt()]) }
        for (i in listOf(0, 1, 2, 3, 6, 7)) ok.onSource(i.toLong(), syms[i])
        ok.onRepair(enc.repair(1)); ok.onRepair(enc.repair(2))
        assertContentEquals(syms[4], ok.get(4)); assertContentEquals(syms[5], ok.get(5)); assertEquals(0L, ok.rejected)
    }

    @Test fun contradictoryRepairIsCountedAsInconsistent() {
        val enc = RlncEncoder(16, 8)
        val syms = (0 until 8).map { sym(it + 100).also { s -> for (i in 10 until 16) s[i] = 0 } }   // zero tails, as the transport's symbols
        syms.forEachIndexed { i, s -> enc.push(i.toLong(), s) }
        val dec = RlncDecoder(16)
        syms.forEachIndexed { i, s -> dec.onSource(i.toLong(), s) }
        dec.onRepair(enc.repair(3))
        assertEquals(0L, dec.inconsistent, "a redundant repair over known symbols reduces to zero")
        val r = enc.repair(4)
        val bad = r.symbol.copyOf(); bad[7] = (bad[7].toInt() xor 1).toByte()
        dec.onRepair(RepairSymbol(r.windowBase, r.windowLen, r.seed, bad))
        assertEquals(1L, dec.inconsistent, "a corrupted repair contradicts the known symbols")
        // a repair with a trimmed symbol (the transport trims trailing zeros) is still consistent
        val t = enc.repair(5)
        dec.onRepair(RepairSymbol(t.windowBase, t.windowLen, t.seed, t.symbol.copyOf(10)))
        assertEquals(1L, dec.inconsistent)
    }

    @Test fun windowOfOneAndEmptyWindowRepairs() {
        val enc = RlncEncoder(16, 1); val dec = RlncDecoder(16)
        val empty = enc.repair(9)
        assertEquals(0, empty.windowLen); dec.onRepair(empty)
        for (i in 0 until 5) {
            enc.push(i.toLong(), sym(i))
            val r = enc.repair(10 + i)
            assertEquals(i.toLong(), r.windowBase); assertEquals(1, r.windowLen)
            dec.onRepair(r)
            assertContentEquals(sym(i), dec.get(i.toLong()), "window of one: seq $i from its repair alone")
        }
        assertEquals(0L, dec.inconsistent); assertEquals(0L, dec.rejected)
    }

    private fun check(label: String, r: RlncHarness.Result) {
        println("$label: $r")
        assertEquals(0L, r.wrong, "$label: wrong solves — $r")
        assertEquals(0L, r.inconsistent, "$label: inconsistent repairs — $r")
        assertEquals(0L, r.rejected, "$label: rejected solves — $r")
        assertTrue(r.decoded > 2_000, "$label: the soak should decode thousands of symbols, got ${r.decoded}")
    }

    @Test fun soak200kScalar() = check("scalar", RlncHarness.run(RlncHarness.Config(seed = 1)))

    @Test fun soak200kScalarHighLossReorder() =
        check("scalar 15% loss", RlncHarness.run(RlncHarness.Config(seed = 2, loss = 0.15, reorder = 0.1, reorderDepth = 32, resendDelay = 1..160)))

    @Test fun soak200kScalarValidated() = check("scalar validated", RlncHarness.run(RlncHarness.Config(seed = 3, validate = true)))

    @Test fun soak200kSwarKernel() {
        // the same soak with the autovec kernel installed: the algebra must not notice which kernel ran
        GF256.use(GF256.Swar)
        try { check("swar", RlncHarness.run(RlncHarness.Config(seed = 4))) } finally { GF256.useScalar() }
    }

    @Test fun soak200kScalarKernel() {
        GF256.useScalar()
        try { check("scalar oracle", RlncHarness.run(RlncHarness.Config(seed = 5))) } finally { GF256.useScalar() }
    }
}
