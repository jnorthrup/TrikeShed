# Plan: Manifold Semantic Layer

## Purpose

Add the minimum working manifold surface to TrikeShed itself in pure Kotlin/commonMain:

- semantic coordinates first
- dense lowered coordinates second
- charts and atlas as repo-native semantic objects
- no dependency on cppfort
- no dependency on Kotlingrad for the implementation

## Bounded Corpus

- `src/commonMain/kotlin/borg/trikeshed/manifold/Manifold.kt`
- `src/jvmTest/kotlin/borg/trikeshed/manifold/ManifoldTest.kt`
- `conductor/tracks/manifold-semantic-layer_20260311/plan.md`
- `conductor/tracks.md`
- `conductor/product.md`

## Slices

- `manifold-01` ✅ semantic coordinates + dense separation
- `manifold-02` ✅ chart + atlas lookup
- `manifold-03` ✅ transition/reprojection contracts
- `manifold-04` tangent/jacobian follow-on only if demanded by real callers

## Acceptance Gates

- The semantic coordinate type must not also be the dense storage type.
- Charts must enforce consistent dimension at the atlas boundary.
- The initial surface must stay in `commonMain`.
- The implementation must stand without Kotlingrad.

## Verification

- `./gradlew jvmTest --tests "borg.trikeshed.manifold.ManifoldTest"`
