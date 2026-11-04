package borg.trikeshed.narsese

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.StorageDurability
import kotlinx.coroutines.CancellationException

enum class KgRetentionIssue { MISSING_CAS, BOUNDED_READER_UNAVAILABLE, LIMIT, IDENTITY, PUT, SYNC, READBACK }

/** A failed import retention command admits no assertions and does not stop the bag owner. */
class KgRetentionRefused(val issue: KgRetentionIssue, cause: Throwable? = null) :
    IllegalArgumentException("KG retention refused: ${issue.name}", cause)

/** Bounded immutable payloads for the existing bag channel; this is not a second corpus owner. */
object KgRetention {
    const val MAX_SOURCE_CHARS = 65_536
    const val MAX_ARTIFACT_BYTES = 256 * 1024
    const val MAX_DEFINITIONS = 2048
    const val MAX_TOTAL_BYTES = 4 * 1024 * 1024

    class Artifact(val cid: ContentId, bytes: ByteArray) {
        private val content = bytes.also {
            if (it.size > MAX_ARTIFACT_BYTES) throw KgRetentionRefused(KgRetentionIssue.LIMIT)
        }.copyOf()
        val size: Int get() = content.size
        fun bytes(): ByteArray = content.copyOf()
    }

    class Batch(val source: Artifact, definitions: Series<Artifact>) {
        // Capture the oracle before channel admission; a caller cannot replace later elements.
        private val entries = run {
            if (definitions.size !in 0..MAX_DEFINITIONS) throw KgRetentionRefused(KgRetentionIssue.LIMIT)
            val captured = ArrayList<Artifact>(definitions.size)
            for (artifact in definitions.view) captured.add(artifact)
            captured.toSeries()
        }
        val definitions: Series<Artifact> get() = entries
        val artifacts: Series<Artifact> get() = (entries.size + 1) j { if (it == 0) source else entries[it - 1] }
    }

    fun prepare(text: String, mapped: List<KgNalBridge.NalMapped>): Batch {
        if (text.length > MAX_SOURCE_CHARS || mapped.size > MAX_DEFINITIONS)
            throw KgRetentionRefused(KgRetentionIssue.LIMIT)
        val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
        if (bytes.size > MAX_ARTIFACT_BYTES) throw KgRetentionRefused(KgRetentionIssue.LIMIT)
        val source = Artifact(ContentId.of(bytes), bytes)
        val definitions = LinkedHashMap<ContentId, Artifact>()
        var total = source.size.toLong()
        for (statement in mapped) {
            val definition = statement.propositionBytes(source.cid.value)
            if (definition.size > MAX_ARTIFACT_BYTES) throw KgRetentionRefused(KgRetentionIssue.LIMIT)
            val cid = ContentId.of(definition)
            if (cid !in definitions) {
                total += definition.size
                if (total > MAX_TOTAL_BYTES) throw KgRetentionRefused(KgRetentionIssue.LIMIT)
                definitions[cid] = Artifact(cid, definition)
            }
        }
        return Batch(source, definitions.values.toSeries())
    }

    /** Called only by the bag's existing single writer, before its retention acknowledgement. */
    fun retain(cas: CasStore?, batch: Batch): StorageDurability {
        val store = cas ?: throw KgRetentionRefused(KgRetentionIssue.MISSING_CAS)
        val reader = store as? BoundedCasReader ?: throw KgRetentionRefused(KgRetentionIssue.BOUNDED_READER_UNAVAILABLE)
        if (batch.definitions.size > MAX_DEFINITIONS || batch.source.size > MAX_ARTIFACT_BYTES)
            throw KgRetentionRefused(KgRetentionIssue.LIMIT)
        var total = 0L
        val ids = HashSet<ContentId>()
        // Check the entire batch before the first write, including publicly constructed batches.
        for (artifact in batch.artifacts.view) {
            total += artifact.size
            if (artifact.size > MAX_ARTIFACT_BYTES || total > MAX_TOTAL_BYTES)
                throw KgRetentionRefused(KgRetentionIssue.LIMIT)
            if (!ids.add(artifact.cid) || ContentId.of(artifact.bytes()) != artifact.cid)
                throw KgRetentionRefused(KgRetentionIssue.IDENTITY)
        }
        for (artifact in batch.artifacts.view) step(KgRetentionIssue.PUT) {
            check(store.put(artifact.bytes()) == artifact.cid) { "CAS acknowledgement differs from payload CID" }
        }
        step(KgRetentionIssue.SYNC) { store.sync() }
        for (artifact in batch.artifacts.view) step(KgRetentionIssue.READBACK) {
            val readback = checkNotNull(reader.getBounded(artifact.cid, artifact.size)) { "Retained KG artifact unavailable" }
            check(readback.size == artifact.size && ContentId.of(readback) == artifact.cid &&
                readback.contentEquals(artifact.bytes())) { "Retained KG artifact differs from its expected bytes" }
        }
        return store.durability
    }

    private inline fun step(issue: KgRetentionIssue, action: () -> Unit) {
        try { action() } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { throw KgRetentionRefused(issue, failure) }
    }
}
