#!/usr/bin/env bash
# Dispatch 15 forge+collections Jules tasks (DRY stock + RTS moneymaker).
# Run from TrikeShed repo root. Requires: jules CLI, origin/master reachable.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

dispatch() {
  local title="$1"
  local body="$2"
  echo "=== DISPATCH: $title ==="
  jules new --repo jnorthrup/TrikeShed "$body" || echo "FAIL: $title"
  echo
}

# ── 01 ──────────────────────────────────────────────────────────────────────
dispatch "01 LineCas spine core (cas) for forge ingest" \
'CONTEXT
Forge blackboard (borg.trikeshed.forge.blackboard) lays out CausalGraphNodeIndex via ForceLayout.kt and frames with ForgeBlackboardCamera. There is no first-class line-body → CAS spine on origin/master. Collections already have ContentId (job/ContentId.kt), Series + zipWithNext (lib/Series.kt), CasManifest (cas/CasManifest.kt). Forge is the moneymaker; cas/collections must stay impeccable DRY stock — same concern.

EVIDENCE
- git cat-file -e origin/master:src/commonMain/kotlin/borg/trikeshed/cas/LineCas.kt → MISSING
- ContentId.of(bytes) exists; Series.zipWithNext exists; CasManifest is ordered CID list → root CID
- ForceLayout.kt consumes CausalGraphNodeIndex only

TASK
1. Create src/commonMain/kotlin/borg/trikeshed/cas/LineCas.kt in package borg.trikeshed.cas
2. Implement:
   - contentOf(line: String): ContentId = ContentId.of(line.trim().encodeToByteArray())
   - spine(text: String): Series of line nodes after trim + drop empty-after-trim
   - Each node: contentCid, ordinal (Int)
   - Use PRELOAD: Series via `n j { i -> ... }`, import borg.trikeshed.lib.j/get/size
3. spine must not demote to List as the public return type (Series only)
4. Do not invent merge/patch logic. No SHA of neighbors yet (task 02)
5. Keep forge imports out of cas (cas is stock)

BUILD GATE (must pass):
  ./gradlew jvmMainClasses --console=plain
No unit tests. TDD PR Deliver.'

# ── 02 ──────────────────────────────────────────────────────────────────────
dispatch "02 NeighborStamp 2-hex bidirectional on LineCas" \
'CONTEXT
LineCas (or create if missing) must stamp each line with neighbor context so link-matching CAS rejects boilerplate false positives. Spec: 2 hex chars (1 byte) from prev content CID + 2 hex from next; edge sentinel "00". Forge graph edges will use MatchGrade later; collections own the stamp algebra.

EVIDENCE
- ContentId.hex is 64 lowercase hex (job/ContentId.kt)
- Paper/rsync analogy: weak prefix filter then strong identity — not a new merge theory
- Series.zipWithNext is the neighbor pair primitive (lib/Series.kt)

TASK
1. In borg.trikeshed.cas.LineCas (create file if absent on branch):
   - NEIGHBOR_HEX_LEN = 2, EDGE_HEX = "00"
   - NeighborStamp value class: 4 lowercase hex (prev||next)
   - LineNode(contentCid, stamp, ordinal); linkedKey = stamp.hex + ":" + contentCid.hex
   - spine(text) fills stamps from adjacent content CIDs
2. KDoc: never put literal */ inside block comments (no bolt/* globs)
3. matchGrade(a,b): null if content differs; else LINKED / PARTIAL_PREV / PARTIAL_NEXT / CONTENT_ONLY with strength meets()
4. No forge dependency from cas

BUILD GATE (must pass):
  ./gradlew jvmMainClasses --console=plain
No unit tests. TDD PR Deliver.'

# ── 03 ──────────────────────────────────────────────────────────────────────
dispatch "03 FunnelHashIndex kill SHA-256; unit-cost mix64" \
'CONTEXT
FunnelHashIndex is the frozen membership structure for DRY residual / forge topo funnel maps. On origin/master it still does Sha256Pure.digest on every probe — destroys open-addressing economics. Collections must be impeccable.

EVIDENCE
- src/commonMain/kotlin/borg/trikeshed/collections/associative/FunnelHashIndex.kt imports Sha256Pure and hashes in companion + instance hash64
- collections/FunnelHashIndex.kt is a typealias; may carry dead Sha256 import
- Zero production callers today except tests; still landmine + forge dependency soon

TASK
1. Replace Sha256Pure hash64 with mix64(key.hashCode(), seed) — unit cost, deterministic
2. Remove Sha256Pure imports from associative FunnelHashIndex and public typealias file
3. KDoc honesty: this is multi-level expanding probeBound geometry, NOT paper β-bucket funnel; do NOT claim O(log² 1/δ)
4. Keep API: build(keys, seed), get, contains, probeDistribution, totalCapacity, size

BUILD GATE (must pass):
  ./gradlew jvmMainClasses --console=plain
No unit tests. TDD PR Deliver.'

# ── 04 ──────────────────────────────────────────────────────────────────────
dispatch "04 FunnelHashMap honest KDoc + delta-tuned beta" \
'CONTEXT
Production FunnelHashMap (collections/FunnelHashMap.kt) is Krapivin-shaped (levels + BETA buckets). Stringpool (couch/isam/Stringpool.kt) is the production consumer. Origin claims "achieves O(log^2(1/delta))" with fixed BETA=8 — overclaim. Collections impeccable = geometry yes, fake bounds no.

EVIDENCE
- FunnelHashMap.kt companion BETA=8, load resize size >= capacity*4/5
- Stringpool: FunnelHashMap<String, Int>()
- Paper arXiv:2501.02305 wants β ~ Θ(log 1/δ); fixed β does not inherit bound

TASK
1. Add slack: Double = 0.20 (δ); load target = 1-δ
2. beta = max(8, ceil(4 * ln(1/δ))) with δ coerced to [0.05, 0.50]; default δ keeps β=8
3. Use instance beta everywhere instead of const BETA
4. Replace KDoc: inspired-by Krapivin; NO "achieves O(log^2...)"; note remove/tombstones outside paper; cite Stringpool
5. Add probeDistribution(sample: List<K>): List<Int> for measurement
6. Do not break Stringpool default constructor usage

BUILD GATE (must pass):
  ./gradlew jvmMainClasses --console=plain
No unit tests. TDD PR Deliver.'

# ── 05 ──────────────────────────────────────────────────────────────────────
dispatch "05 ElasticHashIndex rename honesty + mix64" \
'CONTEXT
ElasticHashIndex is ordinary double hashing named "elastic". Paper elastic hashing (non-greedy, same CACM/Krapivin paper) is different. SHA-256 on probes. Collections DRY: one honest name + cheap hash.

EVIDENCE
- collections/ElasticHashIndex.kt: doubleHash via Sha256Pure; KDoc says double hashing already in body but title says Elastic
- No production callers; tests only

TASK
1. Replace SHA-256 with mix64(hashCode, seed)
2. KDoc: explicitly NOT Farach-Colton/Krapivin elastic hashing; ordinary double hash h1+i*h2 load≤0.5
3. Keep class name ElasticHashIndex for API stability (document the misnomer)

BUILD GATE (must pass):
  ./gradlew jvmMainClasses --console=plain
No unit tests. TDD PR Deliver.'

# ── 06 ──────────────────────────────────────────────────────────────────────
dispatch "06 associative FunnelHashMap mix64 + honesty KDoc" \
'CONTEXT
Duplicate FunnelHashMap in collections/associative/ uses probeBound-halving geometry + Sha256Pure and claims paper O(log²). Must not poison importers. Prefer collections.FunnelHashMap for β-bucket production.

EVIDENCE
- associative/FunnelHashMap.kt hash64 uses Sha256Pure
- KDoc claims Complexity from paper O(log²)

TASK
1. mix64 instead of SHA-256
2. Honest KDoc: cousin geometry not paper funnel; no O(log²) claim; point to collections.FunnelHashMap + FunnelHashIndex
3. Keep behavior of put/get/remove/resize

BUILD GATE (must pass):
  ./gradlew jvmMainClasses --console=plain
No unit tests. TDD PR Deliver.'

# ── 07 ──────────────────────────────────────────────────────────────────────
dispatch "07 LineCasIndex inverted membership + FunnelHashIndex wire" \
'CONTEXT
Forge residual DRY and RTS need O(1) content-key → locations. LineCasIndex should invert contentCid.hex → locations; optionally accelerate key set with FunnelHashIndex for negative queries (master already-has).

EVIDENCE
- FunnelHashIndex.build is append-only membership
- CasManifest is ordered CID fingerprint only
- LineCas on branch may need create (tasks 01-02)

TASK
1. In cas/LineCas.kt (extend): class LineCasIndex with ingest(text)/ingestSpine, linkMatch(probe, minGrade), documentCount
2. Keys for funnel: contentCid.hex (and optionally linkedKey) via FunnelHashIndex.build for contains-fast path if helpful — do not double-store inconsistently
3. cas must not import forge
4. Series return types where appropriate

BUILD GATE (must pass):
  ./gradlew jvmMainClasses --console=plain
No unit tests. TDD PR Deliver.'

# ── 08 ──────────────────────────────────────────────────────────────────────
dispatch "08 Log-ramp confidence prior for link positives" \
'CONTEXT
User strategy: log-based size ramp confidence before real confirmation of positives. Weak neighbor prefix hits are provisional; full content CID + both neighbors = confirmed. Collections expose ConfidenceRamp; forge consumes for edge paint.

EVIDENCE
- MatchGrade strength: LINKED > PARTIAL_* > CONTENT_ONLY
- 8-bit one-side FP ~1/256; 16-bit both ~1/65536 among content collisions
- rsync weak+strong pattern

TASK
1. In cas/LineCas.kt add sealed or enum LinkConfidence: CANDIDATE (content only), PROVISIONAL (one neighbor), CONFIRMED (both neighbors / LINKED)
2. fun confidenceOf(grade: MatchGrade): LinkConfidence
3. fun rampScore(grade): Double in [0,1] with log-ish spacing (e.g. content 0.12, partial 0.45, linked 1.0) — document as prior not probability proof
4. No forge import

BUILD GATE (must pass):
  ./gradlew jvmMainClasses --console=plain
No unit tests. TDD PR Deliver.'

# ── 09 ──────────────────────────────────────────────────────────────────────
dispatch "09 Relation groups Series API (arbitrary groupings)" \
'CONTEXT
RTS graph needs arbitrary relation kinds over line bodies, not only zipWithNext linear neighbors. Collections/cas owns pure relation Series; forge layouts consume.

EVIDENCE
- Series.zipWithNext: Series.kt
- ForceLayout uses parentNodeIds springs only today

TASK
1. Add cas/LineRelation.kt (or section in LineCas.kt):
   - enum or value RelationKind: NEIGHBOR_PREV, NEIGHBOR_NEXT, SAME_CONTENT, LINKED_CONTEXT, CUSTOM
   - data class LineEdge(fromOrdinal, toOrdinal, kind, confidence prior optional)
   - fun neighborEdges(spine): Series<LineEdge> from stamps
   - fun groupByContent(spine): Series of groups (contentCid → ordinals) as Series/Join — PRELOAD style, avoid List public API where possible
2. Allow custom edges: fun edgesOf(spine, kind, pairs: Series<Join<Int,Int>>)
3. No forge import

BUILD GATE (must pass):
  ./gradlew jvmMainClasses --console=plain
No unit tests. TDD PR Deliver.'

# ── 10 ──────────────────────────────────────────────────────────────────────
dispatch "10 Regional aperture L0-L3 + topk (cas/collections)" \
'CONTEXT
DRY-as-tool = zap → multi-scale topo → regional top-k. L0 short prefix buckets; L3 full CID. Forge camera zoom maps to aperture later; aperture math lives in cas so collections stay DRY single source.

EVIDENCE
- ContentId.hex prefixes
- FunnelHashIndex tiny address membership
- ForgeBlackboardCamera zoomAround exists for presentation only

TASK
1. cas/LineAperture.kt:
   - enum Aperture { L0(prefixHex=2), L1(4), L2(8), L3(64 full hex) } or similar
   - fun bucketKey(cid: ContentId, aperture): String
   - fun regionDensities(spine, aperture): Series of Join<bucketKey, count>
   - fun topK(regions, k): Series of densest buckets
2. Use Series algebra; document RTS mapping: coarser aperture = zoomed out
3. No forge import

BUILD GATE (must pass):
  ./gradlew jvmMainClasses --console=plain
No unit tests. TDD PR Deliver.'

# ── 11 ──────────────────────────────────────────────────────────────────────
dispatch "11 Forge adapter: LineCas spine → CausalGraphNodeIndex" \
'CONTEXT
ForceLayout.kt needs CausalGraphNodeIndex. Moneymaker is forge blackboard. Adapter in forge package maps line spines + edges into graph nodes without forking collections.

EVIDENCE
- forge/blackboard/ForceLayout.kt forceLayout(graph, camera, iterations)
- CausalGraphNodeIndex in borg.trikeshed.graph
- Read CausalGraphNodeIndex definition and required fields (nodeId, parentNodeIds, topoOrdinal)

TASK
1. Create forge/blackboard/LineCasGraph.kt (package borg.trikeshed.forge.blackboard)
2. fun lineSpineToCausalIndex(spine, edges): CausalGraphNodeIndex
   - nodeId stable from linkedKey or contentCid+ordinal
   - parent edges from NEIGHBOR_PREV / high-confidence links
3. Import cas LineCas types; do not duplicate stamp algebra in forge
4. If LineCas missing, create minimal spine+edge stubs in cas first then adapter

BUILD GATE (must pass):
  ./gradlew jvmMainClasses --console=plain
No unit tests. TDD PR Deliver.'

# ── 12 ──────────────────────────────────────────────────────────────────────
dispatch "12 ForceLayout edge weight by link confidence" \
'CONTEXT
ForceLayout uses uniform kSpring. Confirmed links should pull harder than content-only boilerplate. Forge moneymaker visual: structure emerges where confidence is high.

EVIDENCE
- ForceLayout.kt kSpring = 0.1 constant on all parent edges
- LinkConfidence / MatchGrade from cas (task 08)

TASK
1. Extend forceLayout or add overload forceLayoutWeighted(..., edgeWeight: (parentId, childId) -> Double)
2. Default weights: CONFIRMED=1.0, PROVISIONAL=0.45, CANDIDATE=0.12 (match rampScore)
3. Keep existing forceLayout signature working (default equal weights)
4. Still frame camera from layout bbox/COM

BUILD GATE (must pass):
  ./gradlew jvmMainClasses --console=plain
No unit tests. TDD PR Deliver.'

# ── 13 ──────────────────────────────────────────────────────────────────────
dispatch "13 Forge RTS: aperture follows camera zoom → regional topk" \
'CONTEXT
ForgeBlackboardCamera zoom is the RTS scroll. Map zoom bands to LineAperture L0-L3 and compute topK regions for HUD/gallery. Collections own aperture; forge owns camera mapping.

EVIDENCE
- ForgeBlackboardCamera zoom clamps min/max; zoomAround
- LineAperture from task 10

TASK
1. forge/blackboard/LineCasRtsView.kt:
   - fun apertureForZoom(zoom: Double, minZoom, maxZoom): Aperture (log or linear bands)
   - fun regionalTopK(spine, camera, k): Series of top regions at that aperture
2. Pure functions; no browser/JS required
3. Document band thresholds in KDoc

BUILD GATE (must pass):
  ./gradlew jvmMainClasses --console=plain
No unit tests. TDD PR Deliver.'

# ── 14 ──────────────────────────────────────────────────────────────────────
dispatch "14 Forge gallery/printer: topk + confidence legend" \
'CONTEXT
Gallery and ForgeGalleryPrinter are sellable surfaces. Surface regional top-k and confidence legend from RTS view so the graph is not only internal algebra.

EVIDENCE
- forge/gallery/ForgeGalleryPrinter.kt prints camera zoom etc.
- GalleryRenderer.js cards

TASK
1. Extend ForgeGalleryPrinter (or commonMain gallery text projector) to accept optional LineCas RTS snapshot: topK buckets, counts, aperture name
2. Print confidence legend CANDIDATE/PROVISIONAL/CONFIRMED
3. Keep existing printer paths working when snapshot null
4. Prefer commonMain if printer is multiplatform; else jvmMain only without breaking compile

BUILD GATE (must pass):
  ./gradlew jvmMainClasses --console=plain
No unit tests. TDD PR Deliver.'

# ── 15 ──────────────────────────────────────────────────────────────────────
dispatch "15 End-to-end forge seed: text bodies → framed blackboard" \
'CONTEXT
Close the loop: arbitrary text bodies → LineCas spines → relations → CausalGraphNodeIndex → forceLayout → camera. Single entry for forge blackboard seed. Collections remain pure stock called from forge.

EVIDENCE
- ForceLayout + Camera + Interaction already in forge/blackboard
- Tasks 01-14 supply pieces; compose only what exists, create missing minimal cas types if branch lacks them

TASK
1. forge/blackboard/LineBodyBlackboard.kt:
   - fun seedFromTexts(texts: Series<String> or List for boundary, camera: ForgeBlackboardCamera): Pair<ForgeBlackboardCamera, CausalGraphNodeIndex> 
   - steps: spine each text, union edges (neighbor + same-content at PROVISIONAL+ only if needed), to graph, forceLayout, return
2. Use FunnelHashIndex for cross-text content DRY (drop pure duplicate CONTENT_ONLY edges if both sides same doc boilerplate — document policy)
3. No patch-merge logic. No Hermes.
4. Keep function total and pure enough for JVM printer/tests later

BUILD GATE (must pass):
  ./gradlew jvmMainClasses --console=plain
No unit tests. TDD PR Deliver.'

echo "=== ALL 15 DISPATCH ATTEMPTS FINISHED ==="
