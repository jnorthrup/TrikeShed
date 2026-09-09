#!/usr/bin/env bash
# Current-source Node host/discovery/selector checks with a scoped algebra source projection.
set -euo pipefail
TRIKESHED_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$TRIKESHED_ROOT"
TRIKESHED_KOTLINC_JS=${TRIKESHED_KOTLINC_JS:-$(command -v kotlinc-js)}
TRIKESHED_KOTLIN_LIB=$(cd "$(dirname "$TRIKESHED_KOTLINC_JS")/../lib" && pwd)
TRIKESHED_VERIFY_OUT=${TRIKESHED_VERIFY_OUT:-$(mktemp -d "${TMPDIR:-/tmp}/trikeshed-uring-host-node.XXXXXX")}
TRIKESHED_GRADLE_CACHE=${TRIKESHED_GRADLE_CACHE:-$HOME/.gradle/caches/modules-2/files-2.1}
mkdir -p "$TRIKESHED_VERIFY_OUT"
trap 'printf "Verification artifacts: %s\n" "$TRIKESHED_VERIFY_OUT"' EXIT

dependency() {
  local group=$1 artifact=$2 version=$3 candidate
  for candidate in "$TRIKESHED_GRADLE_CACHE/$group/$artifact/$version"/*/"$artifact-$version.klib"; do
    if [[ -f "$candidate" ]]; then printf '%s' "$candidate"; return; fi
  done
  printf 'Missing cached dependency: %s:%s:%s\n' "$group" "$artifact" "$version" >&2
  return 1
}
TRIKESHED_LIBRARIES=(
  "$TRIKESHED_KOTLIN_LIB/kotlin-stdlib-js.klib"
  "$TRIKESHED_KOTLIN_LIB/kotlin-test-js.klib"
  "$(dependency org.jetbrains.kotlinx kotlinx-coroutines-core-js 1.11.0)"
  "$(dependency org.jetbrains.kotlinx kotlinx-datetime-js 0.8.0-0.6.x-compat)"
  "$(dependency org.jetbrains.kotlinx kotlinx-serialization-core-js 1.11.0)"
  "$(dependency org.jetbrains.kotlinx atomicfu-js 0.32.1)"
  "$(dependency org.jetbrains.kotlin kotlin-dom-api-compat 2.4.10)"
)
# Keep the actual Join implementation and exact Array/List conversion declarations.
# The unrelated Iterable alpha overload is excluded because it does not compile in this narrow closure.
python3 - "$TRIKESHED_VERIFY_OUT" <<'PY'
from pathlib import Path
import sys
out = Path(sys.argv[1])
join = Path('src/commonMain/kotlin/borg/trikeshed/lib/Join.kt').read_text()
start = join.index('/** Iterable projection. */')
end = join.index('// ── Left identity', start)
(out / 'JoinBoundary.kt').write_text(join[:start] + join[end:])
series = Path('src/commonMain/kotlin/borg/trikeshed/lib/Series.kt').read_text().splitlines()
declarations = [line for line in series if line.startswith(('fun <T> Array<T>.toSeries():', 'fun <T> List<T>.toSeries():'))]
assert len(declarations) == 2, 'Expected the two existing Series conversion declarations'
(out / 'SeriesBoundary.kt').write_text('package borg.trikeshed.lib\n\n' + '\n'.join(declarations) + '\n')
PY
COMMON_SOURCES=(
  "$TRIKESHED_VERIFY_OUT/JoinBoundary.kt"
  "$TRIKESHED_VERIFY_OUT/SeriesBoundary.kt"
  src/commonMain/kotlin/borg/trikeshed/lib/PrimitiveJoins.kt
  src/commonMain/kotlin/borg/trikeshed/lib/Twins.kt
  src/commonMain/kotlin/borg/trikeshed/context/BitMasked.kt
  src/commonMain/kotlin/borg/trikeshed/platform/HostDescriptor.kt
  src/commonMain/kotlin/borg/trikeshed/platform/PlatformHost.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/UserspaceIO.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/FunctionalUringFacade.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/UringOp.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/Liburing.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/FanoutEvent.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/containment/ContainmentPolicy.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/containment/SyscallGuard.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/nio/ByteBuffer.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/nio/Buffer.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/nio/ByteOrder.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/nio/platform/spi/PlatformCodec.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/nio/platform/spi/PlatformEndianness.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/nio/ebpf/UringEbpf.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/UringModuleProbe.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/UringProbeReport.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/nio/spi/NioCapabilityReport.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/nio/spi/UringCapabilityReport.kt
  src/commonMain/kotlin/borg/trikeshed/userspace/nio/spi/NioCapabilityProbe.kt
)
JS_SOURCES=(
  src/jsMain/kotlin/borg/trikeshed/platform/PlatformHost.js.kt
  src/jsMain/kotlin/borg/trikeshed/platform/HostDescriptor.js.kt
  src/jsMain/kotlin/borg/trikeshed/lib/JsNode.kt
  src/jsMain/kotlin/borg/trikeshed/userspace/UserspaceIO.js.kt
  src/jsMain/kotlin/borg/trikeshed/userspace/Liburing.js.kt
  src/jsMain/kotlin/borg/trikeshed/userspace/nio/platform/spi/PlatformEndianness.js.kt
  src/jsMain/kotlin/borg/trikeshed/userspace/NodeUringDiscovery.kt
  src/jsMain/kotlin/borg/trikeshed/userspace/nio/spi/NioCapabilityProbe.js.kt
)
TEST_SOURCES=(
  src/jsTest/kotlin/borg/trikeshed/platform/NodeHostDescriptorTest.kt
  src/jsTest/kotlin/borg/trikeshed/userspace/NodeUringDiscoveryTest.kt
)
TRIKESHED_COMMON_CSV=$(IFS=,; printf '%s' "${COMMON_SOURCES[*]}")
TRIKESHED_LIBRARY_CP=$(IFS=:; printf '%s' "${TRIKESHED_LIBRARIES[*]}")
cat > "$TRIKESHED_VERIFY_OUT/Main.kt" <<'EOF'
fun main() { println("Node host/discovery/selected-backend source checks passed; Linux module fixtures are synthetic.") }
EOF
shasum -a 256 "${COMMON_SOURCES[@]}" "${JS_SOURCES[@]}" "${TEST_SOURCES[@]}" \
  src/commonMain/kotlin/borg/trikeshed/lib/Join.kt src/commonMain/kotlin/borg/trikeshed/lib/Series.kt \
  scripts/verify-uring-host-node.sh "$TRIKESHED_VERIFY_OUT/Main.kt" \
  "${TRIKESHED_LIBRARIES[@]}" > "$TRIKESHED_VERIFY_OUT/sources.sha256"
printf '%s\n' "${TRIKESHED_LIBRARIES[@]}" > "$TRIKESHED_VERIFY_OUT/libraries.txt"
node --version > "$TRIKESHED_VERIFY_OUT/node-version.txt"
printf 'Scoped current-source Node verification; exact Join/Series source projections are hashed with their originals.\n'
"$TRIKESHED_KOTLINC_JS" -Xmulti-platform -Xexpect-actual-classes -Xcommon-sources="$TRIKESHED_COMMON_CSV" \
  -Xir-produce-klib-file -ir-output-dir "$TRIKESHED_VERIFY_OUT" -ir-output-name uring-host \
  -libraries "$TRIKESHED_LIBRARY_CP" "${COMMON_SOURCES[@]}" "${JS_SOURCES[@]}" "${TEST_SOURCES[@]}" \
  "$TRIKESHED_VERIFY_OUT/Main.kt" > "$TRIKESHED_VERIFY_OUT/compile.log" 2>&1 || {
    cat "$TRIKESHED_VERIFY_OUT/compile.log"; exit 1;
  }
for TRIKESHED_MODULE_KIND in commonjs es; do
  mkdir -p "$TRIKESHED_VERIFY_OUT/$TRIKESHED_MODULE_KIND"
  "$TRIKESHED_KOTLINC_JS" -Xir-produce-js -Xir-dce -Xinclude="$TRIKESHED_VERIFY_OUT/uring-host.klib" \
    -libraries "$TRIKESHED_LIBRARY_CP" -module-kind "$TRIKESHED_MODULE_KIND" -main call \
    -ir-output-dir "$TRIKESHED_VERIFY_OUT/$TRIKESHED_MODULE_KIND" -ir-output-name uring-host \
    > "$TRIKESHED_VERIFY_OUT/link-$TRIKESHED_MODULE_KIND.log" 2>&1 || {
      cat "$TRIKESHED_VERIFY_OUT/link-$TRIKESHED_MODULE_KIND.log"; exit 1;
    }
  TRIKESHED_EXTENSION=js
  [[ "$TRIKESHED_MODULE_KIND" != es ]] || TRIKESHED_EXTENSION=mjs
  node "$TRIKESHED_VERIFY_OUT/$TRIKESHED_MODULE_KIND/uring-host.$TRIKESHED_EXTENSION" \
    | tee "$TRIKESHED_VERIFY_OUT/run-$TRIKESHED_MODULE_KIND.log"
done
node - "$TRIKESHED_VERIFY_OUT/commonjs/uring-host.js" <<'JS' | tee "$TRIKESHED_VERIFY_OUT/run-global-fixtures.log"
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync(process.argv[2], 'utf8');
for (const runtime of ['BROWSER_JS', 'HOSTED_JS', 'JS']) {
  const lines = [];
  const sandbox = {module: {exports: {}}, console: {log: (...args) => lines.push(args.join(' '))}};
  if (runtime === 'BROWSER_JS') Object.assign(sandbox, {window: {}, document: {}, navigator: {hardwareConcurrency: 4}});
  if (runtime === 'HOSTED_JS') sandbox.Graal = {};
  if (runtime === 'JS') {
    for (const key of ['process', 'Java', 'navigator']) Object.defineProperty(sandbox, key, {get(){throw new Error('access denied');}});
  }
  vm.runInNewContext(source, sandbox, {timeout: 15000});
  const probe = lines.find(line => line.startsWith('Node uring host probe:'));
  assert.ok(probe?.includes('runtime=' + runtime), probe);
  assert.ok(probe.includes('state=RUNTIME_RESTRICTED'), probe);
  console.log('Synthetic global environment ' + runtime + ': safely classified, no native backend');
}
JS
shasum -a 256 -c "$TRIKESHED_VERIFY_OUT/sources.sha256" > "$TRIKESHED_VERIFY_OUT/sources-check.log" || {
  cat "$TRIKESHED_VERIFY_OUT/sources-check.log"; exit 1;
}
