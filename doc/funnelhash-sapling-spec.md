# Concurrent Tree-Merge Engine — TypeScript Implementation Spec

Spec v2, self-contained. Nothing outside this document is needed to implement.
Deliverable: a TypeScript library per §8. Gates: `tsc --noEmit` and
`vitest run` both green. Parameters marked *tunable* may change only if every
invariant and type-level test is updated coherently in the same change.

---

## 0. The problem, in plain terms

Many writers edit the same file tree at the same time — like git, except the
writers are concurrent agents rather than sequential committers:

- The workspace is a tree of files ("tree of trees": directories are
  sub-trees; every file is an ordered list of text lines).
- Each writer emits a **delta**: a small set of line-level operations
  (insert / delete / move / edit), each recorded against the file path and
  line position it was authored at, plus the fingerprint of the file version
  it was written against (so stale authoring is detectable).
- Deltas arrive asynchronously, in arbitrary order, up to **1000 per merge
  wave**, with long authorship chains between them (writer B based their work
  on writer A's earlier delta).

Given one wave of deltas and the current tree, the engine must:

1. **Classify** every incoming line:
   - `INHERITED` — already in the tree; drop it.
   - `DUPLICATE` — another in-wave delta already contributed it; drop it.
   - `RELOCATED` — the content is new *at this position*, but the same
     content with matching neighboring lines exists elsewhere: it moved.
     Adopt it at the new position.
   - `NOVEL` — genuinely new content; adopt it.
   - `CONFLICT` — two concurrent deltas changed the same position with
     different content. Surface BOTH; never auto-pick one.
2. **Apply** the surviving operations to the tree.
3. **Gate**: run typecheck + tests against the applied result (§6).
4. **Emit a MergeReceipt**: exact per-verdict counts, the applied tree's
   content hash, and the gate results.

Two requirements that a predecessor design failed in production; they are now
hard contract:

- **F1 — moves are not novelty.** Hashing a line and asking "is this in the
  tree?" misreads a moved line as brand new. Here a line's identity includes
  its neighboring lines (`NeighborStamp`, §3), and the `RELOCATED` verdict
  must actually fire on constructed move cases (§9).
- **F2 — no verdict without execution.** Classification alone never closes a
  merge. A receipt is final only after the applicator applied the survivor
  set AND the gate ran (§6, §9).

### A 60-second example

File `a.txt` in the current tree: `["alpha", "beta"]`.
Line identity: `lineId(x) = sha256(trim(x))` — so `lineId("beta")` is the
sha256 hex of the string `beta`.

- Delta 1 (writer X): insert `"gamma"` after `"beta"`.
- Delta 2 (writer Y, concurrent — authored without knowledge of Delta 1):
  move line `"alpha"` from `a.txt` into a new file `b.txt`, and also insert
  `"gamma"` after `"beta"`.

Merge outcome: `"gamma"` arrives from both writers → one `NOVEL`, one
`DUPLICATE`. `"alpha"` disappears from `a.txt` and appears in `b.txt` wrapped
in `b.txt`'s (different) neighboring lines → `RELOCATED`, not `NOVEL`. No two
concurrent deltas disagree destructively about one position → no `CONFLICT`.

Receipt: `novel=1, duplicate=1, relocated=1, conflict=0, applied=true,
gate={typechecked:true, tested:true}`.

## 1. Glossary — every coined term, defined once

| term | meaning |
|---|---|
| spine | one file's ordered list of lines |
| atom | one incoming line, carried with its identity and neighbors |
| lineId | sha256 hex of the line's trimmed text — pure content identity |
| funnel | compact approximate-membership structure over the tree's lineIds: `contains(x) === false` PROVES absence; `true` means "possibly present" (§3) |
| residual | the atoms of a delta whose lineIds miss the funnel — the merge's raw material |
| NeighborStamp | 16 hex bits derived from the previous line's id ‖ 16 from the next — the line's context fingerprint; `"0000"` at file head/tail |
| linkedKey | `NeighborStamp(prev) + NeighborStamp(next) + mini64(lineId)` — a line's identity *in context* |
| mini64 | an 8-char fold of the 64-char sha256 hex (every 8th character) — cheap short id (§2) |
| wave | one merge batch of ≤ 1000 deltas |
| vector clock | per-writer counter map; establishes causal order (§4) |
| happens-before | clock a ≤ clock b componentwise AND a ≠ b |
| concurrent | neither happens-before the other — both may be applied |
| survivor | an atom adopted into the tree (NOVEL or RELOCATED) |

## 2. Identities and the compile-time layer

All ids are branded nominal types — plain strings at runtime, distinct to the
type checker. The only constructors are async (WebCrypto
`crypto.subtle.digest('SHA-256', …)`), keeping the engine browser/node clean.

```ts
declare const ContentIdBrand: unique symbol;
declare const LineIdBrand:    unique symbol;
declare const DeltaIdBrand:   unique symbol;

export type ContentId = string & { readonly [ContentIdBrand]: 'sha256' };
export type LineId    = string & { readonly [LineIdBrand]: 'trimmed-sha256' };
export type DeltaId   = string & { readonly [DeltaIdBrand]: 'ulid-like' };
```

Why brands: a `LineId` must never be passed where a `ContentId` is expected —
the two hashes mean different things and confusing them is a real merge bug
class. The compiler enforces it at zero runtime cost.

**Mini64** — the every-8th-char fold — must exist twice, and the two must
agree: a type-level fold (positions 7,15,…,63) proven total by `tsc` on every
literal it sees, and a runtime fold. A differential test proves
type-level ≡ runtime on ≥ 10⁴ random 64-hex inputs.

```ts
type HexChar = '0'|'1'|'2'|'3'|'4'|'5'|'6'|'7'|'8'|'9'|'a'|'b'|'c'|'d'|'e'|'f';
type Chars<S extends string, A extends readonly string[] = []> =
  S extends `${infer C}${infer R}` ? Chars<R, readonly [...A, C]> : A;
type AllHex<T extends readonly string[]> =
  T extends readonly [infer F, ...infer R extends readonly string[]]
    ? F extends HexChar ? AllHex<R> : false : true;
export type IsHex64<S extends string> =
  Chars<S>['length'] extends 64 ? (AllHex<Chars<S>> extends true ? true : false) : false;

type JoinStr<T extends readonly string[]> =
  T extends readonly [infer F extends string, ...infer R extends readonly string[]]
    ? `${F}${JoinStr<R>}` : '';
type Every8th<T extends readonly string[], I extends readonly 0[] = [],
              Out extends readonly string[] = []> =
  I['length'] extends 64 ? Out
    : Every8th<T, readonly [...I, 0],
        I['length'] extends 7 | 15 | 23 | 31 | 39 | 47 | 55 | 63
          ? readonly [...Out, T[I['length']]] : Out>;
export type Mini64<S extends string> = JoinStr<Every8th<Chars<S>>>;
```

Fixtures are authored `as const` and checked with `satisfies`, preserving
literal op tuples so per-wave statistics type-check:

```ts
export const wave = <const T extends readonly FixtureDelta[]>(deltas: T, seed: number) =>
  ({ seed, deltas }) satisfies FixtureWave;
```

Type-level tests:
`expectTypeOf<Mini64<'…known 64-hex…'>>().toEqualTypeOf<'…expected 8…'>()`.

**Core algebra** (small, fixed; used everywhere so shapes stay composable):

| Concept | Form |
|---|---|
| `Join<A,B>` | `readonly [A, B]`; curried `j(a)(b)`; `Twin<T> = Join<T,T>` |
| `Series<T>` | `readonly [number, (i: number) => T]` — length + indexer lazy view; never an iterator |
| `Cursor` | `type Cursor = Series<RowVec>` — result shape; NO walking/advance API exists anywhere |
| `AsyncSeries<T>` | `{ next(): Promise<T \| null> }` — IO boundary only |

```ts
export type Join<A, B> = readonly [A, B];
export type Twin<T> = Join<T, T>;
export const j = <A,>(a: A) => <B,>(b: B): Join<A, B> => [a, b];

export type Series<T> = readonly [number, (i: number) => T];
export const series = <T>(size: number, at: (i: number) => T): Series<T> => [size, at];

export type RowVec = readonly unknown[];
export type Cursor = Series<RowVec>;

export interface AsyncSeries<T> { next(): Promise<T | null>; }   // null = exhausted
```

Rule: every in-memory collection the engine grades is a `Series<T>`. Waves
stream in as `AsyncSeries<Delta>` and are materialized once, deterministically
ordered; nothing downstream re-reads storage.

## 3. Line identity and the funnel

Content identity: `lineIdOf(line) = sha256(trim(line))`. A file's **spine**
splits on newline, trims each line, and is fingerprinted by `spineCid(spine)`
over its canonical lineIds. `trim()` at both levels is what makes whitespace
noise (indentation churn, trailing spaces) invisible to identity.

The **funnel** is the cheap first question asked of every atom:

```ts
export interface FunnelHashIndex {
  readonly size: number;             // atoms retained
  readonly slack: number;            // FP budget (default 0.20; tunable)
  contains(key: LineId): boolean;    // false ⇒ certainly absent
}
export const build = async (keys: AsyncSeries<LineId>, seed: number, slack?: number): Promise<FunnelHashIndex>;
```

`contains === false` is authoritative — the atom cannot be `INHERITED`, skip
all further ancestry work. `true` defers to a false-positive ladder, explicit
and measured in tests:

| rung | check | approx FP |
|---|---|---|
| 0 | full ContentId equality | ≈ 1 |
| 1 | NeighborPrefix low 8 bits | 1/256 |
| 2 | full NeighborStamp 16 bits | 1/65536 |

Verdicts require rung 2; rung 1 is a ladder rung only. Stamp width (16) is
*tunable*, fixed within a change set.

**Neighbor-linked identity** — F1's mechanism. A line is identified by its
content AND its two neighbors:

```ts
export type NeighborStamp = `${HexChar}${HexChar}${HexChar}${HexChar}`; // 16 bits
export const EDGE: NeighborStamp = '0000';   // head/tail sentinel

export type LinkedKey = `${NeighborStamp}${NeighborStamp}${Mini64Str}`; // prev16 ‖ next16 ‖ fold

export interface LineCasEntry {
  readonly lineId: LineId;
  readonly prev: NeighborStamp;   // EDGE at file head
  readonly next: NeighborStamp;   // EDGE at file tail
  readonly linkedKey: LinkedKey;
}
export const stampLine = async (prev: LineId | null, line: string, next: LineId | null): Promise<LineCasEntry>;

export type MatchGrade = 'LINKED' | 'PARTIAL_PREV' | 'PARTIAL_NEXT' | 'CONTENT_ONLY';
export const GRADE_RANK: Readonly<Record<MatchGrade, number>> =
  { LINKED: 3, PARTIAL_PREV: 2, PARTIAL_NEXT: 2, CONTENT_ONLY: 1 };
```

Reading the grades: both neighbors match → `LINKED` (same line in same
context — the strongest evidence of relocation target); one neighbor matches
→ `PARTIAL_*` (context shifted on one side); neither → `CONTENT_ONLY` (same
text, no context claim — weak).

## 4. Delta model

```ts
export type DeltaOp =
  | { readonly kind: 'insert'; readonly coord: Coordinate; readonly lines: readonly string[] }
  | { readonly kind: 'delete'; readonly coord: Coordinate; readonly count: number }
  | { readonly kind: 'move';   readonly from: Coordinate; readonly to: Coordinate; readonly count: number }
  | { readonly kind: 'edit';   readonly coord: Coordinate; readonly before: readonly string[]; readonly after: readonly string[] };

export type Coordinate = {
  readonly treePath: readonly string[];   // file's path in the workspace tree
  readonly spineOffset: number;           // line offset within that file
  readonly spineCid: ContentId;           // file version this op was authored against
};

export type ReplicaId = string;
export type VectorClock = Readonly<Record<ReplicaId, number>>;

export interface Delta<const Ops extends readonly DeltaOp[] = readonly DeltaOp[]> {
  readonly id: DeltaId;
  readonly parents: readonly DeltaId[];   // causal DAG — writer B built on A's delta
  readonly clock: VectorClock;            // max of parents, own tick +1
  readonly baseSpineCid: ContentId;
  readonly ops: Ops;                      // const-captured literal tuple
  readonly author: ReplicaId;
}
```

`spineCid` mismatch on a coordinate means the op was authored against a stale
file version — evidence toward `SUPERSEDED`/`RELOCATED` rather than `NOVEL`.
Deep = long parent chains; wide = many deltas sharing one parent.

## 5. Grading

Decision procedure, deterministic, in order:

1. Order the wave by (clock rank, DeltaId).
2. `happens-before(a,b)` ⟺ componentwise ≤ and a ≠ b. Concurrent deltas at
   the same Coordinate with differing content ⇒ `CONFLICT` cluster, both
   survivors recorded. Never coalesce.
3. Per added line: funnel `contains(lineId)`?
   - no ⇒ LineCas lane: does `(prev,line)` or `(line,next)` adjacency exist
     anywhere in the tree's spines? yes ⇒ `RELOCATED` (adopt at the matched
     position); else ⇒ `NOVEL`.
   - yes ⇒ `INHERITED` (ancestor content) / `DUPLICATE` (in-wave sibling
     already contributed it) / `SUPERSEDED` (an in-wave delta subsumes both
     content and coordinate) per ancestry and in-wave set.
4. Relocations are coordinate rewrites: the applicator moves the atom, the
   receipt counts it in `relocatedCount`.

Ties resolve by (grade rank desc, ContentId, DeltaId) everywhere.

## 6. Engine

```
AsyncSeries<Delta>
  → spine        per-delta line spines + stamps
  → funnel       build/index the tree's funnel (seeded, slack)
  → residuals    atoms not authoritatively absent
  → topology     coordinate + adjacency topology per cluster
  → grade        verdict lattice (§5)
  → applicator   apply survivor set to a fresh tree snapshot
  → gate         tsc --noEmit + vitest run (io.gate, injected)
  → MergeReceipt
```

```ts
export interface MergeReceipt {
  readonly waveId: string;
  readonly sources: number;
  readonly atoms: { readonly kept: number; readonly inherited: number;
                   readonly duplicates: number; readonly superseded: number;
                   readonly relocated: number; readonly novel: number;
                   readonly conflict: number; readonly dropped: number };
  readonly clusters: readonly ClusterReceipt[];
  readonly applied: boolean;                    // applicator ran to completion
  readonly survivorCid: ContentId | null;       // hash of applied tree
  readonly gate: { readonly typechecked: boolean; readonly tested: boolean } | null;
}

export interface MergeIo {
  readonly cas: { put(cid: ContentId, bytes: Uint8Array): Promise<void>;
                  get(cid: ContentId): Promise<Uint8Array | null>; };
  readonly gate: (workdir: string) => Promise<{ typechecked: boolean; tested: boolean; log: string }>;
}

export const merge = async (wave: AsyncSeries<Delta>, master: MasterSpines,
                            io: MergeIo): Promise<MergeReceipt>;
```

**Terminality (F2):** a receipt with `applied === false` or `gate === null`
is non-terminal. Classification alone never closes a merge.

**Concurrency at 1000:** stages are `AsyncSeries` transforms with no shared
mutable state; the only writes target `io.cas`. One cursor in flight per
stage; spines flow unordered, then a single deterministic ordering pass
precedes grading. Deltas in the same clock rank grade as a set with
commutative outcome — the result is invariant under arrival order.

Determinism rules:
- D1 canonical JSON (sorted keys, no whitespace) for all hashed serializations.
- D2 no `Date.now` / `Math.random` in engine paths; seeded PRNG injected.
- D3 every set iteration ordered by (grade rank desc, ContentId, DeltaId).
- D4 receipt serialization is content-addressed; identical inputs ⇒ identical
  receipt bytes.

## 7. Visualization

MergeWave graph: nodes = deltas; solid edges = causal parents; dashed red
edges = conflict pairs; columns = vector-clock strata. Wide strata collapse
into bands of ≤ 64 nodes with aggregate verdict chips.

Fixed encodings — shape by verdict (◆ NOVEL ● INHERITED ▲ RELOCATED
■ SUPERSEDED ✕ CONFLICT); edge style by MatchGrade (LINKED solid-dark,
PARTIAL mid, CONTENT_ONLY light); border thickness by atom count.

```ts
export interface WaveLayout { readonly nodes: readonly PositionedNode[]; readonly edges: readonly Edge[]; }
export const layoutWave = (wave: MergeWaveGraph): WaveLayout;              // pure
export const renderAscii = (l: WaveLayout, opts?: AsciiOpts): string;      // terminal-safe
export const renderCanvas = (l: WaveLayout, ctx: CanvasRenderingContext2D): void; // 2D, virtualized
```

ASCII: monospace, no ANSI color required, legend in header, width clamped to
100 cols, deep chains vertical with `│` rails and `├─` forks. Canvas: 2D only,
viewport-virtualized, 1000 nodes without layout jank.

```
 stratum 0            stratum 1               stratum 2
 ●─base ─┬─ ● inh ──────●─┐
         ├─ ▲ reloc ──────┼─◆ novel ── ✕═✕ conflict (dashed pair)
         └─ ■ supers ─────┘
```

## 8. Package layout and discipline

```
src/core/     brands.ts hex.ts mini64.ts join.ts series.ts
src/funnel/   funnel-hash-index.ts line-cas.ts grades.ts
src/delta/    ops.ts clock.ts coordinate.ts
src/merge/    engine.ts grade.ts applicator.ts gate.ts receipt.ts
src/viz/      wave-graph.ts ascii.ts canvas.ts
test/         type-level.ts  determinism.test.ts  relocated.test.ts
              gate.test.ts  scale-1000.test.ts  honesty.test.ts
```

RED-first: `relocated.test.ts` and `gate.test.ts` are authored failing
against a stub engine before implementation. Gates: `tsc --noEmit`,
`vitest run`.

## 9. Acceptance

- [ ] Mini64 type fold ≡ runtime fold on ≥ 10⁴ random 64-hex inputs
- [ ] content-novel + adjacency-present lines grade RELOCATED, not NOVEL
- [ ] concurrent same-coordinate edits surface CONFLICT; nothing silently coalesces
- [ ] relocations are moves: `relocatedCount > 0` on constructed move cases
- [ ] receipt claims match applied reality exactly (claimed set ≡ applied set)
- [ ] no terminal receipt without `applied && gate.typechecked && gate.tested`
- [ ] 1000-delta wave: order-shuffled double-run produces byte-identical receipts
- [ ] 1000-delta wave completes without materializing unbounded intermediates
- [ ] ASCII renderer: legend + 100-col clamp; canvas renderer: 1000 nodes, virtualized
- [ ] `tsc --noEmit` and `vitest run` green
