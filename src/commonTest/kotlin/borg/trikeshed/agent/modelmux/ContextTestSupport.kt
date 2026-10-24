package borg.trikeshed.agent.modelmux

import borg.trikeshed.cas.ContextBlockExchange
import borg.trikeshed.ipns.IpnsCid
import borg.trikeshed.ipns.toIpnsCid
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.toSeries
import kotlin.test.fail

internal fun bytesOf(text: String): ByteArray = text.encodeToByteArray()

/** The raw-codec peer identity of a local sha256 identity. */
internal fun ContentId.rawCid(): IpnsCid = toIpnsCid(0x55uL)

/** Serves only what it was given and records every request, including the ceiling the transport was told. */
internal class StubExchange(
    private val blocks: Map<IpnsCid, ByteArray> = emptyMap(),
    private val failWith: Throwable? = null,
) : ContextBlockExchange {
    val fetched = mutableListOf<Pair<IpnsCid, Int>>()

    override suspend fun fetch(cid: IpnsCid, maxBytes: Int): ByteArray {
        fetched += cid to maxBytes
        failWith?.let { throw it }
        return blocks[cid] ?: throw IllegalStateException("peer has no $cid")
    }

    override suspend fun publish(bytes: ByteArray): IpnsCid = fail("publish is not part of block reads")
    override suspend fun pin(cid: IpnsCid) = fail("pin is not part of block reads")
}

/** Any use is a test failure: proves a code path stayed off the network. */
internal object ForbiddenExchange : ContextBlockExchange {
    override suspend fun fetch(cid: IpnsCid, maxBytes: Int): ByteArray = fail("the exchange must not be called")
    override suspend fun publish(bytes: ByteArray): IpnsCid = fail("the exchange must not be called")
    override suspend fun pin(cid: IpnsCid) = fail("the exchange must not be called")
}

/** A published revision: the manifest plus every block a peer must be able to serve for it. */
internal class Revision(val manifest: BreadcrumbContextManifest, val blocks: Map<ContentId, ByteArray>) {
    val cid: ContentId get() = manifest.cid

    /** Puts every block into [cas], optionally leaving some out to model an incomplete closure. */
    fun intoCas(cas: CasStore, except: Set<ContentId> = emptySet()): CasStore {
        for ((id, bytes) in blocks) if (id !in except) check(cas.put(bytes) == id)
        return cas
    }

    fun asPeer(): StubExchange = StubExchange(blocks.mapKeys { it.key.rawCid() })
}

/** Builds a revision from (name, text) pairs. Texts with identical bytes share one block, as in a real CAS. */
internal fun revision(
    scope: String,
    vararg documents: Pair<String, String>,
    parents: List<ContentId> = emptyList(),
    author: String = "tester",
    policy: String = "policy-1",
): Revision {
    val docs = documents.map { (name, text) -> ManifestDocument.of(name, bytesOf(text)) }
    val manifest = BreadcrumbContextManifest.of(scope, policy, author, parents, docs)
    val blocks = LinkedHashMap<ContentId, ByteArray>()
    blocks[manifest.cid] = manifest.encode()
    for ((_, text) in documents) blocks[ContentId.of(bytesOf(text))] = bytesOf(text)
    return Revision(manifest, blocks)
}

/** A verified snapshot built directly from (name, text) pairs, without going through a store. */
internal fun snapshotOf(vararg documents: Pair<String, String>): ContextSnapshot {
    val rev = revision("proj", *documents)
    val docs = documents.sortedBy { it.first }.map { (name, text) -> ContextDocument(name, ContentId.of(bytesOf(text)), bytesOf(text)) }
    return ContextSnapshot(rev.cid, rev.manifest, docs.toSeries())
}
