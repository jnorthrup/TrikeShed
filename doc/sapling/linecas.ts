/**
 * Line CAS merge strategy — the external interface, specified entirely as
 * typealiases and value-class brands. Specification only; no runtime code.
 * Gate: tsgo --noEmit (TS 7.0).
 *
 * Pipeline — the N-way residual merge; every stage a pure function:
 *
 *   sources ─spine→ LineSpines ─residualsOf→ atoms (master-funnel misses)
 *          ─topologyOf→ clusters by mini64 ─gradeClusters→ graded topology
 *          ─mergeResiduals→ MergeReceipt { kept: NOVEL|RELOCATED,
 *                                          dropped: INHERITED* }
 *
 * Mostly idempotent semantics (the contract):
 *   - spine: content-addressed — same text ⇒ same spineCid, always.
 *   - trim: idempotent at line and spine level (law L1).
 *   - funnel: frozen after build; contains is a pure query; rebuild from
 *     the same (keys, seed) is extensionally equal (law M2).
 *   - settlement: re-merging the kept set against the same frozen funnel
 *     regrades nothing — dropped clusters never resurrect (law M3).
 *   - convergence: applying NOVEL survivors into master makes them funnel
 *     hits (INHERITED) on the next wave — waves converge (law M4).
 */

import type { Series } from './preload.ts';

// ── hex ground ─────────────────────────────────────────────────

export type HexChar = '0'|'1'|'2'|'3'|'4'|'5'|'6'|'7'|'8'|'9'|'a'|'b'|'c'|'d'|'e'|'f';
export type Hex2 = `${HexChar}${HexChar}`;

// Hex shapes longer than Hex2 are validated by PREDICATE, never by template
// unions: Hex8 alone would instantiate 2^32 members and trips TS2590
// ("union type too complex"). The union-complexity ceiling is physical; the
// predicate is the contract.
type Chars<S extends string, A extends readonly string[] = []> =
  S extends `${infer C}${infer R}` ? Chars<R, readonly [...A, C]> : A;
type AllHex<T extends readonly string[]> =
  T extends readonly [infer F, ...infer R extends readonly string[]]
    ? F extends HexChar ? AllHex<R> : false
    : true;
/** Linear membership check: exactly 64 lowercase hex chars. */
export type IsHex64<S extends string> =
  Chars<S>['length'] extends 64 ? (AllHex<Chars<S>> extends true ? true : false) : false;

// ── value classes (branded primitives; plain strings live only at boundaries) ──

declare const ContentIdBrand: unique symbol;
declare const Mini64Brand: unique symbol;
declare const NeighborPrefixBrand: unique symbol;
declare const SourceIdxBrand: unique symbol;
declare const OrdinalBrand: unique symbol;

/** sha256 over trimmed UTF-8 line bytes — pure content identity. 64-hex payload (see [IsHex64]). */
export type ContentId = `sha256:${string}` & { readonly [ContentIdBrand]: 'ContentId' };

/** Document-level fingerprint — same substance as ContentId, compressed semantics. */
export type SpineCid = ContentId;

/** 64-bit fold: h₀ = 0; h ← 31·h + charCode(hex[8k]), k = 0..7. Group-by identity, not crypto. */
export type Mini64 = bigint & { readonly [Mini64Brand]: 'Mini64' };

/** Packed byte: (nibble(prevHex) << 4) | nibble(nextHex). */
export type NeighborPrefix = number & { readonly [NeighborPrefixBrand]: 'NeighborPrefix' };

/** Which source (patch) of the N-way merge an atom came from. */
export type SourceIdx = number & { readonly [SourceIdxBrand]: 'SourceIdx' };

/** Line position within its source spine. */
export type Ordinal = number & { readonly [OrdinalBrand]: 'Ordinal' };

// ── Line CAS taxonomy ──────────────────────────────────────────

/** Hex chars taken from each neighbor's CID: 2 per side (tunable — only if all invariants move coherently). */
export type NeighborHexLen = 2;

/** Edge sentinel — a chosen constant, deliberately NOT a hash of empty. */
export type EDGE = '00';

/** prev hex prefix ‖ next hex prefix — 4 hex chars; the line's context fingerprint. */
export type NeighborStamp = `${Hex2}${Hex2}`;

/** Context-bound identity: stamp ‖ ':' ‖ content hex — the spineCid unit. */
export type LinkedKey = `${NeighborStamp}:${string}`;

/** One line: content identity + neighbor context + position. */
export type LineNode = {
  readonly contentCid: ContentId;
  readonly stamp: NeighborStamp;
  readonly ordinal: Ordinal;
};

/** A document under the Line CAS taxonomy. */
export type LineSpine = Series<LineNode>;

/** trim each line, drop empty-after-trim, stamp with neighbor CID prefixes. */
export declare function spine(text: string): LineSpine;

/** sha256 over '\n'-joined linkedKeys. Equal trimmed line sequences share a spineCid. */
export declare function spineCid(spine: LineSpine): SpineCid;

// ── match grading ──────────────────────────────────────────────

export type MatchGrade = 'LINKED' | 'PARTIAL_PREV' | 'PARTIAL_NEXT' | 'CONTENT_ONLY';

export type Strength = {
  readonly LINKED: 3;
  readonly PARTIAL_PREV: 2;
  readonly PARTIAL_NEXT: 2;
  readonly CONTENT_ONLY: 1;
};

/** meets(a, min) ⟺ strength(a) ≥ strength(min) — the total order for thresholds. */
export type Meets<A extends MatchGrade, B extends MatchGrade> =
  [Strength[A], Strength[B]] extends
    [1, 1] | [2, 1] | [2, 2] | [3, 1] | [3, 2] | [3, 3] ? true : false;

/** null ⟺ content differs. LINKED = same text AND same neighborhood — structural reuse. */
export declare function matchGrade(x: LineNode, y: LineNode): MatchGrade | null;

export type OverlapCounts = {
  readonly linked: number;
  readonly partial: number;
  readonly contentOnly: number;
};

/** Blend ∈ [0,1]: linked·1.0 + partial·0.45 + contentOnly·0.12 over max(|x|,|y|). */
export declare function proximity(x: LineSpine, y: LineSpine): number;

// ── inverted index (persistent — ingest returns a fresh index, never mutates) ──

export type LinkHit = {
  readonly grade: MatchGrade;
  readonly docCid: SpineCid;
  readonly node: LineNode;
};

export type LineCasIndex = {
  readonly documentCount: number;
  readonly contentKeyCount: number;
};

export declare function emptyIndex(): LineCasIndex;
export declare function ingest(index: LineCasIndex, text: string): readonly [LineCasIndex, SpineCid];
export declare function linkMatch(index: LineCasIndex, probe: LineNode, minGrade?: MatchGrade): Series<LinkHit>;
export declare function linkDensity(index: LineCasIndex, probe: LineSpine): Series<readonly [SpineCid, OverlapCounts]>;
export declare function missesOf(index: LineCasIndex, probe: LineSpine): Series<LineNode>;

// ── funnel — the INHERITED oracle ──────────────────────────────

/**
 * Frozen membership index over content keys.
 * contains(key) === false is AUTHORITATIVE absence — skip all ancestry work.
 * true defers to the graded ladder:
 *   rung 1 — 8 stamp bits ≈ 1/256 FP among content collisions;
 *   rung 2 — full 16-bit stamp ≈ 1/65536 (verdicts require rung 2).
 */
export type FunnelHashIndex<K extends string> = {
  readonly slack: number;                 // FP budget, default 0.20 (tunable)
  readonly beta: number;                  // bucket width, ≥ 8
  readonly contains: (key: K) => boolean; // pure query; index frozen after build
};

export declare function buildFunnel<K extends string>(
  keys: Series<K>,
  seed: bigint,
  slack?: number,
): FunnelHashIndex<K>;

// ── residual merge (N-way) ─────────────────────────────────────

export type LineAtom = {
  readonly mini64: Mini64;
  readonly neighborPrefix: NeighborPrefix;
  readonly sourceIdx: SourceIdx;
  readonly ordinal: Ordinal;
  readonly contentCid: ContentId;
};

export type ResidualSpine = Series<LineAtom>;

export type CopyAddress = {
  readonly sourceIdx: SourceIdx;
  readonly ordinal: Ordinal;
  readonly neighborPrefix: NeighborPrefix;
};

export type Cluster = {
  readonly mini64: Mini64;
  readonly contentCid: ContentId;
  readonly copies: Series<CopyAddress>;
};

export type Topology = Series<Cluster>;

/**
 * Reachability of INHERITED: through merge() it never fires. residualsOf
 * already excluded every funnel hit, and contains is pure over a frozen
 * index, so re-probing the same contentCid against the same funnel cannot
 * answer true — merge().inheritedCount === 0 is a provable invariant, the
 * machine-checkable statement that the stage-3 early drop lost nothing.
 * The arm exists because gradeClusters is a standalone, total component:
 * its funnel is a parameter, not a closure over this pipeline. Grading a
 * topology against a funnel that did NOT produce it — re-grading a
 * persisted topology after master advanced — must still absorb content the
 * funnel now contains, or stale clusters would re-apply as NOVEL and break
 * convergence at the component boundary.
 */
export type ClusterGrade =
  | 'INHERITED'        // funnel contains the content — drop, regardless of copies
  | 'NOVEL'            // single copy, not in master — keep, fast-apply
  | 'INHERITED_CROSS'  // multiple copies, all neighbor prefixes equal — drop (shared boilerplate)
  | 'RELOCATED';       // prefixes differ across copies — surface (context moved)

export type GradedCluster = readonly [Cluster, ClusterGrade];

export type GradedTopology = Series<GradedCluster>;

export type MergeReceipt = {
  readonly kept: GradedTopology;       // NOVEL + RELOCATED survivors
  readonly dropped: GradedTopology;    // INHERITED + INHERITED_CROSS
  readonly novelCount: number;
  readonly relocatedCount: number;
  readonly inheritedCount: number;       // via merge(): provably 0 — see ClusterGrade
};

/** Per-source probe against the frozen master funnel: misses become atoms. */
export declare function residualsOf(
  source: LineSpine,
  masterFunnel: FunnelHashIndex<string>,
  sourceIdx: SourceIdx,
): ResidualSpine;

/** Group N residual spines by mini64 — the cross-source topology. O(Σ|residual|). */
export declare function topologyOf(residuals: Series<ResidualSpine>): Topology;

/**
 * Grade every cluster: funnel is the INHERITED oracle; prefix diff is the
 * RELOCATION signal. Total over ANY topology: when the topology came from
 * residualsOf against this same funnel, INHERITED is unreachable (contains
 * is pure; its false was authoritative) — see ClusterGrade.
 */
export declare function gradeClusters(
  topology: Topology,
  masterFunnel: FunnelHashIndex<string>,
): GradedTopology;

/** Settlement: keep NOVEL + RELOCATED, drop INHERITED and INHERITED_CROSS. O(|residual|). */
export declare function mergeResiduals(graded: GradedTopology): MergeReceipt;

/** Compose all stages. Cost O(|union residual|), not Σ|patch|. */
export declare function merge(
  sources: Series<LineSpine>,
  masterFunnel: FunnelHashIndex<string>,
): MergeReceipt;

/** The frozen baseline: funnel over master's content keys. */
export declare function buildMasterFunnel(
  masterSpine: LineSpine,
  seed?: bigint,
): FunnelHashIndex<string>;
