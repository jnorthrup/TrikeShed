# Tasks 21–27 reconciliation — pointcut/hotswap track (capped 2026-08-23)

Track: `2026-08-22-tasks21-27-map.md` (7 tasks, sessions 5500648076507182610 … 13148976034000979488).
Opened: 2026-08-22. Capped: 2026-08-23. Reconciled against the working tree, not the cloud board.

| # | task | disposition | evidence |
|---|---|---|---|
| 21 | Atomic compile feed for live dir (hotswapFeed task) | LANDED | `build.gradle.kts:995` `tasks.register<Exec>("hotswapFeed")`, dependsOn compileKotlinJvm + jvmProcessResources |
| 22 | Wire HotSwapAgent for real (needs 21) | LANDED | `src/jvmMain/kotlin/borg/trikeshed/daemon/HotSwapAgent.kt:23` `object HotSwapAgent` (5 KB, 2026-08-22) |
| 23 | Polyglot → PointcutCoordinate bridge | DEFERRED→vm-substrate | `PointcutCoordinate` lives in `classfile/model/PointcutCoordinate.kt` and `pointcut/polyglot/TspyPolyglotHost.kt`; zero references under `graal/` — no bridge on the Graal side |
| 24 | Landings circulate into the store (needs 3) | DEFERRED→vm-substrate | no landing→store circulation in `pointcut/**`; external dependency (task 3 seq log) never closed |
| 25 | Close the loop: landing becomes a card (needs 24) | DEFERRED→vm-substrate | blocked on 24; no landing→card path in tree |
| 26 | Kata sandbox (needs 23) | LANDED | `src/jvmMain/kotlin/borg/trikeshed/pointcut/KataSandboxRunner.kt:5` `object KataSandboxRunner` (+ `fun main`); branch kata-sandbox-runner merged in the 2026-08-22 funnel drain |
| 27 | LLVM facet — Graal adjunct | LANDED | `src/commonMain/kotlin/borg/trikeshed/pointcut/VmFacet.kt:9` `GRAAL_LLVM("llvm")`; `pointcut/SubgraalPointcutRunner.kt:19` consumes the facet |

Follow-up owner for 23/24/25: the vm-substrate track (Graal sub-VM bounds, `graal/subvm`).
Not re-dispatched from this map.

Rows: 7. Terminal sessions reconciled: 7. INCOMPLETE: none.
