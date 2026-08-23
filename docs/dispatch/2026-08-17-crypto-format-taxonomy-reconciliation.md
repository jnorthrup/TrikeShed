# Crypto & format taxonomy reconciliation — 2026-08-17 dispatch (capped 2026-08-23)

Track: root-level `.jules-dispatch-crypto-format-taxonomy.md` + `.tasks-crypto-format-taxonomy.txt`.
Opened: 2026-08-17. Capped: 2026-08-23. This file absorbs both root files so they can be removed.

## Absorbed session map (verbatim from `.jules-dispatch-crypto-format-taxonomy.md`)

| # | Task | Session ID | Status |
|---|------|------------|--------|
| — | TAXONOMY title-only probe | 360537909355070573 | ignore (superseded by full-body dispatch) |
| 1 | TAXONOMY full spec (piped body) | 16609143232232775395 | active |

## Absorbed task (condensed from `.tasks-crypto-format-taxonomy.txt`)

TAXONOMY: Establish a taxonomical set of typealiases for all git and Pijul data formats, all common
hash and cipher types, with causal isomorphisms between them. Single file
`src/commonMain/kotlin/borg/trikeshed/taxonomy/CryptoAndFormatTaxonomy.kt`, package `borg.trikeshed.taxonomy`,
PRELOAD algebra (`Join`, `Series`, `@JvmInline` value classes; no data-class wrappers where a typealias suffices).

Scope: Git (`GitObjectId`, `GitBlobHash`, `GitTreeHash`, `GitCommitHash`, `GitRefName`, `GitRef`,
`GitPackIndexEntry`, `GitIndexMtime`, `GitTreeClean`); Pijul (`PijulPatchId = Blake3Hash`, `PijulChangePos`,
`PijulEdge`, `PijulDependency`, `PijulVertexId`, `PijulCrdtFile`); hash algebra (`HashAlgorithm` sealed
SHA256/SHA1/BLAKE3, `HashDigest`, `HashHex`, `ContentAddressable`); wire identity (`WirePeerId`,
`WirePeerAddress`, `WireInfoHash`, `WireCID`); cipher suite (`TlsCipherSuiteId`, `TlsSignatureAlgorithm`,
`TlsNamedGroup`, `StreamCipherKey`, `Nonce`); serialization (`SerializationFormat` sealed
CBOR/JSON/CONFIX/PROTOBUF, `SerializedBytes`, `CanonicalDocument`); KDoc'd causal isomorphisms
(ContentIdentity, DocumentIdentity, WireEndpoint, EncryptedPayload).

Constraints: additive-only, one file, import `Blake3Hash` from `borg.trikeshed.patch`, `ContentId` from
`borg.trikeshed.job`; no new deps, commonMain only; gate `./gradlew jvmMainClasses --console=plain`;
test `src/commonTest/kotlin/borg/trikeshed/taxonomy/CryptoAndFormatTaxonomyTest.kt` (value-class roundtrip,
digest byte counts, `ContentAddressable.from*` factories). Avoid touching daemon/, flywheel/, pijul/,
torrent/, htx/, reactor/, job/, cas/.

## Reconciliation

| # | task | disposition | evidence |
|---|---|---|---|
| — | title-only probe (360537909355070573) | SETTLE-REJECT | already recorded in `2026-08-18-drain-reconciliation.md` (superseded; CAS sha256:ad873b99…) |
| 1 | TAXONOMY full spec (16609143232232775395) | LANDED | `src/commonMain/kotlin/borg/trikeshed/taxonomy/CryptoAndFormatTaxonomy.kt` (5.9 KB, commit a1ef4f138): `:3` imports `ContentId`, `:7` `Blake3Hash`, `:46` `typealias PijulPatchId = Blake3Hash`, `:111` `fromInfoHash`, `:133` `value class WireInfoHash`. Spec'd commonTest file was not delivered (no `CryptoAndFormatTaxonomy*Test` in tree) |

Chokepoint follow-up (not a blocker): the file is on the JS ratchet —
`gradle/js-target-debt.excludes:5` `**/taxonomy/CryptoAndFormatTaxonomy.kt` — because
`fromInfoHash` (`:111`) depends on `borg.trikeshed.torrent.InfoHash`, which is itself JS-excluded.
Lifting it means either an `expect`/interface seam for `InfoHash` or moving the torrent bridge
to a jvmMain extension. Ratchet rule stands: never add entries; this one already exists.

Root files `.jules-dispatch-crypto-format-taxonomy.md` and `.tasks-crypto-format-taxonomy.txt`
are fully absorbed above and may be `git rm`'d (listed in `2026-08-23-cap-ledger.md` residue).

Rows: 2. Terminal sessions reconciled: 2. INCOMPLETE: none.
