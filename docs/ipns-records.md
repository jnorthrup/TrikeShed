# IPNS record boundary

`borg.trikeshed.ipns` implements Ed25519 IPNS V2 records following the
[IPNS record specification](https://specs.ipfs.tech/ipns/ipns-record/),
[libp2p peer IDs](https://github.com/libp2p/specs/blob/master/peer-ids/peer-ids.md), and
[DAG-CBOR](https://ipld.io/specs/codecs/dag-cbor/spec/).
The record codecs have no I/O. The publisher, journal and libp2p/DHT code in the same commonMain package use the shared uring contract. JVM signing uses the existing Bouncy Castle Ed25519 implementation;
it neither installs a provider nor invokes an external daemon. Other platforms supply the
`IpnsCrypto` coroutine-context element. IPNS record signing/verification supports Ed25519. Separately, JVM libp2p TLS authenticates both Ed25519 and RSA host identities; ECDSA and secp256k1 host identities are not supported. See [native IPNS](native-ipns.md) for runtime commands and network evidence.

| API | Contract |
|---|---|
| `IpnsCrypto.generate/sign/verify` | Public keys are raw Ed25519 32-byte keys; private keys are 32-byte seeds. |
| `IpnsKeyPair.publicKey/privateKey/name` | Defensive byte copies; record creation verifies that the seed matches the public key. |
| `IpnsName.fromPublicKey/parse` | Peer multihash identity, with CIDv1 `libp2p-key` base36 display and legacy base58 peer-ID input/output. |
| `IpnsName.multihash/routingKey` | Binary multihash; DHT key is ASCII `/ipns/` followed by that multihash. |
| `IpnsEncoding.publicKey/publicKeyRaw` | Libp2p protobuf Ed25519 public-key envelope. SHA-256 names require this envelope in record field 7. |
| `IpnsCid.raw/fromBytes/parse` | Real CIDv1 encoding; binary/text CIDv0 inputs normalize to CIDv1. Base32, base36, and base58btc multibase are supported. |
| `ContentId.toIpnsCid(codec)` | Converts the existing SHA-256 digest using an explicit block codec: `0x55u` raw or `0x71u` DAG-CBOR, for example. It does not change existing `ContentId`/HTX digest semantics. |
| `IpnsProtobuf.decode/bytes/uint/tag` | Bounded protobuf fields, canonical unsigned varints, checked lengths, and wire types 0/1/2/5. Repeated-field policy belongs to the schema. |
| `IpnsRecord.create` | Signs canonical DAG-CBOR after `ipns-signature:` and emits protobuf fields 8/9. |
| `IpnsRecord.verify(name, wire, now, crypto)` | Returns authenticated `.bytes`, `.value`, unsigned `.sequence`, `.validUntil`, and unsigned `.ttlNanos`, or throws `IllegalArgumentException`. Times are `kotlin.time.Instant`. |

Verification limits the complete record to 10 KiB, checks canonical DAG-CBOR and mandatory typed
fields, binds the public key to the requested name, verifies V2, checks legacy/V2 field agreement
when legacy value or signature fields are present, and requires EOL to be strictly after `now`.
The original signed bytes remain intact. Empty values normalize to `/ipfs/bafkqaaa`; legacy
binary CIDs normalize to `/ipfs/<CIDv1>`. Unknown canonical DAG-CBOR extensions are authenticated
and ignored. V1-only records are rejected; an obsolete V1 signature is not verified.

Records for the same name order by unsigned sequence, EOL, then unsigned lexicographic wire
bytes, matching Boxo's deterministic selection tie-break. TTL is a cache hint in nanoseconds;
`cacheUntil(receivedAt)` never exceeds signed EOL. `allowExpired = true` exists for authenticated
journal recovery so a publisher can preserve its sequence across renewal. Routing/cache reads
must retain the default expiration check. Resolver path policy and recursive resolution belong
to the calling layer; record signature validation does not fetch its value.

Verification uses the RFC 8032 Ed25519 vector and six pinned official IPIP-0428 records, plus
focused malformed-wire, name-binding, tampering, legacy-consistency, expiry, unsigned-order,
TTL, and canonical-encoding cases. See
[`src/jvmTest/resources/ipns/README.md`](../src/jvmTest/resources/ipns/README.md) for fixture
provenance and licenses. The implementation follows public protocol specifications; no upstream
implementation code was copied. Record verification alone does not establish DHT reachability,
transport authentication, or publication durability; their respective integration tests must do so.
