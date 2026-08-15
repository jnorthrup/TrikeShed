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
