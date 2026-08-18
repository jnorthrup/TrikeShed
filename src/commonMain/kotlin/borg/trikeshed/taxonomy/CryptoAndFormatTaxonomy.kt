package borg.trikeshed.taxonomy

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.patch.Blake3Hash
import borg.trikeshed.pijul.EdgeFlag
import kotlin.jvm.JvmInline

// ============================================================================
// GIT DATA FORMATS
// ============================================================================

enum class GitObjectType {
    BLOB, TREE, COMMIT, TAG
}

typealias GitObjectId = Join<String, GitObjectType>

@JvmInline
value class GitBlobHash(val hex: String)

@JvmInline
value class GitTreeHash(val hex: String)

@JvmInline
value class GitCommitHash(val hex: String)

@JvmInline
value class GitRefName(val name: String)

typealias GitRef = Join<GitRefName, GitCommitHash>

typealias GitPackIndexEntry = Join<GitObjectId, Join<Int, Long>>

@JvmInline
value class GitIndexMtime(val timestamp: Long)

typealias GitTreeClean = Boolean

// ============================================================================
// PIJUL DATA FORMATS
// ============================================================================

typealias PijulPatchId = Blake3Hash

typealias PijulChangePos = Int

typealias PijulEdge = Join<PijulPatchId, Join<PijulPatchId, EdgeFlag>>

@JvmInline
value class PijulDependency(val id: PijulPatchId)

typealias PijulVertexId = Join<PijulPatchId, Int>

typealias PijulCrdtFile = Join<String, Series<PijulVertexId>>

// ============================================================================
// HASH ALGORITHM TAXONOMY
// ============================================================================

/**
 * Base sealed class for Hash algorithms.
 * - SHA256: 32 bytes (FIPS 180-4)
 * - SHA1: 20 bytes (FIPS 180-4)
 * - BLAKE3: 32 bytes (BLAKE3 specification)
 *
 * Causal Isomorphisms across Protocols:
 *
 * 1. ContentIdentity: The pattern where each protocol uses a different hash
 *    to identify content.
 *    - Git uses SHA-1 (GitBlobHash / GitCommitHash)
 *    - Pijul uses BLAKE3 (PijulPatchId)
 *    - BitTorrent v2 uses SHA-256 (WireInfoHash)
 *    - IPFS uses SHA-256 (WireCID)
 *    - TrikeShed CAS uses SHA-256 (ContentId)
 *    The universal isomorphism is Join<HashAlgorithm, ByteArray> = HashDigest.
 *
 * 2. DocumentIdentity: The pattern where each protocol identifies a "version
 *    of the world".
 *    - Git HEAD is GitCommitHash
 *    - Pijul channel head is PijulPatchId
 *    - IPFS root is CID
 *    - TrikeShed is ContentId (ContentAddressable)
 *    All four conceptually map to Join<HashAlgorithm, HashHex>.
 */
sealed class HashAlgorithm(val outputSizeBytes: Int, val wireLabel: String) {
    object SHA256 : HashAlgorithm(32, "sha256")
    object SHA1 : HashAlgorithm(20, "sha1")
    object BLAKE3 : HashAlgorithm(32, "blake3")
}

typealias HashDigest = Join<HashAlgorithm, ByteArray>

@JvmInline
value class HashHex(val hex: String)

typealias ContentAddressable = Join<HashAlgorithm, HashHex>

fun ContentAddressable(alg: HashAlgorithm, hex: HashHex): ContentAddressable = alg j hex

fun Join.Companion.fromContentId(id: ContentId): ContentAddressable {
    return ContentAddressable(HashAlgorithm.SHA256, HashHex(id.hex))
}

fun Join.Companion.fromCID(cid: borg.trikeshed.htx.client.ipfs.CID): ContentAddressable {
    return ContentAddressable(HashAlgorithm.SHA256, HashHex(cid.hex()))
}

fun Join.Companion.fromInfoHash(infoHash: borg.trikeshed.torrent.InfoHash): ContentAddressable {
    return ContentAddressable(HashAlgorithm.SHA256, HashHex(infoHash.hex()))
}

// ============================================================================
// WIRE IDENTITY TYPES
// ============================================================================

/**
 * 3. WireEndpoint: The pattern for "who am I talking to".
 *    - TCP is PeerAddress(host, port)
 *    - uTP is PeerAddress + ConnectionId
 *    - WebSocket is Host + Path
 *    The universal isomorphism is Join<String, Int> (host/port) extended with
 *    protocol-specific nonce.
 */
@JvmInline
value class WirePeerId(val bytes: ByteArray)

typealias WirePeerAddress = Join<String, Int>

@JvmInline
value class WireInfoHash(val bytes: ByteArray)

@JvmInline
value class WireCID(val bytes: ByteArray)

// ============================================================================
// CIPHER SUITE TAXONOMY
// ============================================================================

/**
 * 4. EncryptedPayload: The pattern for "sealed bytes".
 *    - TLS record is Join<TlsCipherSuiteId, ByteArray>
 *    - uTP payload is Join<StreamCipherKey, ByteArray>
 *    - Pijul patch is Blake3Hash + changes
 *    The isomorphism is Join<KeyType, ByteArray>.
 */
@JvmInline
value class TlsCipherSuiteId(val id: UShort)

@JvmInline
value class TlsSignatureAlgorithm(val id: UShort)

@JvmInline
value class TlsNamedGroup(val id: UShort)

@JvmInline
value class StreamCipherKey(val key: ByteArray)

@JvmInline
value class Nonce(val bytes: ByteArray)

// ============================================================================
// SERIALIZATION FORMAT TAXONOMY
// ============================================================================

/**
 * Base sealed class for serialization formats.
 * - CBOR: RFC 8949
 * - JSON: RFC 8259
 * - CONFIX: TrikeShed canonical flat document format
 * - PROTOBUF: Protocol Buffers
 */
sealed class SerializationFormat(val mimeLabel: String, val isCanonical: Boolean) {
    object CBOR : SerializationFormat("application/cbor", true)
    object JSON : SerializationFormat("application/json", false)
    object CONFIX : SerializationFormat("application/x-confix", true)
    object PROTOBUF : SerializationFormat("application/x-protobuf", false)
}

typealias SerializedBytes = Join<SerializationFormat, ByteArray>

typealias CanonicalDocument = Join<ContentAddressable, SerializedBytes>

