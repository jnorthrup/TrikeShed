# R2 evidence packet

`verification.json` identifies tested commits, toolchain, source hashes, exact
CAS bytes, selected regression results, target compilation and limits.
`SHA256.json` hashes the packet files. Inspect the manifest before treating a
historical receipt as evidence for a later source revision.

- `program.json`, `receipt.json`, `invocations.json` and `source.json` are exact
  retained CAS bytes. Their digests match the identities in the observed HTTP
  response. `receipt.json` is the committed command envelope, not a reformatted
  preview. `response-preview.json` is separately labeled parsed response data.
- `corpus.json` records successful native/Kotlin geometry comparisons.
  `yaml-disagreement.json` retains the measured disagreement on `v: 1e++5`.
- JUnit XML records the final selected regressions. Two pre-existing native
  Horn endpoint tests require the unconfigured `trikeshed.native.mlnix` system
  property; their conditional skips are not passes. The corresponding pure
  reviewed paths execute. No source or test exclusions were added.
- `gradle-confix-d3m-offline.txt` is the final JVM run under an actual Linux
  user/network namespace, with loopback only and no external route. The earlier
  combined-source run is retained separately; the intervening delta is four
  JVM test files using public interfaces.
- `confix-offline-rebuild.txt` records a fresh clang/link/cmp under the same
  network-isolation mechanism. It produced the exact retained module bytes.
- `gradle-confix-d3m-targets-staged.txt` is the connected target build, including
  both changed browser applications. Its first attempt lacked an existing
  Skiko dependency in the separate cache; that failed log is retained. This is
  not clean-machine offline distribution proof.

## Reproduce the final JVM run

Set `JAVA_HOME` to GraalVM CE 25.3.4.1+1.1 and stage the dependencies/toolchains
specified in the [runbook](../../02-confix.md). From the repository root on
Linux, use an unprivileged namespace where supported:

```sh
unshare --user --map-current-user --net --keep-caps sh -c '
  ip link set lo up && ip -brief address && ip route &&
  exec ./gradlew --offline --no-daemon --console=plain --max-workers=1 \
    verifyConfixBitcode -PsulongLlvmBin=/absolute/LLVM-22.1.8-Linux-X64/bin \
    jvmTest --rerun \
    --tests "*LlvmScalarTest" --tests "*LlvmScalarRunTest" \
    --tests "*LlvmConfixTest" --tests "*LlvmConfixRunTest" \
    --tests "*NativeHornProfileTest" \
    --tests "*EnglishCopularPresetTest" --tests "*EnglishCopularPresetExecutionTest" \
    --tests "*EnglishTaxonomyCarrierTest" --tests "*EnglishTaxonomyExportGuardTest" \
    --tests "*EnglishTaxonomyWireTest" --tests "*PresetAssemblyTest" \
    --tests "*ReviewedTaxonomy*Test" --tests "*ReviewedKif*Test" \
    --tests "*CurationReviewedReadTest" --tests "*EnglishObservationWireTest"
'
```

This selects meaningful current regressions; it does not claim the entire
repository test suite passed. All JVM main/test sources compile. Do not share
one Gradle user home across simultaneous isolated network namespaces: its lock
coordination requires connectivity. Use separate staged caches or run serially.

The target compilation command is:

```sh
./gradlew --no-daemon --console=plain --max-workers=1 \
  compileKotlinJs compileDocumentsKotlinJs compileSpacegraphKotlinJs \
  compileKotlinWasmJs compileKotlinWasmWasi compileKotlinLinuxX64
```

The native sanitizer corpus is a separate [audit](../confix-native-20261104/reproduction.md).
It establishes bounded observed behavior of the compiled scanner/bridge, not
Sulong isolation or a proof for all inputs. The [R3 handoff](../../03-visible-traversal.md)
records the pending browser presentation and wire-animation work.
