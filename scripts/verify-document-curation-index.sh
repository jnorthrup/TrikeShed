#!/usr/bin/env bash
# Source-overlay verification only; not a clean build or live-daemon validation.
set -euo pipefail
if [[ $# -lt 2 || $# -gt 3 ]]; then
  printf 'Usage: %s SUPPORT_JAR MANAGED_SUBVM_ROOT [OUTPUT_DIRECTORY]\n' "$0" >&2
  exit 2
fi
support_jar=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
subvm_root=$(cd "$2" && pwd)
output_dir=${3:-/private/tmp/trikeshed-document-curation-index}
mkdir -p "$output_dir"
output_dir=$(cd "$output_dir" && pwd)
repository=$(cd "$(dirname "$0")/.." && pwd)
cd "$repository"
compiler=${KOTLINC:-$(command -v kotlinc)}
kotlin_lib=$(cd "$(dirname "$compiler")/../lib" && pwd)
cache=${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1
jar() {
  local group=$1 artifact=$2 version=$3 candidate
  for candidate in "$cache/$group/$artifact/$version"/*/"$artifact-$version.jar"; do
    if [[ -f "$candidate" ]]; then printf '%s' "$candidate"; return; fi
  done
  printf 'Missing cached dependency: %s:%s:%s\n' "$group" "$artifact" "$version" >&2
  return 1
}
coroutines=$(jar org.jetbrains.kotlinx kotlinx-coroutines-core-jvm 1.11.0)
coroutines_test=$(jar org.jetbrains.kotlinx kotlinx-coroutines-test-jvm 1.11.0)
serialization=$(jar org.jetbrains.kotlinx kotlinx-serialization-core-jvm 1.9.0)
datetime=$(jar org.jetbrains.kotlinx kotlinx-datetime-jvm 0.8.0-0.6.x-compat)
junit=$(jar junit junit 4.13.2)
hamcrest=$(jar org.hamcrest hamcrest-core 1.3)
prod_sources=(
  src/commonMain/kotlin/borg/trikeshed/parse/json/Json.kt
  src/commonMain/kotlin/borg/trikeshed/parse/json/JsonSupport.kt
  src/commonMain/kotlin/borg/trikeshed/context/Lifecycle.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/nio/Volume.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/nio/DocumentInputElement.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/concurrency/Channel.kt
  src/commonMain/kotlin/borg/trikeshed/nlp/NlpDocument.kt
  src/commonMain/kotlin/borg/trikeshed/modelmux/ModelUsage.kt
  src/commonMain/kotlin/borg/trikeshed/modelmux/PromptMessage.kt
  src/commonMain/kotlin/borg/trikeshed/modelmux/Prompt.kt
  src/commonMain/kotlin/borg/trikeshed/modelmux/ModelResponse.kt
  src/commonMain/kotlin/borg/trikeshed/narsese/DocumentCurator.kt
  src/commonMain/kotlin/borg/trikeshed/narsese/DocumentCuratorCodec.kt
  src/commonMain/kotlin/borg/trikeshed/narsese/DocumentCuratorGrounding.kt
  src/commonMain/kotlin/borg/trikeshed/narsese/DocumentCuratorElement.kt
  src/commonMain/kotlin/borg/trikeshed/narsese/DocumentModel.kt
  src/commonMain/kotlin/borg/trikeshed/lcnc/CamelLinkage.kt
  src/jvmMain/kotlin/borg/trikeshed/graal/subvm/CamelCatalog.kt
  src/jvmMain/kotlin/borg/trikeshed/graal/subvm/GuestModules.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/nio/channels/spi/ProcessOperations.kt
  src/jvmMain/kotlin/borg/trikeshed/userspace/nio/channels/spi/JvmProcessOperations.kt
  src/jvmMain/kotlin/borg/trikeshed/graal/subvm/TikaRuntime.kt
  src/jvmMain/kotlin/borg/trikeshed/graal/subvm/CamelRuntime.kt
  src/jvmMain/kotlin/borg/trikeshed/graal/subvm/CoreNlpRuntime.kt
  src/jvmMain/kotlin/borg/trikeshed/graal/subvm/DocumentFeed.kt

  src/commonMain/kotlin/borg/trikeshed/lib/FacetedRow.kt
  src/commonMain/kotlin/borg/trikeshed/lib/OpK.kt
  src/commonMain/kotlin/borg/trikeshed/narsese/DocumentCurationIndex.kt
  src/commonMain/kotlin/borg/trikeshed/forge/sheet/CursorSheet.kt
  src/commonMain/kotlin/borg/trikeshed/lcnc/LcncGraph.kt
  src/commonMain/kotlin/borg/trikeshed/lcnc/LcncContracts.kt
  src/commonMain/kotlin/borg/trikeshed/lcnc/LcncNodeKey.kt
  src/commonMain/kotlin/borg/trikeshed/lcnc/LcncNodeElement.kt
  src/commonMain/kotlin/borg/trikeshed/lcnc/LcncRunner.kt
  src/commonMain/kotlin/borg/trikeshed/lcnc/LcncServices.kt
  src/commonMain/kotlin/borg/trikeshed/lcnc/LcncSheetNodes.kt
  src/commonMain/kotlin/borg/trikeshed/lcnc/VmRuntimeNodes.kt
  src/jvmMain/kotlin/borg/trikeshed/lcnc/DocumentCurationLegos.kt
  src/jvmMain/kotlin/borg/trikeshed/lcnc/SubVmLegos.kt
  src/jvmMain/kotlin/borg/trikeshed/graal/subvm/harness/DocumentCurationIndexHarness.kt
)
compile_cp="${TRIKESHED_IO_JAR:+$TRIKESHED_IO_JAR:}$support_jar:$coroutines:$serialization:$datetime${TRIKESHED_COMPOSE_RUNTIME:+:$TRIKESHED_COMPOSE_RUNTIME}"
compiler_plugins=()
if [[ -n "${TRIKESHED_COMPOSE_RUNTIME:-}" ]]; then
  compiler_plugins+=("-Xplugin=$kotlin_lib/compose-compiler-plugin.jar")
fi
overlay="$output_dir/document-curation-index.jar"
run_cp="$overlay:$compile_cp:$kotlin_lib/kotlin-stdlib.jar:$kotlin_lib/kotlin-stdlib-jdk8.jar"
test_sources=(
  src/jvmTest/kotlin/borg/trikeshed/narsese/DocumentCuratorTest.kt
  src/jvmTest/kotlin/borg/trikeshed/lcnc/DocumentCurationLegosTest.kt
  src/commonTest/kotlin/borg/trikeshed/narsese/DocumentCurationIndexTest.kt
)
date -u '+%Y-%m-%dT%H:%M:%SZ' > "$output_dir/verified-at.txt"
java -version > "$output_dir/toolchain.txt" 2>&1
"$compiler" -version >> "$output_dir/toolchain.txt" 2>&1
shasum -a 256 "${prod_sources[@]}" "${test_sources[@]}" scripts/verify-document-curation-index.sh "$support_jar" > "$output_dir/source.sha256"
if [[ -n "${TRIKESHED_IO_JAR:-}" ]]; then shasum -a 256 "$TRIKESHED_IO_JAR" >> "$output_dir/source.sha256"; fi
if [[ -n "${TRIKESHED_COMPOSE_RUNTIME:-}" ]]; then
  IFS=: read -r -a compose_jars <<< "$TRIKESHED_COMPOSE_RUNTIME"
  shasum -a 256 "${compose_jars[@]}" "$kotlin_lib/compose-compiler-plugin.jar" >> "$output_dir/source.sha256"
fi
printf '%s\n' "$run_cp" > "$output_dir/classpath.txt"
"$compiler" "${compiler_plugins[@]}" -jvm-target 25 -classpath "$compile_cp" -d "$overlay" "${prod_sources[@]}" > "$output_dir/compile.out" 2>&1
printf 'compile: OK\n'
# Missing NLP is process-local test configuration; never modify installed module directories.
absent_root="$output_dir/subvm-without-corenlp"
mkdir -p "$absent_root"
for module in tika camel; do
  if [[ ! -e "$absent_root/$module" ]]; then ln -s "$subvm_root/$module" "$absent_root/$module"; fi
done
java -Xmx3g -Djava.awt.headless=true -Dtrikeshed.subvm.home="$subvm_root" -cp "$run_cp" \
  borg.trikeshed.graal.subvm.harness.DocumentCurationIndexHarness "$repository" "$absent_root" \
  > "$output_dir/harness.raw.out" 2> "$output_dir/harness.err"
python3 - "$output_dir/harness.raw.out" "$output_dir/sample.json" <<'CLEAN'
import json, pathlib, sys
rows = pathlib.Path(sys.argv[1]).read_text().splitlines()
result = next(json.loads(row) for row in reversed(rows) if row.startswith('{'))
pathlib.Path(sys.argv[2]).write_text(json.dumps(result, indent=2, ensure_ascii=False) + '\n')
CLEAN
printf 'connected LCNC harness: OK\n'
test_cp="$run_cp:$coroutines_test:$kotlin_lib/kotlin-test.jar:$kotlin_lib/kotlin-test-junit.jar:$junit:$hamcrest"
"$compiler" -jvm-target 25 -Xfriend-paths="$overlay" -classpath "$test_cp" -d "$output_dir/tests.jar" \
  "${test_sources[@]}" > "$output_dir/tests-compile.out" 2>&1
java -Xmx3g -cp "$output_dir/tests.jar:$test_cp" borg.trikeshed.narsese.DocumentCuratorTestMain \
  > "$output_dir/curator-tests.out" 2>&1
java -Xmx3g -cp "$output_dir/tests.jar:$test_cp" org.junit.runner.JUnitCore \
  borg.trikeshed.narsese.DocumentCurationIndexTest borg.trikeshed.lcnc.DocumentCurationLegosTest \
  > "$output_dir/facet-tests.out" 2>&1
printf 'curator and facet regressions: OK\nArtifacts: %s\n' "$output_dir"
