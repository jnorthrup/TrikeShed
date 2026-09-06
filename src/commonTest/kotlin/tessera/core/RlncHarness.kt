/*
 * Ported from Tessera — https://github.com/3x3xX3N0N/tessera (via jnorthrup/tessera @78bc042).
 * Copyright 2026 3x3xX3N0N. Licensed under the Apache License, Version 2.0; see tessera/NOTICE.md.
 * Changes: Frame.Repair(ByteBuffer) -> RepairSymbol(ByteArray); the threaded soak is not ported (JVM threads).
 */
package tessera.core

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Deterministic soak for an [RlncEncoder]/[RlncDecoder] pair, shaped like the transport's use of them
 * (`TesseraConnection.sendSource` / `sendRepair` / `storeSource` / `onRepair` / `rotateDecoder`):
 *
 *  * symbols are `len(2) | 0x80 0x02 fecSeq16 | seq(8) | random body`, zero padded — the transport's layout, so
 *    [validator] checks exactly what the transport can check on a recovered symbol without a wire change;
 *  * the encoder window slides on every push; its size cycles through [Config.windows] (a fresh encoder per segment,
 *    the decoder carries on — it is window-agnostic);
 *  * repairs are generated proactively (per push, probability [Config.redundancy]) and at random points ("timer"
 *    repairs, [Config.timerRepair]) — in particular right after the push that slid the window;
 *  * sources and repairs are lost independently ([Config.loss]); a lost source is re-sent verbatim later (residual
 *    ARQ, [Config.resendDelay]) and may be lost again; the receiver skips a re-send whose symbol it already has, as
 *    the transport's `isDelivered` does;
 *  * some packets are delivered behind later ones ([Config.reorder] / [Config.reorderDepth]);
 *  * the decoder is rotated every [Config.rotateEvery] seqs with the last [Config.refeed] symbols re-fed, as the
 *    transport does;
 *  * every symbol the decoder produces is compared with the original the first time it is seen; the whole epoch is
 *    re-checked before a decoder is dropped, so no wrong solve can hide.
 *
 * [Config.threads] runs the three transport threads that touch the coders — the sender (push + proactive repairs),
 * the timer (tail repairs) and the rx thread (decoding) — with each coder under its own lock, exactly as the
 * transport serializes a connection under its lock. The thread interleaving is then not deterministic; the loss /
 * reorder pattern still is (it is drawn on the receiver side).
 */
object RlncHarness {
    data class Config(
        /** Source symbols pushed. */
        val symbols: Int = 200_000,
        /** Symbol size; the algebra does not depend on it, 64 keeps the soak fast (>= 16 for the layout above). */
        val symbolSize: Int = 64,
        val seed: Long = 1L,
        /** Independent loss probability per packet (sources, re-sends and repairs alike). */
        val loss: Double = 0.05,
        /** Encoder window sizes, cycled through one per [segment] symbols. */
        val windows: IntRange = 1..64,
        val segment: Int = 2_000,
        /** Proactive repair probability per push (the transport's adaptive redundancy). */
        val redundancy: Double = 0.15,
        /** Extra repair probability per push, standing in for the timer thread's tail / TLP / reactive repairs. */
        val timerRepair: Double = 0.05,
        /** A lost source is re-sent this many packets later (the transport: after RTT + reordering window / PTO). */
        val resendDelay: IntRange = 4..96,
        /** Probability that a packet is delayed behind up to [reorderDepth] later packets. */
        val reorder: Double = 0.02,
        val reorderDepth: Int = 8,
        /** Decoder rotation period and the number of trailing symbols re-fed (the transport: 4096 / fecWindow). */
        val rotateEvery: Long = 4096L,
        val refeed: Int = 64,
        /** Sender / timer / rx threads instead of one deterministic loop (see the class comment). */
        val threads: Boolean = false,
        /** Install [validator] on the decoders (rejected solves are counted in [Result.rejected]). */
        val validate: Boolean = false,
    )

    data class Result(
        val sources: Long, val repairs: Long, val resends: Long, val lost: Long,
        /** Symbols that came out of the decoder (not received as sources). */
        val decoded: Long,
        /** Decoded symbols that differ from the original — a wrong solve. */
        val wrong: Long,
        val firstWrong: String?,
        /** Solves the validator refused (with [Config.validate]) and repairs that contradicted the known symbols. */
        val rejected: Long, val inconsistent: Long,
        val rotations: Long,
    ) {
        override fun toString(): String =
            "sources=$sources repairs=$repairs resends=$resends lost=$lost decoded=$decoded wrong=$wrong rejected=$rejected " +
                "inconsistent=$inconsistent rotations=$rotations" + (firstWrong?.let { " firstWrong: $it" } ?: "")
    }

    /** The original symbol for `seq` under `seed` — regenerated on demand, nothing is stored. */
    fun symbol(seq: Long, symbolSize: Int, seed: Long): ByteArray {
        require(symbolSize >= 16)
        val r = Random(seq * -7046029254386353131L + seed)
        val sym = ByteArray(symbolSize)
        val bodyLen = 12 + r.nextInt(symbolSize - 2 - 12 + 1)
        sym[0] = (bodyLen shr 8).toByte(); sym[1] = bodyLen.toByte()
        sym[2] = 0x80.toByte(); sym[3] = 2; sym[4] = (seq shr 8).toByte(); sym[5] = seq.toByte()
        for (i in 0 until 8) sym[6 + i] = (seq shr (56 - 8 * i)).toByte()
        for (i in 14 until 2 + bodyLen) sym[i] = r.nextInt(256).toByte()
        return sym
    }

    /**
     * What the transport can check on a recovered symbol with no wire change: the length prefix is in range and the
     * body starts with the FEC extension frame `0x80 0x02 fecSeq16` carrying the seq the decoder solved for. A wrong
     * solve that is a GF(256) multiple of the true symbol (the failure mode of a lost pivot, see RlncDecoder) cannot
     * pass it: `c * 0x80 == 0x80` only for `c == 1`.
     */
    fun validator(symbolSize: Int): RlncDecoder.SymbolValidator = RlncDecoder.SymbolValidator { seq, s ->
        val len = ((s[0].toInt() and 0xFF) shl 8) or (s[1].toInt() and 0xFF)
        len in 1..(symbolSize - 2) && s[2] == 0x80.toByte() && s[3] == 2.toByte() &&
            s[4] == (seq shr 8).toByte() && s[5] == seq.toByte()
    }

    fun run(cfg: Config): Result {
        require(!cfg.threads) { "the threaded soak variant is JVM-only upstream and is not ported" }
        return runSingle(cfg)
    }

    private sealed class Pkt {
        var at = 0L; var id = 0L
        class Source(val seq: Long) : Pkt() { var resend = false; var attempts = 0 }
        class Repair(val frame: RepairSymbol) : Pkt()
    }

    /** The sender side: one encoder per window-size segment, repair seeds as the transport derives them. */
    private class Sender(val cfg: Config) {
        private val sizes = cfg.windows.toList()
        private var enc = RlncEncoder(cfg.symbolSize, sizes[0])
        private var segment = 0
        private var seed = 0x5A5A
        /** Symbols in the current encoder (0 right after a segment switch: the transport never repairs an empty window). */
        var count = 0; private set

        fun push(seq: Long) {
            val seg = (seq / cfg.segment).toInt()
            if (seg != segment) { segment = seg; enc = RlncEncoder(cfg.symbolSize, sizes[seg % sizes.size]); count = 0 }
            enc.push(seq, symbol(seq, cfg.symbolSize, cfg.seed)); count++
        }
        fun repair(): RepairSymbol? = if (count == 0) null else enc.repair(++seed * 0x9E3779B1.toInt())
    }

    /** The network (loss, re-sends, reordering) and the receiver (decoder rotation, verification). */
    private class Receiver(val cfg: Config, private val rnd: Random) {
        private val validator = if (cfg.validate) validator(cfg.symbolSize) else null
        private var dec = RlncDecoder(cfg.symbolSize, validator)
        private var epoch = 0L
        private var clock = 0L
        private var nextId = 0L
        private val pending = MinQueue<Pkt>(compareBy<Pkt>({ it.at }, { it.id }))
        private val verified = BooleanArray(cfg.symbols)
        private var sources = 0L; private var repairs = 0L; private var resends = 0L; private var lost = 0L
        private var decoded = 0L; private var wrong = 0L; private var rejected = 0L; private var inconsistent = 0L; private var rotations = 0L
        private var firstWrong: String? = null

        private fun original(seq: Long) = symbol(seq, cfg.symbolSize, cfg.seed)

        /** The sender hands a packet to the network; everything that is due by now is delivered. */
        fun offer(p: Pkt) {
            clock++
            if (rnd.nextDouble() < cfg.loss) { lost++; if (p is Pkt.Source) scheduleResend(p) }
            else { p.at = clock + if (rnd.nextDouble() < cfg.reorder) 1 + rnd.nextInt(cfg.reorderDepth) else 0; p.id = nextId++; pending += p }
            drain()
        }

        private fun scheduleResend(p: Pkt.Source) {
            if (p.attempts >= 16) return
            p.resend = true; p.attempts++
            p.at = clock + cfg.resendDelay.random(rnd); p.id = nextId++; pending += p
        }

        private fun drain() { while (pending.isNotEmpty() && pending.peek()!!.at <= clock) deliver(pending.poll()!!) }

        fun finish(): Result {
            clock = Long.MAX_VALUE / 2; drain()
            verify(epoch, cfg.symbols - 1L)
            rejected += dec.rejected; inconsistent += dec.inconsistent
            return Result(sources, repairs, resends, lost, decoded, wrong, firstWrong, rejected, inconsistent, rotations)
        }

        private fun deliver(p: Pkt) {
            when (p) {
                is Pkt.Source -> {
                    if (p.resend) {
                        if (rnd.nextDouble() < cfg.loss) { lost++; scheduleResend(p); return } // the re-send is a packet on the same network
                        resends++
                    } else sources++
                    if (dec.get(p.seq) != null) return   // already delivered (recovered, or a duplicate): the transport skips it
                    rotate(p.seq)
                    dec.onSource(p.seq, original(p.seq))
                    verified[p.seq.toInt()] = true
                    verify(p.seq - cfg.refeed, p.seq + cfg.refeed)   // a source can complete rows around it
                }
                is Pkt.Repair -> {
                    repairs++
                    val f = p.frame
                    val end = f.windowBase + f.windowLen - 1
                    rotate(end)
                    dec.onRepair(f)
                    verify(f.windowBase - cfg.refeed, end + cfg.refeed)
                }
            }
        }

        private fun rotate(seq: Long) {
            if (seq - epoch < cfg.rotateEvery) return
            verify(epoch, seq)   // everything the old decoder ever solved is checked before it goes
            val old = dec
            rejected += old.rejected; inconsistent += old.inconsistent
            dec = RlncDecoder(cfg.symbolSize, validator)
            for (s in max(0L, seq - cfg.refeed) until seq) old.get(s)?.let { dec.onSource(s, it) }
            epoch = seq; rotations++
        }

        private fun verify(lo: Long, hi: Long) {
            var s = max(0L, lo)
            val end = min(hi, cfg.symbols - 1L)
            while (s <= end) {
                val i = s.toInt()
                if (!verified[i]) {
                    val d = dec.get(s)
                    if (d != null) {
                        verified[i] = true; decoded++
                        if (!d.contentEquals(original(s))) { wrong++; if (firstWrong == null) firstWrong = describe(s, d) }
                    }
                }
                s++
            }
        }

        private fun describe(seq: Long, d: ByteArray): String {
            val len = ((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF)
            val head = d.take(8).joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
            return "seq=$seq len=$len head=[$head] at packet $clock (epoch $epoch, window sizes ${cfg.windows})"
        }
    }

    private fun runSingle(cfg: Config): Result {
        val rnd = Random(cfg.seed)
        val rx = Receiver(cfg, Random(cfg.seed + 1))
        val snd = Sender(cfg)
        for (seq in 0L until cfg.symbols) {
            snd.push(seq)
            rx.offer(Pkt.Source(seq))
            if (rnd.nextDouble() < cfg.redundancy) snd.repair()?.let { rx.offer(Pkt.Repair(it)) }
            if (rnd.nextDouble() < cfg.timerRepair) snd.repair()?.let { rx.offer(Pkt.Repair(it)) }
        }
        return rx.finish()
    }
}

/** The few PriorityQueue operations the harness uses, in common Kotlin (a binary heap). */
internal class MinQueue<T>(private val cmp: Comparator<T>) {
    private val a = ArrayList<T>()
    fun isNotEmpty() = a.isNotEmpty()
    fun peek(): T? = a.firstOrNull()
    operator fun plusAssign(x: T) { a.add(x); var i = a.size - 1; while (i > 0) { val p = (i - 1) / 2; if (cmp.compare(a[i], a[p]) < 0) { val t = a[i]; a[i] = a[p]; a[p] = t; i = p } else break } }
    fun poll(): T? {
        if (a.isEmpty()) return null
        val top = a[0]; val last = a.removeAt(a.size - 1)
        if (a.isNotEmpty()) {
            a[0] = last; var i = 0
            while (true) {
                val l = 2 * i + 1; val r = l + 1; var m = i
                if (l < a.size && cmp.compare(a[l], a[m]) < 0) m = l
                if (r < a.size && cmp.compare(a[r], a[m]) < 0) m = r
                if (m == i) break
                val t = a[i]; a[i] = a[m]; a[m] = t; i = m
            }
        }
        return top
    }
}
