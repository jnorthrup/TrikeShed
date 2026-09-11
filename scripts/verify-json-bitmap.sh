#!/usr/bin/env bash
# Compile the actual common source/tests without the rest of TrikeShed.
set -euo pipefail
repository=$(cd "$(dirname "$0")/.." && pwd)
cd "$repository"
output_dir=${1:-build/json-bitmap-verification}
mkdir -p "$output_dir"
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
junit=${JUNIT_JAR:-$(jar junit junit 4.13.2)}
hamcrest=${HAMCREST_JAR:-$(jar org.hamcrest hamcrest-core 1.3)}
classpath="$kotlin_lib/kotlin-stdlib.jar:$kotlin_lib/kotlin-test.jar:$kotlin_lib/kotlin-test-junit.jar:$junit:$hamcrest"
sources=(
  src/commonMain/kotlin/borg/trikeshed/parse/json/JsonBitmap.kt
  src/commonTest/kotlin/borg/trikeshed/parse/json/JsonBitmapTest.kt
)
"$compiler" -version
"$compiler" -classpath "$classpath" -d "$output_dir/tests.jar" "${sources[@]}"
java -cp "$output_dir/tests.jar:$classpath" org.junit.runner.JUnitCore \
  borg.trikeshed.parse.json.JsonBitmapTest
