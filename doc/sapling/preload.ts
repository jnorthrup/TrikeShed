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
