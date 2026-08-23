# Legion counter-threat reconciliation — X14/X15 wave 2 (capped 2026-08-23)

Track: `2026-08-18-legion-counter-threat.session-map.md` / `.tasks.txt`.
Opened: 2026-08-18 (X14/X15 dispatched wave 2 after the 2026-08-18 drain freed slots; X2–X13, X16 already
reconciled in `2026-08-18-drain-reconciliation.md`). Capped: 2026-08-23.

| # | task | disposition | evidence |
|---|---|---|---|
| X14 | Red-Green jvmTest coverage for CrossInstanceCollusionDetector drain integration (session 3932338514890529272) | DEFERRED→soak | `src/jvmTest/kotlin/borg/trikeshed/jules/CrossInstanceCollusionDetectorTest.kt` (3 `@Test`, 2026-08-22) drives `userspace.containment.CrossInstanceCollusionDetector` directly — no `FlywheelDriver` drain wiring exercised; the brief's gate `--tests "*containment*"` does not match the class name. Drain-path integration remains soak task 44 (collusion signal → quarantine gate) |
| X15 | Deterministic clock for all stat-like ops in FunctionalUringFacade (session 2250640878427217467) | DEFERRED→soak | `src/commonMain/kotlin/borg/trikeshed/userspace/FunctionalUringFacade.kt:70-80` `METADATA_QUANTIZED_OPS` = STATX, FGETXATTR, GETXATTR, FLISTXATTR, LISTXATTR, GETDENTS (quantized to `syntheticEpoch`, `:95`); the verifying tests are hidden — `build.gradle.kts:363-364` excludes `FunctionalUringFacadeTest.kt` / `FunctionalUringFacadeXattrTest.kt`. No deterministic-clock assertion runs. Remains soak task 43 (un-exclude uring/ByteRegion 15) |

Rows: 2. Terminal sessions reconciled: 2. INCOMPLETE: none.
