# Track: Native Grad Analytics

**Track ID:** `native-grad-analytics_20260314`
**Branch:** `master`
**Status:** ✅ Completed — 4 tests green

## Purpose

Reimplement the differentiable trading analytics layer in plain Kotlin `Double` arithmetic,
replacing the deleted kotlingrad dependency with self-contained forward-mode autodiff and
signal folds.  Four delivery surfaces:

| Surface | Replaces | Target file |
|---|---|---|
| `SeriesGrad.kt` | kotlingrad `SFun<DReal>` over `Series<Double>` | `src/jvmMain/kotlin/borg/trikeshed/grad/SeriesGrad.kt` |
| `GradOps.kt` | kotlingrad reverse-mode ops | `src/jvmMain/kotlin/borg/trikeshed/grad/GradOps.kt` |
| `DrawdownDsel.kt` | Drawdown DSEL with differentiable expressions | `src/jvmMain/kotlin/borg/trikeshed/grad/DrawdownDsel.kt` |
| `DiffDuckCursor.kt` | `emaFold`, `macdFold`, `softPnlFold` over DuckDB results | `src/jvmMain/kotlin/borg/trikeshed/duck/DiffDuckCursor.kt` |

`CompilerCache` is deferred — caching is only justified once the ops are stable.

## Architecture

All gradient computation uses **dual-number forward-mode autodiff**:

```
data class Dual(val v: Double, val dv: Double)
operator fun Dual.plus(o: Dual) = Dual(v + o.v, dv + o.dv)
operator fun Dual.times(o: Dual) = Dual(v * o.v, v * o.dv + dv * o.v)
// etc.
```

`SeriesGrad` wraps `Series<Double>` as `Series<Dual>`.
`GradOps` provides `grad(f: (Dual) -> Dual, x: Double): Double` and vectorised variants.
`DrawdownDsel` uses `GradOps` to compute ∂drawdown/∂weight for a position series.
`DiffDuckCursor` wraps a `Map<String, Series<Any?>>` (DuckSeries result) and exposes
`emaFold`, `macdFold`, `softPnlFold` over the typed double columns.

## Slices

- `grad-01` ✅ `Dual` number type + `GradOps` (grad, gradVec) in `src/jvmMain/kotlin/borg/trikeshed/grad/`
- `grad-02` ✅ `SeriesGrad` — `Series<Double>` → `Series<Dual>`, map/fold utilities
- `grad-03` ✅ `DrawdownDsel` — peak-to-trough drawdown, ∂drawdown/∂weight
- `grad-04` ✅ `DiffDuckCursor` — emaFold, macdFold, softPnlFold
- `grad-05` ✅ contract tests in `src/jvmTest/kotlin/borg/trikeshed/grad/` — smoke + gradient correctness

## Verification

```
./gradlew jvmTest --tests 'borg.trikeshed.grad.*' --tests 'borg.trikeshed.duck.DiffDuckCursorTest'
```

## Acceptance

- `Dual` forward-mode: `grad(x -> x*x, 3.0)` == `6.0`
- drawdown gradient: sign correct on trivial 3-step series
- `emaFold` output matches reference scalar EMA for span=2 on 5-element series
- `softPnlFold` returns a differentiable scalar (not NaN)
- all above verified by `jvmTest` passing
