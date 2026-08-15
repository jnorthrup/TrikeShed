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
 * Any implementation's mutable phases are construction-time folds hidden
 * behind these signatures. `view` is the SINGLE bridge to stdlib iterables;
 * materializing beyond that door is category demotion.
 */
export {};
