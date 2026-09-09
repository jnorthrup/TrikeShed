# Daemon Launch Guide

Gradle owns checkout builds, the live class feed, and foreground daemon launch.

## Launch

```text
./gradlew runOroborosDaemon --args='--watch --belief-bag --kanban-port 8888 /tmp/trikeshed-forge /Users/jim/work/TrikeShed'
```

The final two arguments are the forge home and repository directory, in that order.
Choose the forge home whose state you intend to use. Use another port for a second instance.
The task builds and stages dependencies, publishes build/live/classes, packages HotSwapAgent,
and launches with JVM resources and the agent on the classpath. Output goes to the terminal;
Ctrl-C ends the foreground run.

Use `-Pjdwp=5005` for debugging or `-Pjdwp=5005,suspend` to wait for the debugger.
Daemon flags pass through `--args`: `--once`, `--watch`, `--interval-ms N`,
`--kanban-port N`, `--belief-bag`, `--agents names`, and the Hermes flags.
The old wrapper's `--home`, `--repo`, `--debug`, and `--boot-forge` are not daemon flags.

## Feed and Checks

```text
./gradlew hotswapFeed
./gradlew oroborosDoctor -PdaemonUrl=http://127.0.0.1:8888
./gradlew commonMainPurity scriptPolicy --continue
./gradlew mux --args='doctor'
```

The feed publishes complete files using JVM atomic moves, removes obsolete files, then advances
build/live/.generation. It also stages runtime jars and the agent. The doctor checks HTTP 200
from health, board, panels, and Graal; it does not prove classfile ingestion.

A foreground Gradle daemon run retains the project build lock. Other Gradle invocations for
this checkout may wait until it ends. A Gradle-owned background start/stop lifecycle is still
required for concurrent feed and doctor workflows.

## Verification and Remaining Migration

### AOT

```text
./gradlew stageDaemonAot verifyDaemonAot -PaotWarmSeconds=2
./gradlew runOroborosDaemonAot --args='--watch --kanban-port 8901 /tmp/trikeshed-aot-forge /Users/jim/work/TrikeShed'
```

Training uses JARs and writes `build/staging/oroboros.aot`. The default warm-up is
30 seconds; `-PaotWarmSeconds=0..30` changes it and `-PaotTrainPort=8971` selects
the isolated probe port. Training and verification each have a 180-second task timeout.
The verifier stops its daemon after checking health and AOT-linked class activation.

The AOT launch explicitly sets `TRIKESHED_AOT_DEFAULT=1`, `-XX:AOTMode=on`,
`-XX:AOTCache=...` and `-Xlog:aot=info`. JVMCI module selection is identical during
training and consumption. Missing or incompatible caches fail; retrain after JAR/JDK changes.
The legacy `-PdaemonArgs` property remains available, with `--args` preferred.

Inspect live switches at `/api/graal/aot`. Training and verification logs are
`build/reports/aot/train.log` and `build/reports/aot/verify.log`.
The ordinary `runOroborosDaemon` remains the exploded-class HotSwap launch;
use `runOroborosDaemonAot` for the JAR-based AOT launch.

All AOT tasks also stage `hotswapFeed` for classfile inspection. The daemon ingests
those class blobs before Git/worktree scans and belief seeding, independently of its
runtime classpath. `verifyDaemonAot` requires a populated classfile TreeSheet whose
blob matches the running JVM. This supplies class facets in AOT mode; it does not
install a reloader. Recompiled blobs can differ from the running AOT JAR until restart.

On 2026-09-08 a focused train/verify run completed in 13 seconds and confirmed
`Using AOT-linked classes: true`. This proves archive use, not a measured startup speedup.

The current panel displays process state. Its download and Couch/CAS capture actions appear
once an archive exists; capture replicates the archive and does not enable JVM AOT.
No runtime enable/disable switch or end-recording control is implemented in this panel.

On 2026-09-08 the Gradle feed completed and an isolated Gradle launch on port 8897 loaded
HotSwapAgent and served HTTP. Its classfile projection still returned `class_blob_missing`.
Class attachment ingestion and source-to-class decompilation remain unverified.

The no-script migration is incomplete. `scriptPolicy` is attached to `check` and deliberately
fails while operator scripts or inline shell launches remain. See
[Gradle migration](gradle-migration.md) for the remaining behavior that must move before deletion.
