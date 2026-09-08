#!/usr/bin/env bash
# Current-source IO overlay verification while unrelated repository migrations block the full build.
set -euo pipefail
TRIKESHED_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$TRIKESHED_ROOT"
TRIKESHED_KOTLINC=${TRIKESHED_KOTLINC:-$(command -v kotlinc)}
TRIKESHED_KOTLIN_LIB=$(cd "$(dirname "$TRIKESHED_KOTLINC")/../lib" && pwd)
TRIKESHED_SUPPORT_JAR=${TRIKESHED_SUPPORT_JAR:-$TRIKESHED_ROOT/build/libs/TrikeShed-jvm-0.1.0-SNAPSHOT.jar}
TRIKESHED_VERIFY_OUT=${TRIKESHED_VERIFY_OUT:-$(mktemp -d "${TMPDIR:-/tmp}/trikeshed-uring-conformance.XXXXXX")}
TRIKESHED_GRADLE_CACHE=${TRIKESHED_GRADLE_CACHE:-$HOME/.gradle/caches/modules-2/files-2.1}
mkdir -p "$TRIKESHED_VERIFY_OUT"
trap 'printf "Verification artifacts: %s\n" "$TRIKESHED_VERIFY_OUT"' EXIT

dependency() {
  local group=$1 artifact=$2 version=$3
  local candidate
  for candidate in "$TRIKESHED_GRADLE_CACHE/$group/$artifact/$version"/*/"$artifact-$version.jar"; do
    if [[ -f "$candidate" ]]; then printf '%s' "$candidate"; return; fi
  done
  printf 'Missing cached dependency: %s:%s:%s\n' "$group" "$artifact" "$version" >&2
  return 1
}
TRIKESHED_COROUTINES=$(dependency org.jetbrains.kotlinx kotlinx-coroutines-core-jvm 1.11.0)
TRIKESHED_COROUTINES_TEST=$(dependency org.jetbrains.kotlinx kotlinx-coroutines-test-jvm 1.11.0)
TRIKESHED_SERIALIZATION=$(dependency org.jetbrains.kotlinx kotlinx-serialization-core-jvm 1.9.0)
TRIKESHED_JUNIT=$(dependency junit junit 4.13.2)
TRIKESHED_HAMCREST=$(dependency org.hamcrest hamcrest-core 1.3)
[[ -f "$TRIKESHED_SUPPORT_JAR" ]] || { printf 'Set TRIKESHED_SUPPORT_JAR to an existing support build.\n' >&2; exit 2; }

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
  src/commonMain/kotlin/borg/trikeshed/userspace/nio/channels/Pipe.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/nio/channels/UringPipe.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/nio/channels/spi/SelectorProvider.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/nio/channels/spi/ChannelOperations.kt
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
  src/jvmMain/kotlin/borg/trikeshed/userspace/nio/channels/spi/JvmChannelOperations.kt
)
TEST_SOURCES=(
  src/commonTest/kotlin/borg/trikeshed/userspace/FunctionalUringFacadeTest.kt
  src/commonTest/kotlin/borg/trikeshed/userspace/FunctionalUringFacadeXattrTest.kt
  src/commonTest/kotlin/borg/trikeshed/userspace/UringEbpfFacadeTest.kt
  src/commonTest/kotlin/borg/trikeshed/userspace/UringFileConformance.kt
  src/commonTest/kotlin/borg/trikeshed/userspace/UringLifecycleTest.kt
  src/commonTest/kotlin/borg/trikeshed/userspace/UringPipeTest.kt
  src/jvmTest/kotlin/borg/trikeshed/userspace/UringConformanceTest.kt
  src/jvmTest/kotlin/borg/trikeshed/userspace/UringPipeRaceTest.kt
  src/jvmTest/kotlin/borg/trikeshed/userspace/JvmChannelHandleConformanceTest.kt
  src/jvmTest/kotlin/borg/trikeshed/userspace/UringDocumentInputConformanceTest.kt
)
TRIKESHED_COMPILE_CP="$TRIKESHED_SUPPORT_JAR:$TRIKESHED_COROUTINES:$TRIKESHED_SERIALIZATION"
TRIKESHED_RUNTIME_CP="$TRIKESHED_VERIFY_OUT/uring-prod.jar:$TRIKESHED_COMPILE_CP:$TRIKESHED_KOTLIN_LIB/kotlin-stdlib.jar"
TRIKESHED_TEST_CP="$TRIKESHED_RUNTIME_CP:$TRIKESHED_COROUTINES_TEST:$TRIKESHED_KOTLIN_LIB/kotlin-test.jar:$TRIKESHED_KOTLIN_LIB/kotlin-test-junit.jar:$TRIKESHED_JUNIT:$TRIKESHED_HAMCREST"
TRIKESHED_COMMON_CSV=$(IFS=,; printf '%s' "${COMMON_SOURCES[*]}")
shasum -a 256 "${COMMON_SOURCES[@]}" "${JVM_SOURCES[@]}" "${TEST_SOURCES[@]}" "$TRIKESHED_SUPPORT_JAR" > "$TRIKESHED_VERIFY_OUT/sources.sha256"
printf '%s\n' "$TRIKESHED_TEST_CP" > "$TRIKESHED_VERIFY_OUT/classpath.txt"
printf 'Scoped current-source IO verification; support artifact: %s\n' "$TRIKESHED_SUPPORT_JAR"
"$TRIKESHED_KOTLINC" -jvm-target 25 -Xmulti-platform -Xcommon-sources="$TRIKESHED_COMMON_CSV" \
  -classpath "$TRIKESHED_COMPILE_CP" -d "$TRIKESHED_VERIFY_OUT/uring-prod.jar" \
  "${COMMON_SOURCES[@]}" "${JVM_SOURCES[@]}" > "$TRIKESHED_VERIFY_OUT/compile-prod.log" 2>&1
"$TRIKESHED_KOTLINC" -jvm-target 25 -Xfriend-paths="$TRIKESHED_VERIFY_OUT/uring-prod.jar" \
  -classpath "$TRIKESHED_TEST_CP" -d "$TRIKESHED_VERIFY_OUT/uring-tests.jar" \
  "${TEST_SOURCES[@]}" > "$TRIKESHED_VERIFY_OUT/compile-tests.log" 2>&1
java -Dkotlinx.coroutines.test.default_timeout=10s -cp "$TRIKESHED_VERIFY_OUT/uring-tests.jar:$TRIKESHED_TEST_CP" org.junit.runner.JUnitCore \
  borg.trikeshed.userspace.FunctionalUringFacadeTest \
  borg.trikeshed.userspace.FunctionalUringFacadeXattrTest \
  borg.trikeshed.userspace.UringEbpfFacadeTest \
  borg.trikeshed.userspace.UringLifecycleTest \
  borg.trikeshed.userspace.UringPipeTest \
  borg.trikeshed.userspace.UringConformanceTest \
  borg.trikeshed.userspace.UringPipeRaceTest \
  borg.trikeshed.userspace.JvmChannelHandleConformanceTest \
  borg.trikeshed.userspace.UringDocumentInputConformanceTest | tee "$TRIKESHED_VERIFY_OUT/conformance.log"
