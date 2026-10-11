package borg.trikeshed.loom

import borg.trikeshed.collections.associative.*
import borg.trikeshed.ipns.*
import borg.trikeshed.job.sha256
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.*
import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.*
import borg.trikeshed.userspace.nio.platform.spi.*

/*
 * cocaine-rats crates/loom-mesh/src/storage.rs: the replica's record store. One CBOR file per record
 * (`<hex id>.cbor`), one per ledger stream (`.ledger-<stream>.cbor`), the replay table
 * (`.replay.cbor`) and the `.lock` held with flock for the store's life. Every write is a
 * `.pending` file written, fsynced, renamed over its target and the directory fsynced.
 */

class Limits(
    val max_records: ULong = 1024uL,
    val max_bytes: ULong = 64uL * 1024uL * 1024uL,
    /**
     * Per-live-record lifecycle growth reserve. Configured nodes enforce an encoded schema bound;
     * replay receives a separate full-table reserve. This is logical file-byte capacity, not a
     * physical disk-space guarantee.
     */
    val lifecycle_headroom_bytes: ULong = 16_384uL,
) {
    fun validate() {
        if (max_records !in 1uL..1024uL || max_bytes !in 1024uL..1024uL * 1024uL * 1024uL ||
            lifecycle_headroom_bytes > 1024uL * 1024uL || lifecycle_headroom_bytes > max_bytes
        ) error("storage limits")
    }

    companion object {
        val fields = s_["max_records", "max_bytes", "lifecycle_headroom_bytes"]

        /** serde's derived `Deserialize`, `default` and `deny_unknown_fields`. */
        fun deserialize(value: Any): Limits {
            val f = deserialize_struct(value, fields)
            val d = Limits()
            return Limits(
                f[0]?.deserialize_u64("max_records") ?: d.max_records,
                f[1]?.deserialize_u64("max_bytes") ?: d.max_bytes,
                f[2]?.deserialize_u64("lifecycle_headroom_bytes") ?: d.lifecycle_headroom_bytes,
            )
        }
    }
}

class Entry(
    val accepted: ULong,
    /** producer, epoch, sequence */
    val slot: Join<String, Twin<ULong>>,
    val id: Id,
    var segment: Segment?,
    var receipt: Receipt?,
    var settlement: Settlement?,
    var handoff: Handoff?,
) {
    fun clone(): Entry = Entry(accepted, slot, id, segment, receipt, settlement, handoff)

    fun encode(): ByteArray {
        val fields = mutableListOf<Item>(
            Item.Str(if (settlement != null) "loom-record/v2" else "loom-record/v1"),
            Item.Num(accepted.toLong()),
            Item.Str(slot.a),
            Item.Num(slot.b.a.toLong()),
            Item.Num(slot.b.b.toLong()),
            Item.Bin(id),
            Item.Bin(segment?.to_bytes() ?: ByteArray(0)),
            Item.Bin(receipt?.to_bytes() ?: ByteArray(0)),
        )
        settlement?.let {
            fields.add(Item.Bin(it.to_bytes()))
            fields.add(Item.Bin(handoff?.to_bytes() ?: ByteArray(0)))
        }
        val body = itemArrayOf(fields)
        return Cbor.encode(itemArrayOf(body, Item.Bin(sha256(Cbor.encode(body)))))
    }

    fun verify(producers: Map<String, ByteArray>, archive: Member?, lifecycle: Config?, crypto: IpnsCrypto) {
        settlement?.verify(lifecycle ?: error("settlement trust missing"), id, crypto)
        handoff?.let { h ->
            h.verify(lifecycle ?: error("handoff trust missing"), (settlement ?: error("unsettled handoff")).completion, segment, crypto)
            if (!h.id.contentEquals(id) || !same_slot(h.slot, slot)) error("stored handoff binding")
        }
        if (slot.a !in producers) error("stored unknown producer")
        val s = segment
        if (s != null) {
            s.verify(producers, crypto)
            if (!s.id().contentEquals(id) || !same_slot(s.slot(), slot)) error("stored segment binding")
        } else if (settlement != null) {
            if (handoff == null) error("settled tombstone requires GCS handoff")
        } else if (receipt == null) error("unreceipted tombstone")
        // Read compatibility covers retained legacy bytes; never infer a settlement or external
        // commit for a pre-gate legacy deletion.
        else if (lifecycle != null && !lifecycle.legacy_archive_test_only) error("legacy tombstone requires test-only mode")
        receipt?.let { r ->
            if (!r.archival) error("nonarchival receipt")
            r.verify(archive ?: error("archive trust missing"), id, crypto)
        }
    }

    companion object {
        fun decode(data: ByteArray): Entry {
            val outer = array(borg.trikeshed.loom.decode(data), 2)
            if (!fixed(outer[1], 32).contentEquals(sha256(Cbor.encode(outer[0])))) error("record checksum")
            val a = outer[0] as? Item.Arr ?: error("record schema")
            val v2 = a.size > 0 && (a[0] as? Item.Str)?.value == "loom-record/v2"
            array(outer[0], if (v2) 10 else 8)
            tag(a[0], if (v2) "loom-record/v2" else "loom-record/v1")
            val segment = bytes(a[6])
            val receipt = bytes(a[7])
            val settlement = if (v2) Settlement.from_bytes(bytes(a[8])) else null
            val handoff = if (v2 && bytes(a[9]).isNotEmpty()) Handoff.from_bytes(bytes(a[9])) else null
            val accepted = number(a[1])
            val slot = text(a[2]) j (number(a[3]) j number(a[4]))
            val id = fixed(a[5], 32)
            return Entry(
                accepted, slot, id,
                if (segment.isEmpty()) null else Segment.from_bytes(segment),
                if (receipt.isEmpty()) null else Receipt.from_bytes(receipt),
                settlement, handoff,
            ).also { label(it.slot.a) }
        }

        fun same_slot(a: Join<String, Twin<ULong>>, b: Join<String, Twin<ULong>>): Boolean =
            a.a == b.a && a.b.a == b.b.a && a.b.b == b.b.b
    }
}

class Store(
    val root: String,
    val ring: FunctionalUringFacade,
    /** `_lock`: the `.lock` descriptor, flocked until [close]. */
    val lock: Int,
    val limits: Limits,
    val producers: Map<String, ByteArray>,
    val archive: Member?,
    val lifecycle: Config?,
    val crypto: IpnsCrypto,
) : AutoCloseable {
    /** By lowercase hex id: the order of Rust's `BTreeMap<Id, Entry>`. */
    val entries = HashMap<String, Entry>()
    val slots = HashMap<Triple<String, ULong, ULong>, Id>()
    /** Per-stream serial ledger: most recent settled + unsettled versions, and the file's size. */
    val ledgers = HashMap<String, Join<LedgerSlots, ULong>>()
    var used = 0uL
    var replay_bytes = 0uL
    var replay_hash = ByteArray(32)
    var failed = false

    fun ingest(segment: Segment): Id {
        healthy()
        segment.verify(producers, crypto)
        val id = segment.id()
        slots[key(segment.slot())]?.let { previous ->
            if (!previous.contentEquals(id)) error("producer slot conflict")
            get(id)
            return id
        }
        if (entries.size.toULong() >= limits.max_records) error("storage count")
        publish(Entry(now(), segment.slot(), id, segment, null, null, null))
        slots[key(segment.slot())] = id
        return id
    }

    fun replay_reserve(): ULong = (lifecycle?.let { required_replay_bytes(it) } ?: 0uL).let { if (it > replay_bytes) it - replay_bytes else 0uL }

    fun entry_reserve(entry: Entry): ULong {
        if (entry.segment == null) return 0uL
        val initial = entry.clone()
        initial.receipt = null
        initial.settlement = null
        initial.handoff = null
        val growth = (entry.encode().size - initial.encode().size).toULong()
        return if (limits.lifecycle_headroom_bytes > growth) limits.lifecycle_headroom_bytes - growth else 0uL
    }

    fun publish(entry: Entry) {
        healthy()
        entry.verify(producers, archive, lifecycle, crypto)
        val data = entry.encode()
        val previous = entries[entry.id.toHexString()]?.encode()?.size?.toULong() ?: 0uL
        val projected = used - previous + data.size.toULong()
        val reserved = entries.values.filter { !it.id.contentEquals(entry.id) }.sumOf { entry_reserve(it) } + entry_reserve(entry)
        if (data.size > MAX_WIRE || projected > limits.max_bytes) error("storage capacity")
        if (projected + reserved + replay_reserve() > limits.max_bytes) error("lifecycle headroom")
        if (runCatching { commit_path(path(entry.id), data) }.isFailure) {
            failed = true
            error("durable write failed; store closed")
        }
        used = used - previous + data.size.toULong()
        entries[entry.id.toHexString()] = entry
    }

    fun record_archive(id: Id, receipt: Receipt) {
        val entry = read_entry(id)
        receipt.verify(archive ?: error("archive trust missing"), id, crypto)
        if (!receipt.archival) error("archive receipt required")
        // Keep the first valid proof; no unbounded historical ACK collection.
        if (entry.receipt == null) {
            entry.receipt = receipt
            publish(entry)
        }
    }

    fun delete_archived(id: Id, min_age_secs: ULong) {
        if (lifecycle?.let { it.legacy_archive_test_only && it.settlement_authority == null && it.gcs == null } != true)
            error("legacy deletion requires test-only opt-in")
        val entry = read_entry(id)
        entry.verify(producers, archive, lifecycle, crypto)
        if (entry.receipt == null || now().let { it < entry.accepted || it - entry.accepted < min_age_secs }) error("deletion age or handoff")
        if (entry.segment != null) {
            entry.segment = null
            publish(entry)
        }
    }

    fun handoff(id: Id): Handoff? = read_entry(id).handoff

    fun record_handoff(id: Id, handoff: Handoff) {
        val entry = read_entry(id)
        entry.handoff?.let { previous ->
            if (!previous.to_bytes().contentEquals(handoff.to_bytes())) error("conflicting handoff")
            return
        }
        if (entry.segment == null) error("handoff requires retained segment")
        entry.handoff = handoff
        publish(entry)
    }

    fun delete_settled(id: Id, min_age_secs: ULong) {
        val entry = read_entry(id)
        if (entry.settlement == null || entry.handoff == null || now().let { it < entry.accepted || it - entry.accepted < min_age_secs })
            error("deletion completion, age or handoff")
        if (entry.segment != null) {
            entry.segment = null
            publish(entry)
        }
    }

    fun settlement(id: Id): Settlement? = read_entry(id).settlement

    fun record_settlement(settlement: Settlement) {
        val id = settlement.completion.id
        val entry = read_entry(id)
        settlement.verify(lifecycle ?: error("settlement trust missing"), id, crypto)
        entry.settlement?.let { previous ->
            if (!previous.completion.to_bytes().contentEquals(settlement.completion.to_bytes())) error("conflicting completion")
            return
        }
        if (entry.segment == null) error("completion requires retained segment")
        entry.settlement = settlement
        publish(entry)
    }

    fun has_record(id: Id): Boolean = id.toHexString() in entries

    fun ids(): List<Id> = entries.keys.sorted().mapNotNull { key -> entries[key]!!.takeIf { it.segment != null }?.id }

    fun get(id: Id): Segment = live_segment(id) ?: error("segment archived and deleted")

    /**
     * Null means a durable, verified archive-backed tombstone, never a missing record, corruption
     * or a failed write.
     */
    fun live_segment(id: Id): Segment? = read_entry(id).segment

    fun read_entry(id: Id): Entry {
        healthy()
        val indexed = entries[id.toHexString()] ?: error("segment not found")
        val data = ring.read_bounded(path(id), MAX_WIRE)
        val record = Entry.decode(data)
        record.verify(producers, archive, lifecycle, crypto)
        if (!data.contentEquals(indexed.encode())) error("stored content changed")
        return record
    }

    /** Verify every indexed record, including tombstones omitted by [ids]. */
    fun verify_records() {
        healthy()
        if (ring.symlink_metadata(join(root, ".retired.cbor")) != null) error("unauthorized retirement metadata; recovery required")
        if (replay_bytes > 0uL) {
            val data = ring.read_bounded(join(root, ".replay.cbor"), 65536)
            if (data.size.toULong() != replay_bytes || !sha256(data).contentEquals(replay_hash)) error("replay state changed")
        } else if (ring.symlink_metadata(join(root, ".replay.cbor")) != null) error("unexpected replay state")
        for (key in entries.keys.sorted()) read_entry(entries[key]!!.id)
        for (stream in ledger_streams()) ledger(stream)
    }

    fun ledger_path(stream: String): String = join(root, ".ledger-$stream.cbor")

    fun ledger(stream: String): LedgerSlots? {
        healthy()
        label(stream)
        val slots = ledgers[stream]?.a ?: return null
        val data = ring.read_bounded(ledger_path(stream), MAX_WIRE)
        if (!data.contentEquals(slots.encode(stream))) error("stored ledger changed")
        return LedgerSlots(slots.settled, slots.pending)
    }

    fun ledger_streams(): List<String> = ledgers.keys.sorted()

    /**
     * Durably apply one transition to a stream's slots. The candidate file is re-verified through
     * the canonical rebuild before it replaces the old one.
     */
    fun <T> update_ledger(stream: String, change: (LedgerSlots) -> T): T {
        val config = lifecycle ?: error("ledger trust missing")
        val held = ledger(stream)
        val previous = if (held != null) ledgers[stream]!!.b else if (ledgers.size >= MAX_LEDGER_STREAMS) error("ledger stream count") else 0uL
        val slots = held ?: LedgerSlots()
        val before = slots.encode(stream)
        val out = change(slots)
        val data = slots.encode(stream)
        if (data.contentEquals(before)) return out
        val (named, _) = LedgerSlots.decode(data, config, crypto)
        if (named != stream) error("ledger stream binding")
        val projected = used - previous + data.size.toULong()
        val reserved = entries.values.sumOf { entry_reserve(it) }
        if (data.size > MAX_WIRE || projected + reserved + replay_reserve() > limits.max_bytes) error("ledger storage capacity")
        if (runCatching { commit_path(ledger_path(stream), data) }.isFailure) {
            failed = true
            error("durable write failed; store closed")
        }
        used = projected
        ledgers[stream] = slots j data.size.toULong()
        return out
    }

    fun healthy() {
        if (failed) error("store closed")
    }

    fun path(id: Id): String = join(root, "${id.toHexString()}.cbor")

    fun load_replay(): ByteArray? = if (replay_bytes == 0uL) null else ring.read_bounded(join(root, ".replay.cbor"), 65536)

    fun save_replay(data: ByteArray) {
        healthy()
        if (replay_bytes > 0uL) {
            val prior = ring.read_bounded(join(root, ".replay.cbor"), 65536)
            if (!sha256(prior).contentEquals(replay_hash)) {
                failed = true
                error("replay state changed")
            }
        }
        if (data.size > 65536 || used - replay_bytes + data.size.toULong() > limits.max_bytes) error("replay storage capacity")
        if (runCatching { commit_path(join(root, ".replay.cbor"), data) }.isFailure) {
            failed = true
            error("replay persistence failed")
        }
        used = used - replay_bytes + data.size.toULong()
        replay_hash = sha256(data)
        replay_bytes = data.size.toULong()
    }

    /**
     * The directory handle is acquired and validated before any record is replaced; a later sync
     * failure still leaves the post-rename outcome uncertain.
     */
    fun commit_path(path: String, data: ByteArray) {
        val directory = ring.open(root, UringOp.O_RDONLY or UringOp.O_DIRECTORY or UringOp.O_NOFOLLOW, 0)
        try {
            val temp = join(root, ".pending")
            val file = ring.open(temp, UringOp.O_WRONLY or UringOp.O_CREAT or UringOp.O_EXCL or UringOp.O_NOFOLLOW, 0x180)
            try {
                ring.write_all(file, data)
                ring.sync_all(file)
                ring.rename(temp, path)
                ring.sync_all(directory)
            } finally {
                ring.close(file)
            }
        } finally {
            ring.close(directory)
        }
    }

    /** Drop: the `.lock` descriptor closes and its flock is released. */
    override fun close() {
        try {
            ring.close(lock)
        } finally {
            ring.closeNow()
        }
    }

    companion object {
        fun key(slot: Join<String, Twin<ULong>>): Triple<String, ULong, ULong> = Triple(slot.a, slot.b.a, slot.b.b)

        fun open_config(config: Config, producers: Map<String, ByteArray>, crypto: IpnsCrypto): Store {
            config.validate()
            val archive = config.archive?.let { config.member(it) } ?: config.legacy_archive_read_trust
            return open_inner(config.store, config.limits, producers, archive, config, crypto)
        }

        fun open_inner(root: String, limits: Limits, producers: Map<String, ByteArray>, archive: Member?, lifecycle: Config?, crypto: IpnsCrypto): Store {
            limits.validate()
            val ring = FunctionalUringFacade(8, openUserspaceChannelBackend(8))
            var lock = -1
            try {
                if (ring.create_dir(root, 0x1c0)) ring.sync_path(parent(root) ?: error("store parent missing"))
                val meta = ring.symlink_metadata(root) ?: error("storage I/O failure")
                if (meta.stx_mode and UringOp.S_IFMT != UringOp.S_IFDIR || meta.stx_uid != platformGeteuid() || meta.stx_mode and 0x3f != 0)
                    error("store directory must be owned and mode 0700")
                lock = ring.open(join(root, ".lock"), UringOp.O_RDWR or UringOp.O_CREAT or UringOp.O_NOFOLLOW, 0x180)
                if (platformFlock(lock, UringOp.LOCK_EX or UringOp.LOCK_NB) != 0) error("store already owned")
                val s = Store(root, ring, lock, limits, producers, archive, lifecycle, crypto)
                for ((raw, type) in ring.read_dir(root)) {
                    val name = raw.decodeToString()
                    if (name == ".lock") continue
                    val kind = if (type != UringOp.DT_UNKNOWN) type
                        else (ring.symlink_metadata(join(root, name)) ?: error("storage I/O failure")).stx_mode.let { if (it and UringOp.S_IFMT == UringOp.S_IFREG) UringOp.DT_REG else 0 }
                    if (kind != UringOp.DT_REG) error("invalid store entry")
                    val stream = runCatching { raw.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
                        ?.takeIf { it.startsWith(".ledger-") && it.endsWith(".cbor") && it.length >= 13 }?.let { it.substring(8, it.length - 5) }
                    when {
                        name == ".pending" -> {
                            ring.remove_file(join(root, name))
                            ring.sync_path(root)
                            continue
                        }
                        name == ".replay.cbor" -> {
                            val data = ring.read_bounded(join(root, name), 65536)
                            decode(data)
                            s.replay_hash = sha256(data)
                            s.replay_bytes = data.size.toULong()
                            s.used += s.replay_bytes
                        }
                        name == ".retired.cbor" -> error("unauthorized retirement metadata; recovery required")
                        stream != null -> {
                            if (s.ledgers.size >= MAX_LEDGER_STREAMS) error("ledger stream count")
                            val config = s.lifecycle ?: error("ledger trust missing")
                            val data = ring.read_bounded(join(root, name), MAX_WIRE)
                            val (named, slots) = LedgerSlots.decode(data, config, crypto)
                            if (named != stream) error("ledger filename mismatch")
                            s.used += data.size.toULong()
                            s.ledgers[named] = slots j data.size.toULong()
                        }
                        else -> {
                            if (s.entries.size.toULong() >= s.limits.max_records) error("storage count")
                            val data = ring.read_bounded(join(root, name), MAX_WIRE)
                            val record = Entry.decode(data)
                            record.verify(s.producers, s.archive, s.lifecycle, crypto)
                            if (!raw.contentEquals("${record.id.toHexString()}.cbor".encodeToByteArray())) error("store filename mismatch")
                            if (s.slots.put(key(record.slot), record.id) != null) error("stored slot conflict")
                            s.used += data.size.toULong()
                            s.entries[record.id.toHexString()] = record
                        }
                    }
                    if (s.used > s.limits.max_bytes) error("storage capacity")
                }
                return s
            } catch (failure: Throwable) {
                if (lock >= 0) runCatching { ring.close(lock) }
                ring.closeNow()
                throw failure
            }
        }

        /** Rust `Path::parent`: null for the root or an empty path. */
        fun parent(path: String): String? {
            val trimmed = path.trimEnd('/')
            if (trimmed.isEmpty()) return null
            val slash = trimmed.lastIndexOf('/')
            return if (slash < 0) "" else trimmed.substring(0, slash).trimEnd('/').ifEmpty { if (path.startsWith('/')) "/" else "" }
        }
    }
}

/** At most [max] bytes of a regular file, opened without following a symlink and without blocking. */
fun FunctionalUringFacade.read_bounded(path: String, max: Int): ByteArray {
    val fd = open(path, UringOp.O_RDONLY or UringOp.O_NOFOLLOW or UringOp.O_NONBLOCK, 0)
    try {
        val meta = metadata(fd)
        if (meta.stx_mode and UringOp.S_IFMT != UringOp.S_IFREG || meta.stx_size > max.toULong()) error("file size or type")
        val data = read_to_end(fd, max + 1)
        if (data.size > max) error("file size")
        return data
    } finally {
        close(fd)
    }
}

/*
 * The std::fs calls storage.rs makes, each one operation on the ring. lib.rs maps every failed
 * effect (`impl From<std::io::Error> for Error`) to "storage I/O failure".
 */

/** The operation's result, or -errno; a submission the facade refuses to build is -EINVAL. */
fun FunctionalUringFacade.errno(submission: () -> UringSubmission): Int = try {
    execute(submission())
} catch (refused: IllegalArgumentException) {
    -22
} catch (failed: IOException) {
    -5
}

fun FunctionalUringFacade.io(submission: () -> UringSubmission): Int =
    errno(submission).also { if (it < 0) error("storage I/O failure") }

/** `OpenOptions::open`, with O_CLOEXEC as std always adds it. */
fun FunctionalUringFacade.open(path: String, flags: Int, mode: Int): Int = io { Submissions.openat(path, flags or UringOp.O_CLOEXEC, 1, mode) }

fun FunctionalUringFacade.close(fd: Int) {
    io { Submissions.close(fd, 1) }
}

/** `File::metadata`. */
fun FunctionalUringFacade.metadata(fd: Int): Statx {
    val statx = Submissions.statx(fd, "", UringOp.AT_EMPTY_PATH, UringOp.STATX_BASIC_STATS, 1)
    io { statx }
    return Statx.of(statx)
}

/** `fs::symlink_metadata`: null where the path does not exist. */
fun FunctionalUringFacade.symlink_metadata(path: String): Statx? {
    val statx = try {
        Submissions.statx(UringOp.AT_FDCWD, path, UringOp.AT_SYMLINK_NOFOLLOW, UringOp.STATX_BASIC_STATS, 1)
    } catch (refused: IllegalArgumentException) {
        error("storage I/O failure")
    }
    val result = errno { statx }
    if (result == -2) return null
    if (result < 0) error("storage I/O failure")
    return Statx.of(statx)
}

/** `take(limit).read_to_end`. */
fun FunctionalUringFacade.read_to_end(fd: Int, limit: Int): ByteArray {
    var bytes = ByteArray(minOf(limit, 8192))
    var size = 0
    while (size < limit) {
        if (size == bytes.size) bytes = bytes.copyOf(minOf(limit.toLong(), size * 2L).toInt())
        val count = io { UringSubmission(UringOp.READ, fd, 0, bytes.size - size, size.toLong(), userData = 1, buffer = ByteBuffer.wrap(bytes, size, bytes.size - size)) }
        if (count == 0) break
        size += count
    }
    return bytes.copyOf(size)
}

/** `write_all` from offset 0 of a new file. */
fun FunctionalUringFacade.write_all(fd: Int, data: ByteArray) {
    var at = 0
    while (at < data.size) {
        val count = io { UringSubmission(UringOp.WRITE, fd, 0, data.size - at, at.toLong(), userData = 1, buffer = ByteBuffer.wrap(data, at, data.size - at)) }
        if (count == 0) error("storage I/O failure")
        at += count
    }
}

/** `File::sync_all`. */
fun FunctionalUringFacade.sync_all(fd: Int) {
    io { Submissions.fsync(fd, 1) }
}

/** `File::open(path)?.sync_all()`. */
fun FunctionalUringFacade.sync_path(path: String) {
    val fd = open(path, UringOp.O_RDONLY, 0)
    try {
        sync_all(fd)
    } finally {
        close(fd)
    }
}

/** `fs::rename`. */
fun FunctionalUringFacade.rename(from: String, to: String) {
    io { Submissions.renameat(UringOp.AT_FDCWD, from, UringOp.AT_FDCWD, to, 0, 1) }
}

/** `fs::remove_file`. */
fun FunctionalUringFacade.remove_file(path: String) {
    io { Submissions.unlinkat(UringOp.AT_FDCWD, path, 0, 1) }
}

/** `DirBuilder::new().mode(mode).create(path)`: false where the path already exists. */
fun FunctionalUringFacade.create_dir(path: String, mode: Int): Boolean {
    val result = errno { Submissions.mkdirat(UringOp.AT_FDCWD, path, mode, 1) }
    if (result == -17) return false
    if (result < 0) error("storage I/O failure")
    return true
}

/** `fs::read_dir`: each entry's name bytes and d_type, without `.` and `..`. */
fun FunctionalUringFacade.read_dir(path: String): List<Join<ByteArray, Int>> {
    val fd = open(path, UringOp.O_RDONLY or UringOp.O_DIRECTORY, 0)
    try {
        val entries = ArrayList<Join<ByteArray, Int>>()
        val buffer = ByteArray(32768)
        while (true) {
            val count = io { Submissions.getdents(fd, ByteBuffer.wrap(buffer, 0, buffer.size), 1) }
            if (count == 0) return entries
            val records = ByteBuffer.wrap(buffer, 0, count).order(ByteOrder.nativeOrder())
            var at = 0
            while (at < count) {
                val reclen = records.getShort(at + 16).toInt() and 0xffff
                val type = buffer[at + 18].toInt() and 0xff
                var end = at + 19
                while (buffer[end] != 0.toByte()) end++
                val name = buffer.copyOfRange(at + 19, end)
                if (!(name.size == 1 && name[0] == '.'.code.toByte()) && !(name.size == 2 && name[0] == '.'.code.toByte() && name[1] == '.'.code.toByte()))
                    entries.add(name j type)
                at += reclen
            }
        }
    } finally {
        close(fd)
    }
}
