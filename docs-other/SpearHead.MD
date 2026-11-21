| trikeshed | | 3x3xX3N0N/TrikeShed |
|:-:|:-:|:-:|
| Issues | | Issues |
| #880 | | #1 |
| – | → | – |
| #940 | | #61 |

*3x3xX3N0N/TrikeShed — personal master*

memory as defined language over time a mindvirus
as the universe seems to [have a shape](https://en.wikipedia.org/wiki/Hopf_fibration)

```
                    HOPF FIBRATION
                    ==============

   ┌─────────┐    inclusion     ┌─────────┐    projection p    ┌─────────┐
   │   S¹    │ ───────────────► │   S³    │ ─────────────────► │   S²    │
   │ (fiber) │                  │ (total  │                    │ (base)  │
   │         │                  │  space) │                    │         │
   └─────────┘                  └─────────┘                    └─────────┘
   unit circle                  unit sphere                    Riemann
   in ℂ                         in ℂ²                          sphere ℂ ∪ {∞}
        │                            │                              │
        │ z ↦ (λz₁, λz₂)             │ (z₁, z₂)                     │ z₁ / z₂
        │ |λ| = 1                    │ |z₁|² + |z₂|² = 1            │
        ▼                            ▼                              ▼
   ┌─────────────────────────────────────────────────────────────────────┐
   │                                                                     │
   │   Every point of S² pulls back to a full circle S¹ in S³:           │
   │                                                                     │
   │        p⁻¹(point) ≅ S¹                                              │
   │                                                                     │
   │   Any two distinct fibers are linked circles (Hopf link).           │
   │                                                                     │
   └─────────────────────────────────────────────────────────────────────┘

   Local picture (trivial over open patches U ⊂ S²):

        p⁻¹(U) ≅ U × S¹
             │
             │ p
             ▼
             U

   Global picture: NOT trivial.  S³ ≇ S² × S¹
   (S³ is simply connected; S² × S¹ is not.)
   ```

```
                       HOPF FIBRATION  (mirrored, reversed)
                    ====================================

   ┌─────────┐    projection p    ┌─────────┐     inclusion    ┌─────────┐
   │   S²    │ ◄───────────────── │   S³    │ ◄─────────────── │   S¹    │
   │ (base)  │                    │ (total  │                  │ (fiber) │
   │         │                    │  space) │                  │         │
   └─────────┘                    └─────────┘                  └─────────┘
   Riemann                        unit sphere                  unit circle
   sphere ℂ ∪ {∞}                 in ℂ²                        in ℂ
        ▲                              ▲                            ▲
        │ z₁ / z₂                      │ (z₁, z₂)                   │ z ↦ (λz₁, λz₂)
        │                              │ |z₁|² + |z₂|² = 1          │ |λ| = 1
        │                              │                            │
   ┌─────────────────────────────────────────────────────────────────────┐
   │                                                                     │
   │   Every point of S² pulls back to a full circle S¹ in S³:           │
   │                                                                     │
   │        p⁻¹(point) ≅ S¹                                              │
   │                                                                     │
   │   Any two distinct fibers are linked circles (Hopf link).           │
   │                                                                     │
   └─────────────────────────────────────────────────────────────────────┘

   Local picture (trivial over open patches U ⊂ S²):

             U
             ▲
             │ p
             │
        p⁻¹(U) ≅ U × S¹

   Global picture: NOT trivial.  S³ ≇ S² × S¹
   (S³ is simply connected; S² × S¹ is not.)
```

```
                               HOPF FIBRATION — TWO MODELS, NO COLLISION
                            =========================================

   MODEL A · FORWARD (maps)              legend:  ι = inclusion   p = projection
   ──────────────────────────────────────────────────────────────────────────────
   ┌─────────┐        ι          ┌─────────┐        p          ┌─────────┐
   │  A.S¹   │ ────────────────► │  A.S³   │ ────────────────► │  A.S²   │
   │  fiber  │                   │  total  │                   │  base   │
   └─────────┘                   └─────────┘                   └─────────┘
   λ ∈ ℂ, |λ|=1                  (z₁,z₂) ∈ ℂ²                  z₁/z₂ ∈ ℂ∪{∞}
        │                             │                             │
        │ λ ↦ (λz₁, λz₂)              │ |z₁|²+|z₂|² = 1             │ [z₁ : z₂]
        ▼                             ▼                             ▼
   ┌──────────────────────────────────────────────────────────────────────────┐
   │  SHARED FACTS (read by both models)                                      │
   │                                                                          │
   │   p⁻¹(point) ≅ S¹            every base point lifts to one full circle   │
   │   fibers pairwise linked     Hopf link                                   │
   │   p⁻¹(U) ≅ U × S¹            locally trivial                             │
   │   S³ ≇ S² × S¹               globally nontrivial (π₁ differs)            │
   └──────────────────────────────────────────────────────────────────────────┘
        ▲                             ▲                             ▲
        │ ι⁻¹(orbit) = S¹             │ p⁻¹(z) = { (λz₁,λz₂) }       │ z = [z₁ : z₂]
        │                             │                             │
   ┌─────────┐        ι⁻¹        ┌─────────┐        p⁻¹        ┌─────────┐
   │  B.S²   │ ◄───────────────  │  B.S³   │ ◄───────────────  │  B.S¹   │
   │  base   │   (which fiber?)  │  total  │   (which circle?) │  fiber  │
   └─────────┘                   └─────────┘                   └─────────┘
   ──────────────────────────────────────────────────────────────────────────────
   MODEL B · REVERSE (preimages)         legend:  ι⁻¹ = fiber recovery   p⁻¹ = lift
   ```

`https://github.com/3x3xX3N0N/BikeChainz`
