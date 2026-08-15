# Funnelmap-Assisted Linked CAS Signature Merges (Arbitrary N-Way)

As the code presents it, August 14 2026. Every claim cites current source.
Nothing here is aspirational; where a limit exists it is stated as a limit.

Consumers:  MemoryStore.linkMatch (src/commonMain/kotlin/borg/trikeshed/memory/MemoryStore.kt:142)
Evidence:   FunnelResidualMerge57WayEvidenceTest (src/jvmTest/kotlin/borg/trikeshed/cas/)


## 0. What "arbitrary-way" means

The pipeline signature is N-agnostic:

    fun merge(sources: Series<LineSpine>, masterFunnel: FunnelHashIndex<String>): MergeReceipt
                                       (FunnelResidualMerge.kt:299)

`Series<LineSpine>` is any N — 2-way, 57-way, 77-way. The 57-way evidence test
is simply the largest measured run; nothing in the code fixes N.


## 1. The line algebra (Ring 1 — per-line identity)

Source: src/commonMain/kotlin/borg/trikeshed/cas/LineCas.kt

Every text line, in order:

    1. trim()                                  (LineCas.spine, LineCas.kt:64-68;
                                                empty-after-trim lines are dropped)
    2. ContentId = sha256:<64 hex>             (LineCas.contentOf, LineCas.kt:57-58)
    3. NeighborStamp = prevHex || nextHex      (LineCas.stamp, LineCas.kt:53-54)
       - 2 hex chars per side (1 byte/side)    (NEIGHBOR_HEX_LEN = 2, LineCas.kt:40)
       - document edges use sentinel "00",
         not hash-of-empty                     (EDGE_HEX, LineCas.kt:43)
    4. linkedKey = "<stamp>:<contentHex>"      (LineNode.linkedKey, LineCas.kt:219)

One line of a document:

    data class LineNode(contentCid, stamp, ordinal)     (LineCas.kt:214-218)

A document is a lazy Series of these — `typealias LineSpine = Series<LineNode>`
(LineCas.kt:225). The document itself has a fingerprint: `spineCid` = sha256
over the ordered linkedKeys (LineCas.kt:115-124), so two documents that share
line content AND neighborhoods share a spineCid even if formatting differed
before trim.

Graded pairwise match (LineCas.matchGrade, LineCas.kt:132-142):

    same contentCid, then compare stamps:
      prev & next both equal  -> MatchGrade.LINKED         (strength 3)
      prev only               -> MatchGrade.PARTIAL_PREV   (strength 2)
      next only               -> MatchGrade.PARTIAL_NEXT   (strength 2)
      neither                 -> MatchGrade.CONTENT_ONLY   (strength 1)

Strength ordering and threshold: MatchGrade.strength / meets() (LineCas.kt:243-251).
Confidence mapping: LINKED->CONFIRMED, PARTIAL->PROVISIONAL,
CONTENT_ONLY->CANDIDATE (confidenceOf, LineCas.kt:458-462). Proximity score
weights: linked 1.0, partial 0.45, content-only 0.12 (LineCas.proximity,
LineCas.kt:173-179; same weights in rampScore, LineCas.kt:468-472).

Why the stamp exists — the false-positive ladder as the KDoc states it
(LineCas.kt:21-27): content-only match FP ~ 1 among content collisions
(boilerplate); +8 bits one side ~ 1/256; +16 bits both sides (LINKED) ~
1/65536. SHA-256 collisions are neglected; the FP problem is structural reuse
of identical lines, not cryptography.


## 2. The funnelmap (negative-query accelerator)

Source: src/commonMain/kotlin/borg/trikeshed/collections/associative/FunnelHashIndex.kt

A frozen, seed-hash, multi-level filter over a key set:

    FunnelHashIndex.build(keys: Series<K>, seed: Long,
                          slack: Double = 0.20)          (companion build, :64)
    fun contains(key: K): Boolean                          (:228)
    fun get(key: K): Int?                                  (:190)
    fun probeDistribution(): Series<Int>                   (:237)

It is built once from a key set and never mutated. In this pipeline the keys
are master's per-line contentCid.hex strings, and the workload is
negative-query-heavy: almost every probe is "master already has it". A MISS is
the signal, not a hit — that is what makes the merge cost track the residual
set instead of N full trees.


## 3. Stages, as wired

All six stages exist on disk and compose; build gate green
(`./gradlew jvmMainClasses`, Aug 14 2026).

                        text
                         |
                +------------------+
                | S1  spine        |  LineCas.spine            (LineCas.kt:64)
                +------------------+
                         |  Series<LineNode>  (contentCid + stamp + ordinal)
                +------------------+
                | S2  master       |  FunnelResidualMerge.     (FRM.kt:324)
                |     funnel       |  buildMasterFunnel(spine)
                +------------------+  = FunnelHashIndex over contentCid.hex
                         |
         +---------------+---------------+ ... N sources
         |               |               |
    +---------+     +---------+     +---------+
    | S3 probe|     | S3 probe|     | S3 probe|  FunnelResidualMerge.residualsOf
    +---------+     +---------+     +---------+  (FRM.kt:134): each source line
         |               |               |        that MISSES the funnel becomes
         |  ResidualSpine (Series<LineAtom>)      a LineAtom; hits are inherited
         +---------------+---------------+        and dropped
                         |
                +------------------+
                | S4  topology     |  topologyOf (FRM.kt:171): group-by mini64
                |     (DRY map)    |  across all N residual spines
                +------------------+
                         |  Topology = Series<Cluster>
                +------------------+
                | S5  grading      |  gradeClusters (FRM.kt:217)
                +------------------+
                         |  GradedTopology
                +------------------+
                | S6  survivor     |  mergeResiduals (FRM.kt:255)
                |     merge        |  keep NOVEL + RELOCATED
                +------------------+
                         |
                    MergeReceipt  (FRM.kt:113-119:
                      kept / dropped / novelCount /
                      relocatedCount / inheritedCount)

(FRM.kt = src/commonMain/kotlin/borg/trikeshed/cas/FunnelResidualMerge.kt)


### Stage 3 — residual atoms (FRM.kt:134-156)

    fun residualsOf(source: LineSpine,
                    masterFunnel: FunnelHashIndex<String>,
                    sourceIdx: SourceIdx): ResidualSpine

Per source line: funnel.contains(contentCid.hex) == true -> inherited, drop.
A miss becomes:

    LineAtom(
      mini64          = mini64Of(contentCid),        // Long fold, see §6
      neighborPrefix  = NeighborPrefix(short),       // 8 bits: prev nibble << 4 | next nibble
      sourceIdx, ordinal, contentCid,
    )                                                     (FRM.kt:61-67, 142-153)

Note the packing honestly: the residual atom's NeighborPrefix takes the FIRST
hex nibble of prevHex and of nextHex (hexNibble reads hex[0], FRM.kt:375-383)
— an 8-bit projection of LineCas's 16-bit NeighborStamp. Coarser on purpose:
it is a relocation discriminator, not an identity.


### Stage 4 — cross-source topology / DRY detection (FRM.kt:171-200)

    fun topologyOf(residuals: Series<ResidualSpine>): Topology

One pass over all N residual spines, bucketed by mini64 (raw Long). Each
distinct mini64 becomes:

    Cluster(mini64, contentCid, copies: Series<CopyAddress>)
    CopyAddress(sourceIdx, ordinal, neighborPrefix)      (FRM.kt:73-84)

Lines shared across patches collapse to ONE cluster with multiple copy
addresses; lines unique to one patch stay singletons. This is the DRY map:
the topology that emerges between multiple copies of the same line.


### Stage 5 — grading (FRM.kt:217-240)

    fun gradeClusters(topology, masterFunnel): GradedTopology

    INHERITED        funnel has the contentCid          -> drop (master owns it)
    NOVEL            not in master, exactly 1 copy      -> keep (fast-apply)
    INHERITED_CROSS  not in master, k>1 copies,
                     ALL neighborPrefix equal           -> drop (shared boilerplate)
    RELOCATED        not in master, k>1 copies,
                     neighborPrefix differs             -> surface (context moved;
                                                              3-way, driver picks)


### Stage 6 — survivor merge (FRM.kt:255-276)

    fun mergeResiduals(graded): MergeReceipt

Keeps NOVEL + RELOCATED, drops INHERITED + INHERITED_CROSS, counts each.
Cost shape, per the KDoc (FRM.kt:28-30): O(|union residual|), not Σ|patch| —
with N mostly-overlapping agent patches, the residual set is the DRY harvest,
and merge cost tracks that, not N trees.


## 4. The LineCasIndex variant (single-index, per-aperture)

LineCasIndex (LineCas.kt:283-428) is the same idea with the index and the
funnel living together:

    ingestSpine(spine)     rebuilds funnel = FunnelHashIndex.build(
                           byContent.keys, 0L) on EVERY ingest  (LineCas.kt:305)
    linkMatch(probe, minGrade = LINKED)
                           funnel gate first (miss -> empty),
                           then graded candidate scan        (LineCas.kt:314-332)
    linkDensity(probe)     per-document OverlapCounts        (LineCas.kt:338-372)
    residualsOf(probe)     nodes whose hex MISS the funnel   (LineCas.kt:411-427)
    residualDensity(probe, aperture)
                           regional density per RTS band:
                           L0=1, L1=4, L2=16, L3=64 regions (LineCas.kt:381-405)

This is the shape MemoryStore consumes (MemoryStore.kt:49,142) — recall
queries graded LINKED-first, funnel as the negative gate.

Production-wired today: MemoryStore.linkMatch via LineCasIndex.
FunnelResidualMerge.merge: composed and measured in jvmTest; its production
consumer (the survivor applicator that turns receipt.kept into git commits)
does not exist yet — see §7.


## 5. Measured evidence (57-way, as recorded in the test header)

src/jvmTest/kotlin/borg/trikeshed/cas/FunnelResidualMerge57WayEvidenceTest.kt:3-12

    MEASURED
    sources.size        = 57
    receipt.novelCount  = 7
    receipt.relocatedCount = 0        <- RELOCATED lane never non-zero yet
    receipt.inheritedCount  = 1
    receipt.kept.size   = 7
    receipt.dropped.size = 1
    residualAtoms       = 14
    totalSourceAtoms    = 2294

Construction: 7 sources share one identical novel line; 7 add unique novel
lines; 7 move the same master line to the same place; 36 are byte-identical
to master. Note what the zeros teach: the 7 identical moves grade
INHERITED_CROSS (same stamp on every copy), not RELOCATED — RELOCATED fires
only when the SAME content sits at DIFFERENT neighborhoods across copies.
2294 source atoms collapse to 14 residual atoms: a 164x probe reduction on
this corpus.


## 6. Honest limits (as the code presents them)

1. Receipt is classification, not application. merge() returns MergeReceipt;
   it does not touch a worktree, does not pick winners, does not emit commits.
   The KDoc scope note (FRM.kt:32-35): the funnel collapses duplicates, it
   does not pick winners on true same-locus content conflicts.

2. mini64 folds 8 of the 64 hex chars (every 8th char, h*31 + code,
   FRM.kt:362-372). It is a group-by convenience, not a digest; the full
   contentCid is retained on every atom for disambiguation. A mini64
   collision would merge two clusters until grading re-separates them via
   the funnel check on contentCid.

3. Residual NeighborPrefix is 8 bits (first nibble each side) vs LineCas's
   16-bit stamp — relocation detection is deliberately coarse; two moves that
   share first nibbles both side grade INHERITED_CROSS.

4. LineCasIndex.ingestSpine rebuilds the whole funnel per ingest
   (LineCas.kt:305) — fine for read-mostly indexes, O(|keys|) per new doc.

5. RELOCATED has never fired in the recorded evidence (relocatedCount = 0 in
   the only measured run). The lane is wired and graded; it is unexercised
   by measurement.


## 7. What the write-up does not claim

No survivor applicator exists: nothing maps receipt.kept (NOVEL fast-apply,
RELOCATED 3-way) into git operations. That consumer is the named next layer
— the merge-driver that turns this topology into commits — and until it
lands, the pipeline's deliverable is the MergeReceipt artifact itself.
