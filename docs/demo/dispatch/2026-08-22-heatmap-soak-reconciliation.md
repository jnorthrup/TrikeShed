# Heat-map soak reconciliation — tasks 43–50 (capped 2026-08-23)

Track: `2026-08-22-heatmap-soak.md` (soak tasks 43–50, per heat zone).
Opened: 2026-08-22. Capped: 2026-08-23. Reconciled against the working tree and `build.gradle.kts` exclude block.
All rows hand off to the standing soak track; nothing here is closed as done.

| # | task | disposition | evidence |
|---|---|---|---|
| 43 | userspace — the rule becomes the fence (113 violating commonMain files) | DEFERRED→soak | commonMain still violates: `src/commonMain/kotlin/borg/trikeshed/htx/client/ipfs/CidAndStore.kt` `import java.`, 9 files with `java.`/`System.currentTimeMillis`; commonTest excludes `**/userspace/{btrfs,context,nio/ebpf,network,reactor}/**` (`build.gradle.kts:358-365`) unchanged |
| 44 | Dispatcher complex — freeze, fence, test | DEFERRED→soak | `src/jvmMain/kotlin/borg/trikeshed/jules/FlywheelDriver.kt` = 98,517 bytes (target < 40 KB); no `interface Flywheel` in `src/`; no `TRIKESHED_FLYWHEEL` switch in `src/` |
| 45 | WorkDrain.kt comes home | DEFERRED→soak | `src/commonMain/kotlin/forge/doc/WorkDrain.kt:1` still `package forge.doc` (17.5 KB, last touched 2026-08-08); no tests |
| 46 | One `Protocol` | DEFERRED→soak | three `enum class Protocol` remain: `litebike/taxonomy/Taxonomy.kt:38`, `userspace/network/Protocols.kt:9`, `reactor/ReactorAlgebra.kt:9` |
| 47 | Un-hide htx | DEFERRED→soak | `build.gradle.kts:370` `kotlin.exclude("**/htx/**")` still present |
| 48 | Parser round-trip property | PARTIAL→soak | only yaml round-trip tests exist (`src/jvmTest/kotlin/borg/trikeshed/parse/yaml/TestYamlRoundTrip.kt`, `TestStation17RoundTrip.kt`); no JsonSupport stringify∘parse property test; `gradle/js-target-debt.excludes:7` still lists `**/parse/json/Codec.kt` |
| 49 | Un-hide util/oroboros | DEFERRED→soak | `build.gradle.kts:372` `kotlin.exclude("**/util/oroboros/**")` still present |
| 50 | Sweep the dust | DEFERRED→soak | `build.gradle.kts:200` `**/classfile/slab/**` and `:369` `**/window/**` still excluded; dead jvmTest patterns (`:247-250`) still present |

Rows: 8. Terminal sessions reconciled: 8. INCOMPLETE: none.
