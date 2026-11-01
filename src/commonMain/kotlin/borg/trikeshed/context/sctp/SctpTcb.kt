package borg.trikeshed.sctp

import borg.trikeshed.job.hmacSha256
import kotlin.random.Random

/*
 * SCTP associations (RFC 9260) as a pure state machine, split from I/O the way the feed server splits Xio from its
 * Poller: the owner loop gives [SctpTcbTable.receive] each datagram of a wake, then calls [SctpTcbTable.flush] once,
 * and every sealed packet leaves through [SctpSink] inside those two calls. One table serves every association of a
 * local port, so the kernel holds one UDP socket however many connections there are.
 *
 * Scope of this version, stated so nothing reads as more than it is:
 * - one ordered stream per association (connection-oriented, as TCP); messages are fragmented with B/E flags;
 * - congestion control is RFC 9260 §7.2 slow start and congestion avoidance with T3-rtx; there is no fast retransmit;
 * - gap-acked chunks are not sent again; there are no HEARTBEATs (a path is presumed alive while it carries data);
 * - the receive window is the table's constant; out-of-order chunks are held until the gap fills.
 */

/** An index the owner gives to a peer address (UDP address and port); the table only hands it back. */
typealias SctpPath = Int

/** What the table delivers upward. One implementation per program keeps these calls monomorphic. */
interface SctpUpper {
    /** [tcb] reached ESTABLISHED, on either side. */
    fun associated(tcb: SctpTcb)

    /** One whole message: [bytes] from [offset] for [length]. The array is reused after the call returns. */
    fun message(tcb: SctpTcb, ppid: Ppid, bytes: ByteArray, offset: Int, length: Int)

    /** [tcb] is gone: shut down, aborted or out of retransmissions. */
    fun closed(tcb: SctpTcb)
}

/** Where sealed packets go. The array is reused after the call returns: the owner copies or sends at once. */
interface SctpSink {
    fun send(path: SctpPath, packet: ByteArray, length: Int)
}

/**
 * Transmission Control Block (RFC 9260 §14.1): one association. The send queue is a ring of chunk descriptors in TSN
 * order over per-message arrays; [upperData] belongs to the layer above (the feed server's connection, a load user).
 */
class SctpTcb(val table: SctpTcbTable) {
    var localTag: VerificationTag = 0
    var peerTag: VerificationTag = 0
    var path: SctpPath = 0
    var peerPort: Int = 0
    var state: SctpState = SctpState.CLOSED
    var upperData: Any? = null

    val number: ConnectionNumber get() = ConnectionNumber(localTag, peerTag)

    // Transmit side.
    var nextTsn: Tsn = 0
    /** Every TSN up to and including this one is acknowledged by the peer. */
    var ackedTsn: Tsn = 0
    var peerRwnd: Int = 0
    var cwnd: Int = 0
    var ssthresh: Int = 0
    var partialAcked: Int = 0
    /** Bytes sent and not yet acknowledged, counted per DATA chunk value. */
    var flight: Int = 0
    var rto: Long = 0
    var srtt: Long = 0
    var rttvar: Long = 0
    /** The TSN being timed for an RTT sample, valid while [timing]; cleared by a retransmission (Karn). */
    var timedTsn: Tsn = 0
    var timedAt: Long = 0
    var timing = false
    /** T3-rtx (or T1 during the handshake) expiry, valid while [timerOn]. */
    var deadline: Long = 0
    var timerOn = false
    /** In the table's timer list; the list drops it once [timerOn] is off. */
    var listed = false
    var retransmissions = 0
    /** INIT or COOKIE ECHO kept for T1 retransmission while the handshake runs. */
    var cookie: ByteArray? = null
    var localInitTsn: Tsn = 0

    // Send ring: entries [head, tail) by counter; [sent] is the first never sent. Index = counter and (capacity - 1).
    var qTsn = IntArray(16)
    var qFlags = IntArray(16)
    var qPpid = IntArray(16)
    var qOff = IntArray(16)
    var qLen = IntArray(16)
    var qGapAcked = BooleanArray(16)
    var qMsg = arrayOfNulls<ByteArray>(16)
    var head = 0
    var sent = 0
    var tail = 0

    // Receive side.
    /** The last TSN received in order. */
    var cumTsn: Tsn = 0
    var sackDue = false
    var reasm = NO_DATA
    var reasmLength = 0
    var reasmPpid: Ppid = 0
    var oooTsn = IntArray(0)
    var oooFlags = IntArray(0)
    var oooPpid = IntArray(0)
    var oooData = arrayOfNulls<ByteArray>(0)
    var oooCount = 0

    /** In the table's dirty list for the next [SctpTcbTable.flush]. */
    var dirty = false
    /** SHUTDOWN, SHUTDOWN ACK, COOKIE ACK or COOKIE ECHO to send at the next flush. */
    var control = 0
    /** close() was asked: SHUTDOWN once the send queue is empty. */
    var closing = false

    inline val queued: Int get() = tail - head

    fun grow() {
        val cap = qTsn.size
        val n = cap * 2
        fun IntArray.regrow(): IntArray { val r = IntArray(n); for (c in head until tail) r[c and (n - 1)] = this[c and (cap - 1)]; return r }
        qTsn = qTsn.regrow(); qFlags = qFlags.regrow(); qPpid = qPpid.regrow(); qOff = qOff.regrow(); qLen = qLen.regrow()
        val g = BooleanArray(n); val m = arrayOfNulls<ByteArray>(n)
        for (c in head until tail) { g[c and (n - 1)] = qGapAcked[c and (cap - 1)]; m[c and (n - 1)] = qMsg[c and (cap - 1)] }
        qGapAcked = g; qMsg = m
    }

    companion object {
        val NO_DATA = ByteArray(0)
        const val SEND_SHUTDOWN = 1
        const val SEND_SHUTDOWN_ACK = 2
        const val SEND_COOKIE_ACK = 4
        const val SEND_COOKIE_ECHO = 8
        const val SEND_SHUTDOWN_COMPLETE = 16
    }
}

/**
 * Open addressing from a verification tag to its TCB: IntArray keys, linear probing, backward-shift deletion. Tags
 * are random, so a multiplicative hash spreads them; there is no boxing on lookup.
 */
class TagTable(initialBits: Int = 12) {
    var bits = initialBits
    var keys = IntArray(1 shl bits)
    var values = arrayOfNulls<SctpTcb>(1 shl bits)
    var count = 0

    inline fun index(tag: Int): Int = ((tag * -0x61c88647) ushr (32 - bits))

    operator fun get(tag: VerificationTag): SctpTcb? {
        val mask = keys.size - 1
        var i = index(tag)
        while (true) {
            val v = values[i] ?: return null
            if (keys[i] == tag) return v
            i = (i + 1) and mask
        }
    }

    fun put(tag: VerificationTag, tcb: SctpTcb) {
        if ((count + 1) * 2 > keys.size) resize()
        val mask = keys.size - 1
        var i = index(tag)
        while (values[i] != null) {
            if (keys[i] == tag) { values[i] = tcb; return }
            i = (i + 1) and mask
        }
        keys[i] = tag; values[i] = tcb; count++
    }

    fun remove(tag: VerificationTag) {
        val mask = keys.size - 1
        var i = index(tag)
        while (true) {
            if (values[i] == null) return
            if (keys[i] == tag) break
            i = (i + 1) and mask
        }
        values[i] = null; count--
        var j = (i + 1) and mask
        while (values[j] != null) {
            val home = index(keys[j])
            // Move j back to the hole when its home is cyclically outside (i, j].
            if (if (i <= j) home <= i || home > j else home <= i && home > j) {
                keys[i] = keys[j]; values[i] = values[j]; values[j] = null; i = j
            }
            j = (j + 1) and mask
        }
    }

    fun resize() {
        val oldKeys = keys; val oldValues = values
        bits++
        keys = IntArray(1 shl bits); values = arrayOfNulls(1 shl bits); count = 0
        for (k in oldKeys.indices) oldValues[k]?.let { put(oldKeys[k], it) }
    }
}

/**
 * Every association of [localPort]: handshake, transfer, retransmission and shutdown, driven by the owner loop.
 * The listening side keeps no state before a COOKIE ECHO (RFC 9260 §5.1.3): the INIT ACK's cookie carries both tags
 * and both initial TSNs, MACed with [secret].
 */
class SctpTcbTable(
    val localPort: Int,
    val upper: SctpUpper,
    val sink: SctpSink,
    val secret: ByteArray,
    /** SCTP packet bytes per datagram: 1452 fits Ethernet under IPv4 and UDP. */
    val mtu: Int = 1452,
    val rwnd: Int = 1 shl 20,
    val rtoInitialNanos: Long = 1_000_000_000L,
    val rtoMinNanos: Long = 1_000_000_000L,
    val rtoMaxNanos: Long = 60_000_000_000L,
    val maxRetransmissions: Int = 10,
    /** A cookie older than this on the owner's monotonic clock is refused (RFC 9260 Valid.Cookie.Life). */
    val cookieLifeNanos: Long = 60_000_000_000L,
    val random: Random = Random.Default,
) {
    val tags = TagTable()
    val writer = SctpPacketWriter(ByteArray(mtu))
    var dirty = arrayOfNulls<SctpTcb>(256)
    var dirtyCount = 0
    var timed = arrayOfNulls<SctpTcb>(256)
    var timedCount = 0
    /** Packets refused: checksum, port, unknown tag, malformed. */
    var dropped = 0L
    /** Largest DATA value per chunk under [mtu]. */
    val maxData = mtu - SCTP_HEADER - DATA_HEADER

    val count: Int get() = tags.count

    fun newTag(): VerificationTag {
        while (true) {
            val t = random.nextInt()
            if (t != 0 && tags[t] == null) return t
        }
    }

    fun markDirty(t: SctpTcb) {
        if (t.dirty) return
        t.dirty = true
        if (dirtyCount == dirty.size) dirty = dirty.copyOf(dirtyCount * 2)
        dirty[dirtyCount++] = t
    }

    fun arm(t: SctpTcb, now: Long) {
        t.deadline = now + t.rto
        t.timerOn = true
        if (t.listed) return
        t.listed = true
        if (timedCount == timed.size) timed = timed.copyOf(timedCount * 2)
        timed[timedCount++] = t
    }

    fun begin(t: SctpTcb, rto: Long) {
        t.rto = rto
        t.cwnd = minOf(4 * mtu, maxOf(2 * mtu, 4380))
        t.ssthresh = t.peerRwnd
    }

    // ── Client side ───────────────────────────────────────────────────────────

    /** INIT toward [path]:[peerPort]; [SctpUpper.associated] follows once the COOKIE ACK arrives. */
    fun connect(path: SctpPath, peerPort: Int, now: Long): SctpTcb {
        val t = SctpTcb(this)
        t.localTag = newTag()
        t.path = path
        t.peerPort = peerPort
        t.localInitTsn = random.nextInt()
        t.nextTsn = t.localInitTsn
        t.ackedTsn = t.localInitTsn - 1
        t.state = SctpState.COOKIE_WAIT
        t.rto = rtoInitialNanos
        tags.put(t.localTag, t)
        sendInit(t)
        arm(t, now)
        return t
    }

    fun sendInit(t: SctpTcb) {
        writer.begin(localPort, t.peerPort, 0)
        writer.init(ChunkType.INIT, t.localTag, rwnd, 1, 1, t.localInitTsn)
        sink.send(t.path, writer.buffer, writer.seal())
    }

    // ── Upper-layer calls ─────────────────────────────────────────────────────

    /**
     * Queues one message on the association's stream, fragmented to fit [mtu]; it leaves at the next [flush] as far as
     * the windows allow. [bytes] is copied: the caller may reuse it.
     */
    fun send(t: SctpTcb, ppid: Ppid, bytes: ByteArray, offset: Int, length: Int) {
        check(t.state == SctpState.ESTABLISHED || t.state == SctpState.COOKIE_ECHOED || t.state == SctpState.COOKIE_WAIT) {
            "send on ${t.state}"
        }
        val msg = bytes.copyOfRange(offset, offset + length)
        var at = 0
        do {
            val n = minOf(maxData, length - at)
            if (t.tail - t.head == t.qTsn.size) t.grow()
            val i = t.tail and (t.qTsn.size - 1)
            var flags = 0
            if (at == 0) flags = flags or DataFlags.B
            if (at + n == length) flags = flags or DataFlags.E
            t.qTsn[i] = t.nextTsn++
            t.qFlags[i] = flags
            t.qPpid[i] = ppid
            t.qOff[i] = at
            t.qLen[i] = n
            t.qGapAcked[i] = false
            t.qMsg[i] = msg
            t.tail++
            at += n
        } while (at < length)
        markDirty(t)
    }

    /** SHUTDOWN once everything queued is acknowledged (RFC 9260 §9.2). */
    fun close(t: SctpTcb) {
        if (t.closing || t.state != SctpState.ESTABLISHED) return
        t.closing = true
        t.state = SctpState.SHUTDOWN_PENDING
        markDirty(t)
    }

    /** ABORT now: the peer drops the association at once. */
    fun abort(t: SctpTcb) {
        writer.begin(localPort, t.peerPort, t.peerTag)
        writer.bare(ChunkType.ABORT)
        sink.send(t.path, writer.buffer, writer.seal())
        release(t)
    }

    fun release(t: SctpTcb) {
        if (t.state == SctpState.CLOSED && tags[t.localTag] !== t) return
        t.state = SctpState.CLOSED
        tags.remove(t.localTag)
        t.timerOn = false
        for (c in t.head until t.tail) t.qMsg[c and (t.qMsg.size - 1)] = null
        t.head = t.tail
        upper.closed(t)
    }

    // ── Receive ───────────────────────────────────────────────────────────────

    /** One datagram of the wake from [path]. */
    fun receive(p: ByteArray, length: Int, path: SctpPath, now: Long) {
        if (length < SCTP_HEADER + 4 || !sctpChecksumValid(p, length) || p.destinationPort() != localPort) { dropped++; return }
        val tag = p.verificationTag()
        if (p.chunkType(SCTP_HEADER) == ChunkType.INIT) {
            if (tag == 0) answerInit(p, length, path, now) else dropped++
            return
        }
        var t: SctpTcb? = tags[tag]
        p.forEachChunk(length) { type, flags, at, len ->
            if (type == ChunkType.COOKIE_ECHO) {
                if (t == null) t = acceptCookie(p, at, len, tag, path, now) ?: run { dropped++; return }
                else if (t!!.state == SctpState.ESTABLISHED) { t!!.control = t!!.control or SctpTcb.SEND_COOKIE_ACK; markDirty(t!!) }
                return@forEachChunk
            }
            val tcb = t ?: run { dropped++; return }
            tcb.path = path
            when (type) {
                ChunkType.DATA -> data(tcb, p, flags, at, len)
                ChunkType.SACK -> sack(tcb, p, at, len, now)
                ChunkType.INIT_ACK -> initAck(tcb, p, at, len, now)
                ChunkType.COOKIE_ACK -> if (tcb.state == SctpState.COOKIE_ECHOED) established(tcb, now)
                ChunkType.SHUTDOWN -> shutdown(tcb, p, at)
                ChunkType.SHUTDOWN_ACK -> {
                    writer.begin(localPort, tcb.peerPort, tcb.peerTag)
                    writer.bare(ChunkType.SHUTDOWN_COMPLETE)
                    sink.send(tcb.path, writer.buffer, writer.seal())
                    release(tcb)
                    return
                }
                ChunkType.SHUTDOWN_COMPLETE, ChunkType.ABORT -> { release(tcb); return }
                else -> {
                    // RFC 9260 §3.2: the upper two type bits say whether an unknown chunk stops the packet.
                    if (type ushr 6 == 0 || type ushr 6 == 1) return
                }
            }
        }
    }

    /** INIT: an INIT ACK whose cookie holds everything the association needs; no TCB yet. */
    fun answerInit(p: ByteArray, length: Int, path: SctpPath, now: Long) {
        val at = SCTP_HEADER
        if (p.chunkLength(at) < INIT_FIXED || at + INIT_FIXED > length) { dropped++; return }
        val peerTag = p.initTag(at)
        if (peerTag == 0) { dropped++; return }
        val localTag = newTag()
        val localTsn = random.nextInt()
        val c = ByteArray(COOKIE_FIELDS + COOKIE_MAC)
        c.putNet32(0, localTag)
        c.putNet32(4, peerTag)
        c.putNet32(8, localTsn)
        c.putNet32(12, p.initTsn(at))
        c.putNet32(16, p.initRwnd(at))
        c.putNet16(20, p.sourcePort())
        c.putNet32(24, (now ushr 32).toInt())
        c.putNet32(28, now.toInt())
        hmacSha256(secret, c.copyOf(COOKIE_FIELDS)).copyInto(c, COOKIE_FIELDS, 0, COOKIE_MAC)
        writer.begin(localPort, p.sourcePort(), peerTag)
        writer.init(ChunkType.INIT_ACK, localTag, rwnd, 1, 1, localTsn, c)
        sink.send(path, writer.buffer, writer.seal())
    }

    /** COOKIE ECHO with a valid MAC and age: the TCB the cookie describes, ESTABLISHED, its COOKIE ACK queued. */
    fun acceptCookie(p: ByteArray, at: Int, len: Int, tag: VerificationTag, path: SctpPath, now: Long): SctpTcb? {
        val from = at + 4
        if (len - 4 != COOKIE_FIELDS + COOKIE_MAC) return null
        val fields = p.copyOfRange(from, from + COOKIE_FIELDS)
        val mac = hmacSha256(secret, fields)
        for (i in 0 until COOKIE_MAC) if (mac[i] != p[from + COOKIE_FIELDS + i]) return null
        if (fields.net32(0) != tag) return null
        val created = (fields.net32(24).toLong() shl 32) or (fields.net32(28).toLong() and 0xffffffffL)
        if (now - created > cookieLifeNanos) return null
        if (tags[tag] != null) return null
        val t = SctpTcb(this)
        t.localTag = tag
        t.peerTag = fields.net32(4)
        t.localInitTsn = fields.net32(8)
        t.nextTsn = t.localInitTsn
        t.ackedTsn = t.localInitTsn - 1
        t.cumTsn = fields.net32(12) - 1
        t.peerRwnd = fields.net32(16)
        t.peerPort = fields.net16(20)
        t.path = path
        begin(t, rtoInitialNanos)
        tags.put(tag, t)
        t.state = SctpState.ESTABLISHED
        t.control = t.control or SctpTcb.SEND_COOKIE_ACK
        markDirty(t)
        upper.associated(t)
        return t
    }

    fun initAck(t: SctpTcb, p: ByteArray, at: Int, len: Int, now: Long) {
        if (t.state != SctpState.COOKIE_WAIT) return
        val found = p.stateCookie(at, len)
        if (found < 0) return
        val from = (found ushr 32).toInt()
        t.peerTag = p.initTag(at)
        t.peerRwnd = p.initRwnd(at)
        t.cumTsn = p.initTsn(at) - 1
        t.cookie = p.copyOfRange(from, from + found.toInt())
        t.state = SctpState.COOKIE_ECHOED
        begin(t, rtoInitialNanos)
        t.control = t.control or SctpTcb.SEND_COOKIE_ECHO
        arm(t, now)
        markDirty(t)
    }

    fun established(t: SctpTcb, now: Long) {
        t.state = SctpState.ESTABLISHED
        t.cookie = null
        t.retransmissions = 0
        if (t.flight > 0) arm(t, now) else t.timerOn = false
        upper.associated(t)
        if (t.queued > 0) markDirty(t)
    }

    /** One DATA chunk: in order it is delivered (with any held chunks it unblocks), ahead of order it is held. */
    fun data(t: SctpTcb, p: ByteArray, flags: Int, at: Int, len: Int) {
        if (len < DATA_HEADER || t.state == SctpState.COOKIE_WAIT) return
        val tsn = p.dataTsn(at)
        t.sackDue = true
        markDirty(t)
        val d = tsnDistance(tsn, t.cumTsn)
        if (d <= 0) return
        if (d > 1) { hold(t, tsn, flags, p.dataPpid(at), p, at + DATA_HEADER, len - DATA_HEADER); return }
        deliver(t, flags, p.dataPpid(at), p, at + DATA_HEADER, len - DATA_HEADER)
        t.cumTsn = tsn
        while (t.oooCount > 0 && t.oooTsn[0] == t.cumTsn + 1) {
            val data = t.oooData[0]!!
            deliver(t, t.oooFlags[0], t.oooPpid[0], data, 0, data.size)
            t.cumTsn++
            t.oooTsn.copyInto(t.oooTsn, 0, 1, t.oooCount)
            t.oooFlags.copyInto(t.oooFlags, 0, 1, t.oooCount)
            t.oooPpid.copyInto(t.oooPpid, 0, 1, t.oooCount)
            t.oooData.copyInto(t.oooData, 0, 1, t.oooCount)
            t.oooCount--
            t.oooData[t.oooCount] = null
        }
    }

    /** A whole message straight from the packet; fragments gather in [SctpTcb.reasm] until E. */
    fun deliver(t: SctpTcb, flags: Int, ppid: Ppid, src: ByteArray, from: Int, n: Int) {
        val b = flags and DataFlags.B != 0
        val e = flags and DataFlags.E != 0
        if (b && e) { upper.message(t, ppid, src, from, n); return }
        if (b) { t.reasmLength = 0; t.reasmPpid = ppid }
        if (t.reasm.size < t.reasmLength + n) t.reasm = t.reasm.copyOf(maxOf(t.reasm.size * 2, t.reasmLength + n, 4096))
        src.copyInto(t.reasm, t.reasmLength, from, from + n)
        t.reasmLength += n
        if (e) {
            upper.message(t, t.reasmPpid, t.reasm, 0, t.reasmLength)
            t.reasmLength = 0
        }
    }

    /** A chunk ahead of order, kept sorted by TSN; a duplicate is dropped. */
    fun hold(t: SctpTcb, tsn: Tsn, flags: Int, ppid: Ppid, src: ByteArray, from: Int, n: Int) {
        var i = 0
        while (i < t.oooCount && tsnDistance(t.oooTsn[i], tsn) < 0) i++
        if (i < t.oooCount && t.oooTsn[i] == tsn) return
        if (t.oooCount == t.oooTsn.size) {
            val cap = maxOf(8, t.oooCount * 2)
            t.oooTsn = t.oooTsn.copyOf(cap); t.oooFlags = t.oooFlags.copyOf(cap)
            t.oooPpid = t.oooPpid.copyOf(cap); t.oooData = t.oooData.copyOf(cap)
        }
        t.oooTsn.copyInto(t.oooTsn, i + 1, i, t.oooCount)
        t.oooFlags.copyInto(t.oooFlags, i + 1, i, t.oooCount)
        t.oooPpid.copyInto(t.oooPpid, i + 1, i, t.oooCount)
        t.oooData.copyInto(t.oooData, i + 1, i, t.oooCount)
        t.oooTsn[i] = tsn; t.oooFlags[i] = flags; t.oooPpid[i] = ppid; t.oooData[i] = src.copyOfRange(from, from + n)
        t.oooCount++
    }

    /** SACK: the cumulative point frees the ring's head, gap blocks mark later chunks, the windows move. */
    fun sack(t: SctpTcb, p: ByteArray, at: Int, len: Int, now: Long) {
        if (len < 16) return
        val cum = p.sackCumulative(at)
        t.peerRwnd = p.sackRwnd(at)
        val mask = t.qTsn.size - 1
        var acked = 0
        if (tsnDistance(cum, t.ackedTsn) > 0) {
            // To the tail, not to [sent]: after a T3 reset the peer may acknowledge chunks sent before it.
            while (t.head < t.tail && tsnDistance(t.qTsn[t.head and mask], cum) <= 0) {
                val i = t.head and mask
                if (!t.qGapAcked[i] && t.head < t.sent) acked += t.qLen[i]
                t.qMsg[i] = null
                t.head++
            }
            if (t.sent < t.head) t.sent = t.head
            t.ackedTsn = cum
            if (t.timing && tsnDistance(t.timedTsn, cum) <= 0) { sample(t, now - t.timedAt); t.timing = false }
            t.retransmissions = 0
        }
        val gaps = p.sackGaps(at)
        var g = 0
        while (g < gaps && 16 + 4 * g + 4 <= len) {
            val from = p.sackGapStart(at, g)
            val to = p.sackGapEnd(at, g)
            for (c in t.head until t.sent) {
                val i = c and mask
                val off = tsnDistance(t.qTsn[i], cum)
                if (off in from..to && !t.qGapAcked[i]) { t.qGapAcked[i] = true; acked += t.qLen[i] }
            }
            g++
        }
        if (acked > 0) {
            t.flight = maxOf(0, t.flight - acked)
            if (t.cwnd <= t.ssthresh) t.cwnd += minOf(acked, mtu)
            else {
                t.partialAcked += acked
                if (t.partialAcked >= t.cwnd) { t.partialAcked -= t.cwnd; t.cwnd += mtu }
            }
        }
        if (t.head == t.sent) t.timerOn = false else arm(t, now)
        if (t.queued > 0 || t.closing) markDirty(t)
    }

    /** RFC 9260 §6.3.1 smoothed RTT and RTO. */
    fun sample(t: SctpTcb, r: Long) {
        if (t.srtt == 0L) { t.srtt = r; t.rttvar = r / 2 }
        else {
            t.rttvar = (3 * t.rttvar + kotlin.math.abs(t.srtt - r)) / 4
            t.srtt = (7 * t.srtt + r) / 8
        }
        t.rto = (t.srtt + 4 * t.rttvar).coerceIn(rtoMinNanos, rtoMaxNanos)
    }

    fun shutdown(t: SctpTcb, p: ByteArray, at: Int) {
        if (t.state != SctpState.ESTABLISHED && t.state != SctpState.SHUTDOWN_PENDING && t.state != SctpState.SHUTDOWN_SENT) return
        t.state = SctpState.SHUTDOWN_RECEIVED
        t.closing = true
        markDirty(t)
    }

    // ── Drain ─────────────────────────────────────────────────────────────────

    /**
     * End of the wake: for each association touched, one packet or more with its control chunks, a SACK when DATA
     * arrived, and DATA as far as min(cwnd, peer rwnd) allows; then the timers.
     */
    fun flush(now: Long) {
        var k = 0
        while (k < dirtyCount) {
            val t = dirty[k]!!
            dirty[k] = null
            t.dirty = false
            if (tags[t.localTag] === t) drain(t, now)
            k++
        }
        dirtyCount = 0
        expire(now)
    }

    fun drain(t: SctpTcb, now: Long) {
        writer.begin(localPort, t.peerPort, t.peerTag)
        if (t.control and SctpTcb.SEND_COOKIE_ECHO != 0) {
            val c = t.cookie!!
            writer.cookieEcho(c, 0, c.size)
        }
        if (t.control and SctpTcb.SEND_COOKIE_ACK != 0) writer.bare(ChunkType.COOKIE_ACK)
        t.control = t.control and (SctpTcb.SEND_COOKIE_ECHO or SctpTcb.SEND_COOKIE_ACK).inv()
        if (t.sackDue) {
            writeSack(t)
            t.sackDue = false
        }
        if (t.state == SctpState.ESTABLISHED || t.state == SctpState.SHUTDOWN_PENDING || t.state == SctpState.SHUTDOWN_RECEIVED ||
            t.state == SctpState.COOKIE_ECHOED) {
            val mask = t.qTsn.size - 1
            while (t.sent < t.tail) {
                val i = t.sent and mask
                val n = t.qLen[i]
                if (t.flight > 0 && (t.flight + n > t.cwnd || n > t.peerRwnd)) break
                if (!writer.room(DATA_HEADER + n)) { emit(t); writer.begin(localPort, t.peerPort, t.peerTag) }
                if (!t.qGapAcked[i]) {
                    writer.data(t.qTsn[i], 0, 0, t.qPpid[i], t.qFlags[i], t.qMsg[i]!!, t.qOff[i], n)
                    t.flight += n
                    t.peerRwnd = maxOf(0, t.peerRwnd - n)
                    if (!t.timing) { t.timing = true; t.timedTsn = t.qTsn[i]; t.timedAt = now }
                }
                t.sent++
            }
            if (t.sent > t.head && !t.timerOn) arm(t, now)
        }
        if (t.closing && t.head == t.tail) {
            if (t.state == SctpState.SHUTDOWN_PENDING) {
                if (!writer.room(8)) { emit(t); writer.begin(localPort, t.peerPort, t.peerTag) }
                writer.shutdown(t.cumTsn)
                t.state = SctpState.SHUTDOWN_SENT
                arm(t, now)
            } else if (t.state == SctpState.SHUTDOWN_RECEIVED) {
                if (!writer.room(4)) { emit(t); writer.begin(localPort, t.peerPort, t.peerTag) }
                writer.bare(ChunkType.SHUTDOWN_ACK)
                t.state = SctpState.SHUTDOWN_ACK_SENT
                arm(t, now)
            }
        }
        if (!writer.empty) emit(t)
    }

    /** The packet in [writer] goes to [t]'s path. */
    fun emit(t: SctpTcb) { sink.send(t.path, writer.buffer, writer.seal()) }

    /** SACK for [t]'s cumulative TSN, with gap blocks over the chunks held ahead of order. */
    fun writeSack(t: SctpTcb) {
        var blocks = 0
        val gaps = IntArray(2 * minOf(t.oooCount, 64))
        var i = 0
        while (i < t.oooCount && blocks < 64) {
            val start = tsnDistance(t.oooTsn[i], t.cumTsn)
            var end = start
            while (i + 1 < t.oooCount && t.oooTsn[i + 1] == t.oooTsn[i] + 1) { i++; end++ }
            gaps[2 * blocks] = start; gaps[2 * blocks + 1] = end
            blocks++
            i++
        }
        var held = 0
        for (k in 0 until t.oooCount) held += t.oooData[k]!!.size
        writer.sack(t.cumTsn, maxOf(0, rwnd - held), gaps, blocks)
    }

    /** T1 and T3-rtx (RFC 9260 §6.3.3): resend from the oldest unacknowledged chunk; too many and the TCB is dropped. */
    fun expire(now: Long) {
        var k = 0
        var keep = 0
        while (k < timedCount) {
            val t = timed[k]!!
            timed[k] = null
            k++
            if (!t.timerOn || tags[t.localTag] !== t) { t.timerOn = false; t.listed = false; continue }
            if (now - t.deadline < 0) { timed[keep++] = t; continue }
            if (++t.retransmissions > maxRetransmissions) { t.timerOn = false; t.listed = false; release(t); continue }
            t.rto = minOf(t.rto * 2, rtoMaxNanos)
            t.deadline = now + t.rto
            timed[keep++] = t
            when (t.state) {
                SctpState.COOKIE_WAIT -> sendInit(t)
                SctpState.COOKIE_ECHOED -> { t.control = t.control or SctpTcb.SEND_COOKIE_ECHO; t.sent = t.head; t.flight = 0; markDirty(t) }
                SctpState.SHUTDOWN_SENT -> { t.state = SctpState.SHUTDOWN_PENDING; markDirty(t) }
                SctpState.SHUTDOWN_ACK_SENT -> { t.state = SctpState.SHUTDOWN_RECEIVED; markDirty(t) }
                else -> {
                    t.ssthresh = maxOf(t.cwnd / 2, 4 * mtu)
                    t.cwnd = mtu
                    t.partialAcked = 0
                    t.flight = 0
                    t.timing = false
                    t.sent = t.head
                    markDirty(t)
                }
            }
        }
        timedCount = keep
    }

    companion object {
        /** Cookie fields: local tag, peer tag, local TSN, peer TSN, peer a_rwnd, peer port + 2 spare, creation time (8, owner's clock). */
        const val COOKIE_FIELDS = 32
        /** HMAC-SHA256 truncated to 128 bits. */
        const val COOKIE_MAC = 16
    }
}
