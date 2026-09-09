# Gradle Migration

The project rule is no operator scripts. Gradle is the entrypoint.

## Implemented

- `hotswapFeed`: JVM file comparison, atomic file publication, stale-file removal, then generation publication. No rsync.
- `runOroborosDaemon`: depends on the feed and agent; runs live classes, resources, and staged jars with HotSwapAgent.
- `oroborosDoctor`: bounded JVM HTTP checks for health, board, panels, and Graal.
- `commonMainPurity`: reports forbidden JVM imports and fails. Removed its placeholder shell file and the placeholder doctor script.
- `scriptPolicy`: reports remaining operator entrypoints and inline Gradle shell launches; required by `check`.
- `stageDaemonAot`: JVM process lifecycle, bounded readiness/shutdown, archive validation and atomic publication. No inline shell remains in this task.
- `runOroborosDaemonAot`: required AOT cache with visible JVM logs and `TRIKESHED_AOT_DEFAULT=1`.
- `verifyDaemonAot`: isolated boot verifies live switches, mapped cache, AOT-linked classes and HTTP health, then stops.

## Red Evidence

`./gradlew commonMainPurity scriptPolicy --continue` fails with actionable paths.
The purity violation observed on 2026-09-08 is the ConcurrentHashMap import in
`src/commonMain/kotlin/borg/trikeshed/couch/IncrementalViewRegistry.kt`.
This was previously hidden by an ignored shell exit code.

The Gradle feed completed. A separate Gradle daemon launch loaded the agent and served
port 8897, but `/api/graal/classfile` returned `class_blob_missing` for the live
HotSwapAgent class. Populated build directories do not establish Couch attachment ingestion.
The production port 8888 was unreachable during this check.

## Remaining Behavior

| Surface | Behavior still requiring migration before removal |
| --- | --- |
| `bin/oroboros-daemon` | CAS-only classpath hydration, per-instance logs, wrapper environment/argument translations |
| `bin/oroboros-up` | Gradle-owned background process, PID ownership, stop task, port selection, readiness, browser opening |
| `bin/com.trikeshed.oroboros.plist` | Replace the shell launcher dependency and service installation workflow |
| `bin/mux`, `bin/modelmux-cli` | Audit wrapper options against the existing Gradle `mux` task before removal |
| `bin/m5-*-run.sh`, `bin/trikeshed-btrfs`, `bin/archive-demo.cjs` | Platform/container/storage/demo behavior needs native task ownership |
| `scripts/kernel-parity.sh` | Pinned checkout acquisition and parity report generation |
| Remaining placeholder `scripts/*.sh` | Replace documented proof/gate claims with actual Gradle verification before deleting the references |

The policy task inventories top-level operator scripts, not embedded guest-language fixtures
under `bin/jvmMain` or application resources. Historical guides still reference legacy commands;
the README and daemon launch guide now use Gradle. Foreground JavaExec retains the project lock,
so concurrent feed/doctor needs the background lifecycle migration above.

`runOroborosDaemonAot` and `subvmHarnessNative` invoke JDK executables directly;
they are not shell-script entrypoints. AOT training and consumption were verified on
2026-09-08 in 13 seconds: a 62,324,736-byte archive, 83,669 class CP entries,
and `Using AOT-linked classes: true` in the consumption log.
