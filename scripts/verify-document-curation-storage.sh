#!/usr/bin/env bash
# Scoped current-source userspace IO + managed curation, with separate JVM reopen.
set -euo pipefail
if [[ $# -lt 2 || $# -gt 3 ]]; then
  printf 'Usage: %s SUPPORT_JAR MANAGED_SUBVM_ROOT [NEW_OUTPUT_DIRECTORY]\n' "$0" >&2
  exit 2
fi
support_jar=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
subvm_root=$(cd "$2" && pwd)
output_dir=${3:-$(mktemp -d /private/tmp/trikeshed-document-storage.XXXXXX)}
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
serialization=$(jar org.jetbrains.kotlinx kotlinx-serialization-core-jvm 1.9.0)
compose_runtime=$(jar androidx.compose.runtime runtime-desktop 1.11.2)
compose_annotations=$(jar androidx.compose.runtime runtime-annotation-jvm 1.11.2)
COMMON_SOURCES=(
  src/commonMain/kotlin/borg/trikeshed/userspace/FunctionalUringFacade.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/UserspaceIO.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/UringOp.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/Liburing.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/Channels.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/UringProbeReport.kt
  src/commonMain/kotlin/borg/trikeshed/platform/HostDescriptor.kt
  src/commonMain/kotlin/borg/trikeshed/platform/PlatformHost.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/UringModuleProbe.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/nio/channels/FileChannel.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/nio/channels/UringFileChannel.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/nio/channels/UringChannel.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/nio/channels/UringChannels.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/nio/file/File.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/nio/file/Files.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/nio/ByteBuffer.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/nio/Buffer.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/nio/Volume.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/nio/DocumentInputElement.kt
  src/commonMain/kotlin/borg/trikeshed/btrfs/BtrfsUringFileVolume.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/nio/ebpf/UringEbpf.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/nio/spi/NioCapabilityReport.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/nio/spi/NioCapabilityProbe.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/nio/spi/UringCapabilityReport.kt
)
JVM_SOURCES=(
  src/jvmMain/kotlin/borg/trikeshed/userspace/UserspaceIO.jvm.kt
  src/jvmMain/kotlin/borg/trikeshed/userspace/JvmUring.kt
  src/jvmMain/kotlin/borg/trikeshed/userspace/JvmUringDiscovery.kt
  src/jvmMain/kotlin/borg/trikeshed/platform/HostDescriptor.jvm.kt
  src/jvmMain/kotlin/borg/trikeshed/platform/PlatformHost.jvm.kt
  src/jvmMain/kotlin/borg/trikeshed/userspace/Liburing.jvm.kt
  src/jvmMain/kotlin/borg/trikeshed/userspace/nio/spi/NioCapabilityProbe.jvm.kt
)
io_cp="$support_jar:$coroutines:$serialization"
common_csv=$(IFS=,; printf '%s' "${COMMON_SOURCES[*]}")
shasum -a 256 "${COMMON_SOURCES[@]}" "${JVM_SOURCES[@]}" "$support_jar" > "$output_dir/io-source.sha256"
"$compiler" -jvm-target 25 -Xmulti-platform -Xcommon-sources="$common_csv" \
  -classpath "$io_cp" -d "$output_dir/uring-prod.jar" \
  "${COMMON_SOURCES[@]}" "${JVM_SOURCES[@]}" > "$output_dir/io-compile.out" 2>&1
printf 'current-source userspace IO: OK\n'
TRIKESHED_IO_JAR="$output_dir/uring-prod.jar" \
TRIKESHED_COMPOSE_RUNTIME="$compose_runtime:$compose_annotations" \
  bash scripts/verify-document-curation-index.sh "$support_jar" "$subvm_root" "$output_dir/index"

storage_sources=(
  src/commonMain/kotlin/borg/trikeshed/narsese/DocumentFrameFile.kt
  src/commonMain/kotlin/borg/trikeshed/narsese/DocumentCasStore.kt
  src/commonMain/kotlin/borg/trikeshed/narsese/DocumentAppendLog.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/nio/DocumentFeedStorage.kt
  src/commonMain/kotlin/borg/trikeshed/ccek/ForgeSignal.kt
  src/commonMain/kotlin/borg/trikeshed/ccek/ArticulatedNode.kt
  src/commonMain/kotlin/borg/trikeshed/ccek/UserContext.kt
  src/commonMain/kotlin/borg/trikeshed/lcnc/ccek/CcekReactorBinding.kt
  src/commonMain/kotlin/borg/trikeshed/lcnc/ccek/LcncCcekAssembly.kt
  src/jvmMain/kotlin/borg/trikeshed/module/ForgeModule.kt
  src/jvmMain/kotlin/borg/trikeshed/module/ModuleSupervisor.kt
  src/jvmMain/kotlin/borg/trikeshed/kanban/module/KanbanModule.kt
  src/jvmMain/kotlin/borg/trikeshed/kanban/module/LcncRunService.kt
  src/jvmMain/kotlin/borg/trikeshed/litebike/JvmKanbanServer.kt
  src/jvmMain/kotlin/borg/trikeshed/graal/subvm/harness/DocumentCurationStorageHarness.kt
)
base_cp=$(cat "$output_dir/index/classpath.txt")
# App runtime libraries are explicit cached dependencies; managed NLP stays in guest VMs.
app_cp="$base_cp:$(jar org.jetbrains.kotlinx kotlinx-serialization-json-jvm 1.9.0)"
shasum -a 256 "${storage_sources[@]}" scripts/verify-document-curation-storage.sh \
  scripts/verify-document-curation-http.py src/jvmTest/kotlin/borg/trikeshed/narsese/DocumentPersistenceTest.kt \
  > "$output_dir/storage-source.sha256"
"$compiler" -jvm-target 25 -Xplugin="$kotlin_lib/compose-compiler-plugin.jar" \
  -Xfriend-paths="$support_jar,$output_dir/index/document-curation-index.jar" \
  -classpath "$app_cp" -d "$output_dir/storage.jar" "${storage_sources[@]}" \
  > "$output_dir/storage-compile.out" 2>&1
printf 'current-source storage and route closure: OK\n'
run_cp="$output_dir/storage.jar:$app_cp"
printf '%s\n' "$run_cp" > "$output_dir/classpath.txt"
state_dir="$output_dir/state"
mkdir "$state_dir"
for phase in write reopen; do
  java -Xmx3g -Djava.awt.headless=true -Dtrikeshed.subvm.home="$subvm_root" \
    -cp "$run_cp" borg.trikeshed.graal.subvm.harness.DocumentCurationStorageHarness \
    "$phase" "$state_dir" "$repository" > "$output_dir/$phase.raw.out" 2> "$output_dir/$phase.err"
  python3 - "$output_dir/$phase.raw.out" "$output_dir/$phase.json" <<'CLEAN'
import json, pathlib, sys
rows = pathlib.Path(sys.argv[1]).read_text().splitlines()
result = next(json.loads(row) for row in reversed(rows) if row.startswith('{'))
pathlib.Path(sys.argv[2]).write_text(json.dumps(result, indent=2, ensure_ascii=False) + '\n')
CLEAN
  printf 'separate JVM %s: OK\n' "$phase"
done
test_cp="$run_cp:$kotlin_lib/kotlin-test.jar:$kotlin_lib/kotlin-test-junit.jar:$(jar junit junit 4.13.2):$(jar org.hamcrest hamcrest-core 1.3)"
"$compiler" -jvm-target 25 -Xfriend-paths="$output_dir/storage.jar" \
  -classpath "$test_cp" -d "$output_dir/storage-tests.jar" \
  src/jvmTest/kotlin/borg/trikeshed/narsese/DocumentPersistenceTest.kt \
  > "$output_dir/storage-tests-compile.out" 2>&1
java -cp "$output_dir/storage-tests.jar:$test_cp" org.junit.runner.JUnitCore \
  borg.trikeshed.narsese.DocumentPersistenceTest > "$output_dir/storage-tests.out" 2>&1
cat "$output_dir/storage-tests.out"
cat "$output_dir/io-source.sha256" "$output_dir/index/source.sha256" "$output_dir/storage-source.sha256" > "$output_dir/source.sha256"
printf 'Artifacts: %s\n' "$output_dir"
