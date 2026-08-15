# Concurrent Line-Merge Engine — TypeScript Contract

Self-contained: implement against this document alone. The contract in
Part 2 is TypeScript that already typechecks under `tsgo` --noEmit
(TypeScript 7.0 native, strict). Your implementation must satisfy it
exactly — the `Expect` assertions in laws.ts are machine-checked laws,
not prose. Specification is types-only: value classes are branded
primitives, every structure is immutable (`readonly`), and every
operation is a pure function.

## 0. The problem, in plain terms

You maintain a **master** text (a file, a document — an ordered list of text
lines). N writers each hand you a **patch text**: the document as they believe
it should be. Writers worked concurrently — nobody sequenced them. Your job is
to settle all N patches against the master in one pass and say exactly what
happened, line by line.

The engine never diffs whole files. Every line is content-addressed:

- `lineId(line) = sha256(trim(line))` — pure content identity. Whitespace
  churn is invisible; the same text is the same line, always.
- Each line also carries a **neighbor stamp**: short hash prefixes of the
  previous and next lines. A line's identity-in-context is
  `stamp + ':' + contentHash` — the same sentence between different
  neighbors is a *different* structural line.
- The **funnel** is a frozen membership index over the master's line hashes.
  `contains(x) === false` PROVES the line is not in master — no further
  ancestry work needed. (`true` is probabilistic; the stamp ladder settles it.)

Given master + N patches, the merge is six pure stages:

1. **spine** each text — trim, drop empties, hash, stamp every line.
2. **build the master funnel** — freeze master's line hashes.
3. **residuals** — per patch, keep only lines the master funnel MISSES.
   Everything else is a funnel hit (master already has it): dropped cheaply —
   never clustered, never graded.
4. **topology** — group the residual atoms by a 64-bit fold of their hash.
   Lines several writers added collapse into one **cluster** holding every
   copy's address (which patch, which position, which neighbor prefix).
5. **grade** each cluster:
   - `NOVEL` — one copy, master lacks it → keep, fast-apply.
   - `INHERITED_CROSS` — several copies, all neighbor prefixes EQUAL → drop.
     Every writer added the same boilerplate in the same spot; keep one is a
     no-op because content addressing already dedups.
   - `RELOCATED` — several copies, prefixes DIFFER → keep and surface. The
     same new line landed in different contexts: the writers moved/placed it
     differently, and a human-grade decision (or 3-way) must pick the home.
6. **settle** — emit a `MergeReceipt`: kept clusters, dropped clusters, exact
   counts. Dropping is final; re-running cannot resurrect a dropped cluster.

Two invariants anchor the design:

- **Context is not novelty.** A line's identity-in-context is its neighbor
  stamp, not its hash alone: a re-placed line is not brand new, and identical
  additions by several writers are not independent. The neighbor-stamp
  lattice (LINKED > PARTIAL > CONTENT_ONLY) is what separates these cases
  (law M4).
- **Settlement converges.** Applying `NOVEL` survivors into master makes
  them funnel hits on the next merge; settlement is idempotent (law M3).

### A 60-second example

Master: `["alpha", "beta"]`.

- Writer X inserts `"gamma"` after `"beta"`.
- Writer Y inserts `"gamma"` after `"alpha"`, and appends `"delta"` at the end.
- Writer Z also inserts `"import os"` at the top — and so does X, identically.

Merge outcome: `"alpha"`/`"beta"` are funnel hits — never residuals.
`"gamma"` misses twice with DIFFERENT neighbor prefixes → `RELOCATED`
(kept, surfaced). `"delta"` misses once → `NOVEL` (kept, fast-apply).
`"import os"` misses twice with EQUAL prefixes → `INHERITED_CROSS` (dropped).

Receipt: `novelCount=1, relocatedCount=1, inheritedCount=0` (funnel hits are
dropped before clustering), kept = the gamma and delta clusters, dropped =
the import-os cluster.

`inheritedCount` is 0 by construction through this pipeline — and provably
so: `contains` is pure over a frozen index, so content that answered false
at stage 3 cannot answer true at grading. The `INHERITED` grade exists for
`gradeClusters` driven directly against a different funnel (re-grading a
persisted topology after master advanced); it is unreachable via `merge()`.

## 1. Glossary — every coined term, defined once

| term | meaning |
|---|---|
| spine | one text's ordered list of stamped lines |
| atom | one residual line, carrying its identity and copy address |
| lineId / contentCid | sha256 over the trimmed line — pure content identity |
| mini64 | 64-bit fold of the hash — cheap group-by identity, not crypto |
| funnel | frozen membership index over master's line hashes; `contains===false` proves absence |
| residual | an atom whose hash MISSES the master funnel — the merge's raw material |
| neighbor stamp | 4 hex chars: 2-char hash prefix of the previous line ‖ 2 of the next |
| linkedKey | `stamp ':' contentHash` — identity-in-context; the spineCid unit |
| spineCid | sha256 over the ordered linkedKeys — document fingerprint |
| cluster | all residual atoms sharing one mini64, across all patches |
| neighbor prefix (packed) | prev and next 2-char prefixes packed into one byte |
| LINKED / PARTIAL_* / CONTENT_ONLY | match grades: both / one / neither neighbor matches |

## Part 2 — The contract (gated; reproduce verbatim)

### 2a. preload.ts — kernel algebra

```ts
/**
 * PRELOAD — kernel algebra, abstractly adapted to TypeScript.
 * Specification only: no runtime code exists in this file.
 * Gate: TypeScript 7.0 native (tsgo) `--noEmit`.
 *
 * Shapes (adapted, not ported):
 *   Join<A,B>        base binary composition
 *   Twin<T>          same-typed Join
 *   MetaSeries<I,T>  domain paired with an index oracle
 *   Series<T>        integer-indexed MetaSeries — the default abstraction
 *   Cursor           columnar specialization over RowVec
 *
 * Immutable by default: every field readonly, every declaration pure.
 * Any implementation's mutable phases are construction-time folds kept
 * behind these signatures. `view` is the SINGLE bridge to stdlib iterables;
 * materializing beyond that door is category demotion.
 */

// ── value-class brand ──────────────────────────────────────────

declare const RecordMetaBrand: unique symbol;

/** Opaque row-metadata token. */
export type RecordMeta = { readonly [RecordMetaBrand]: 'RecordMeta' };

// ── Join / Twin ────────────────────────────────────────────────

/** Base binary composition — an immutable product of exactly two. */
export type Join<A, B> = readonly [A, B];

/** Same-typed Join. */
export type Twin<T> = Join<T, T>;

/** Infix constructor grammar: j(a)(b) ≔ the Join of a and b. */
export declare function j<A>(x: A): <B>(y: B) => Join<A, B>;

/** Construct a Twin — routes to the densest representation available. */
export declare function twin<T>(x: T, y: T): Twin<T>;

/** Left projection. */
export declare const a: <A, B>(p: Join<A, B>) => A;

/** Right projection. */
export declare const b: <A, B>(p: Join<A, B>) => B;

// ── MetaSeries / Series ────────────────────────────────────────

/** A bound domain paired with an index oracle: I paired with (I → T). */
export type MetaSeries<I, T> = Join<I, (i: I) => T>;

/** The domain half of any MetaSeries. */
export type Domain<S> = S extends MetaSeries<infer I, unknown> ? I : never;

/** Integer-indexed MetaSeries — the default indexed abstraction. */
export type Series<T> = MetaSeries<number, T>;

/** Series of Joins — the split-storage specialization. */
export type Series2<A, B> = Series<Join<A, B>>;

/** Size — the domain half of a Series. */
export declare const size: <T>(xs: Series<T>) => number;

/** Detached index oracle: at(xs)(i) ≔ xs at i. */
export declare const at: <T>(xs: Series<T>) => (i: number) => T;

/** Series literal: s_(x, y, z). */
export declare const s_: <T>(...xs: readonly T[]) => Series<T>;

/** Empty Series — zero elements; the oracle is never invoked. */
export declare function emptySeriesOf<T>(): Series<T>;

/** Zip two same-sized Series into a Series2. */
export declare function joins<A, B>(xs: Series<A>): (ys: Series<B>) => Series2<A, B>;

/** Left projection of a Series2. */
export declare const left: <A, B>(xs: Series2<A, B>) => Series<A>;

/** Right projection of a Series2. */
export declare const right: <A, B>(xs: Series2<A, B>) => Series<B>;

// ── projection α / range / view / left identity ────────────────

/** Lazy projection: α(xs)(f). Size preserved, oracle composed — never eager. */
export declare function α<X>(xs: Series<X>): <C>(f: (x: X) => C) => Series<C>;

/** Range selection as composition, not control flow. */
export declare function range<T>(xs: Series<T>): (from: number, until: number) => Series<T>;

/** The ONLY bridge to stdlib iteration. Beyond this door is demotion. */
export declare function view<T>(xs: Series<T>): Iterable<T>;

/** Left identity / constant anchor (↺): the supplier that always returns x. */
export declare const leftIdentity: <T>(x: T) => () => T;

// ── Cursor ─────────────────────────────────────────────────────

/** Row-shaped value view plus metadata supplier. */
export type RowVec = Series2<unknown, () => RecordMeta>;

/** The columnar specialization: an indexed composition of RowVec. */
export type Cursor = Series<RowVec>;

// ── categorical idempotency (binding law) ──────────────────────
//
// A Series that is not mutated stays a Series. Converting to Array only to
// read it back is demotion; the resting type is Series. The round-trip
// toSeries → toList → toSeries must be a no-op when nothing mutated.

// ── userspace boundary: AsyncSeries + CCEK lifecycle ───────────

/** The only async door: streaming IO boundary. null = exhausted. */
export interface AsyncSeries<T> {
  next(): Promise<T | null>;
}

/** CCEK element lifecycle — five states; an implementation with fewer is incomplete. */
export type Lifecycle = 'CREATED' | 'OPEN' | 'ACTIVE' | 'DRAINING' | 'CLOSED';

/** Legal forward transitions. DRAINING is graceful drain, not hard cancel. */
export type LegalTransition =
  | readonly ['CREATED', 'OPEN']
  | readonly ['OPEN', 'ACTIVE']
  | readonly ['ACTIVE', 'DRAINING']
  | readonly ['DRAINING', 'CLOSED'];

/** Unique successor of a state; CLOSED is terminal. */
export type Next<L extends Lifecycle> = {
  readonly CREATED: 'OPEN';
  readonly OPEN: 'ACTIVE';
  readonly ACTIVE: 'DRAINING';
  readonly DRAINING: 'CLOSED';
  readonly CLOSED: never;
}[L];
```

### 2b. linecas.ts — Line CAS merge strategy

```ts
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
```

### 2c. laws.ts — machine-checked laws + runtime obligations

```ts
/**
 * Laws — machine-checked by tsgo where decidable at the type level,
 * declared as runtime test obligations where semantic. Specification only.
 *
 * NOTE on Hex64: the full nested template union (16^8 at each Hex8) exceeds
 * the compiler's union-complexity limit when instantiated in an `extends`
 * pattern. Literal-membership checks therefore use the LINEAR predicate
 * (length + charset), never the Hex64 union itself. A branded ContentId has
 * no literal inhabitants BY DESIGN — nominal typing is the guarantee; these
 * laws check the representational shape beneath the brand.
 */
import type {
  ContentId,
  Hex2,
  Meets,
  NeighborStamp,
} from './linecas.ts';
import type { FunnelHashIndex, LineSpine } from './linecas.ts';
import type { Series } from './preload.ts';

type Expect<T extends true> = T;
type Eq<A, B> = (<T>() => T extends A ? 1 : 2) extends (<T>() => T extends B ? 1 : 2) ? true : false;

// ── L1 — trim is idempotent (type-level, on literals) ──────────

type Strip<S extends string> =
  S extends ` ${infer R}` ? Strip<R> : S extends `${infer R} ` ? Strip<R> : S;

type L1 = Expect<Eq<Strip<Strip<'  alpha '>>, Strip<'  alpha '>>>;

// ── L2 — the edge sentinel is a chosen constant, never a hash of empty ──

type L2a = Expect<Eq<NeighborStamp, `${Hex2}${Hex2}`>>;
type L2b = Expect<Eq<'0000' extends NeighborStamp ? true : false, true>>;

// ── L3 — the grade lattice is total and ordered ────────────────

type L3a = Expect<Eq<Meets<'LINKED', 'CONTENT_ONLY'>, true>>;
type L3b = Expect<Eq<Meets<'LINKED', 'PARTIAL_NEXT'>, true>>;
type L3c = Expect<Eq<Meets<'PARTIAL_PREV', 'PARTIAL_NEXT'>, true>>;
type L3d = Expect<Eq<Meets<'PARTIAL_NEXT', 'LINKED'>, false>>;
type L3e = Expect<Eq<Meets<'CONTENT_ONLY', 'CONTENT_ONLY'>, true>>;
type L3f = Expect<Eq<Meets<'CONTENT_ONLY', 'LINKED'>, false>>;

// ── L4 — a linkedKey decomposes back into (stamp, content hex) ──
// Linear pattern: `${string}` tail, never the Hex64 union.

type StampOf<K extends string> = K extends `${infer S}:${string}` ? S : never;

type L4 = Expect<
  Eq<
    StampOf<'a1b2:688787d8ff144c502c7f5cffaafe2cc588d86079f9de88304c26b0cb99ce91c6'>,
    'a1b2'
  >
>;

// ── L5 — the representation beneath the brand is exactly sha256:<64 hex> ──

type Chars<S extends string, A extends readonly string[] = []> =
  S extends `${infer C}${infer R}` ? Chars<R, readonly [...A, C]> : A;
type AllHex<T extends readonly string[]> =
  T extends readonly [infer F, ...infer R extends readonly string[]]
    ? F extends '0'|'1'|'2'|'3'|'4'|'5'|'6'|'7'|'8'|'9'|'a'|'b'|'c'|'d'|'e'|'f'
      ? AllHex<R> : false
    : true;
type IsContentHex<S extends string> =
  Chars<S>['length'] extends 64 ? (AllHex<Chars<S>> extends true ? true : false) : false;
type IsContentId<S extends string> =
  S extends `sha256:${infer H}` ? IsContentHex<H> : false;

/** canonical sha256('') — a real constant, not invented. */
type EmptySha = 'sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855';
type L5a = Expect<Eq<IsContentId<EmptySha>, true>>;
type L5b = Expect<Eq<IsContentId<'sha256:zz88787d8ff144c502c7f5cffaafe2cc588d86079f9de88304c26b0cb99ce91c6'>, false>>;
type L5c = Expect<Eq<IsContentId<'688787d8ff144c502c7f5cffaafe2cc588d86079f9de88304c26b0cb99ce91c6'>, false>>;
type L5d = Expect<Eq<IsContentId<'sha256:688787d8ff144c502c7f5cffaafe2cc588d86079f9de88304c26b0cb99ce91c'>, false>>;

// The brand itself: no string literal is a ContentId. This is nominal
// typing working as intended, not a gap.
type L5e = Expect<Eq<EmptySha extends ContentId ? true : false, false>>;

// ── M-laws — runtime obligations any implementation must discharge ──

export interface MergeLaws {
  /** M1: spineCid(spine(t)) === spineCid(spine(textOf(spine(t)))) — content addressing absorbs trim. */
  readonly spineIdempotent: (text: string) => boolean;
  /** M2: buildFunnel(keys, seed) twice ⇒ contains agrees on every probe — build is pure. */
  readonly funnelDeterministic: (keys: Series<string>, seed: bigint) => boolean;
  /** M3: merge(keptSources(receipt), sameFunnel) regrades nothing — dropped clusters never resurrect. */
  readonly settlementIdempotent: (sources: Series<LineSpine>, funnel: FunnelHashIndex<string>) => boolean;
  /** M4: applying NOVEL survivors into master turns them into funnel hits on the next wave. */
  readonly convergence: (sources: Series<LineSpine>, funnel: FunnelHashIndex<string>) => boolean;
}
```

## Part 3 — Acceptance

- [ ] `tsgo --noEmit` passes on your sources compiled against Part 2.
- [ ] Type laws L1–L5 hold as written (they are already in laws.ts;
      do not weaken them).
- [ ] Runtime laws M1–M4 (the `MergeLaws` obligations) are discharged
      by your implementation: spine idempotency, funnel determinism,
      settlement idempotency, convergence.
- [ ] RELOCATED fires when copies of one cluster carry different
      neighbor prefixes; INHERITED_CROSS collapses identical-context
      duplicates; funnel hits never become residuals.
- [ ] No mutable state escapes a stage boundary; `ingest` returns a new
      index (the old one remains valid).
