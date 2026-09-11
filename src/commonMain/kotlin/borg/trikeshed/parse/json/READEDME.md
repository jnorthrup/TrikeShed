# JSON structural bitmap

`JsonBitmap` is a scalar, two-stage lexical index over UTF-8 bytes. It does
not parse values or validate JSON/UTF-8. `JsonParser` provides character
indexing/reification; Confix's `Syntax.JSON.scan0` produces byte spans and a
`Cursor` with metadata. Neither currently consumes `JsonBitmap`. The bitmap
repair does not change their behavior or claim a parser speedup.

## Representation

- `encode(bytes)` allocates `ceil(bytes.size / 2)` bytes. Each input byte has
  a four-bit classification: structural event in bits 0–1 and lexer event
  in bits 2–3. Earlier input occupies the high nibble.
- Structural ordinals are `0` unchanged, `1` object/array open, `2`
  object/array close, `3` comma. Colons are not indexed.
- `decode(chunks, originalByteCount)` compacts classifications in place,
  masking events inside strings with correct backslash parity. Its result
  is the same arrays: four events per byte, most significant slot first,
  in their concatenated prefix. Unused bits and bytes are zero.
- Quote/escape state crosses all chunk boundaries, including empty chunks.
  Chunk arrays must have disjoint storage. Split the **encoded byte stream**;
  independently encoding odd-sized source chunks introduces interior padding
  that this API cannot distinguish from data.
- Retain the original byte count for odd inputs. The default count treats
  every nibble as input, including a possible zero padding nibble. Counts
  exceeding capacity are rejected before mutation.
- Two-bit output occupies `ceil(n / 4)` logical bytes; the original
  `ceil(n / 2)` allocation is retained. This is layout arithmetic, not a
  measured heap or throughput result.

```kotlin
val source = "[1,2]".encodeToByteArray().asUByteArray()
val chunks = arrayOf(JsonBitmap.encode(source))
JsonBitmap.decode(chunks, source.size.toUInt())
// Logical events: [1, 0, 3, 0, 2]; bytes: [0x4c, 0x80, 0x00].
```

UTF-8 bytes above ASCII never equal a quote, backslash or delimiter. They
need no continuation counter for lexical masking. Invalid escapes,
unbalanced containers and unterminated strings are not diagnosed here;
unterminated strings mask the remaining stream. This two-bit representation
cannot reconstruct the source or distinguish braces from brackets.

## Historical assimilation, 2026-09-11

Reviewed Jim Northrup's
[`simdjsonbitmapcodec` source at c3c90fb](https://github.com/jnorthrup/simdjsonbitmapcodec/blob/c3c90fb07ca9e4c8b4fc3c62688ffc1cad4718c3/src/main/commonMain/jsonbitmapcodec.kt)
and its repository history via `gh`. That is the sole commit, dated
2023-03-30. The tree has no tests or license file. No upstream source or
license declaration was copied; this change repairs TrikeShed's existing
implementation under its existing repository license, retaining this
algorithmic provenance. All new tests are locally authored.

The historical contribution is a compact structural event stream with
quote/escape state intended to survive chunk boundaries. The exact routine
is not working SIMD code: executing it with Kotlin against all 256 byte
values leaves a sentinel bitmap unchanged. Its class intermediates are
nonnegative and at most 256, so `ushr 31` always yields zero. Its quote mask
changes state for `0xdd`, not ASCII quote; escape state does not suppress
quote changes. Close and comma share a code, writes use XOR, and there is
no destination offset for appending arbitrary chunks. No timing or SIMD
claim is supported by that repository.

TrikeShed already contained the useful two-stage design, but its encoder
allocated twice the required size and its decoder had overlapping ordinal
bit tests, an eight-bit first write shift, and incorrect empty/chunk/count
bounds. The repair keeps the existing enum/API taxonomy, fixes these
behaviors, and captures input nibbles before writing compacted output.
It introduces no legacy wrapper or external-checkout dependency.

`CsvBitmap` imports the unchanged lexer enum API but retains its separate
legacy decoder. `Codec.kt`'s frequency coder and `JsonIndex.kt`'s offset
experiment are unrelated sketches and are outside this repair. No Cursor,
Confix, platform I/O or build configuration files are changed.

## Verification

Run the repository test when the full project compiles:

```sh
./gradlew jvmTest --tests 'borg.trikeshed.parse.json.JsonBitmapTest'
```

For a self-contained source slice with the installed Kotlin compiler:

```sh
scripts/verify-json-bitmap.sh
```

The script compiles the actual production and common test files with
`kotlin-test` and JUnit 4.13.2. It uses cached Gradle JUnit/Hamcrest artifacts;
`KOTLINC`, `JUNIT_JAR`, and `HAMCREST_JAR` can select explicit installations.
It does not use a prebuilt TrikeShed jar or a legacy checkout. This is a
focused correctness check, not evidence of a clean whole-project build.

The same common sources can be checked directly on JS/Node and Native. With
Kotlin 2.4.20's command-line tools selected (`KONANC` is the path to the
matching Native compiler):

```sh
bitmap_out=$(mktemp -d)
bitmap_lib=$(cd "$(dirname "$(command -v kotlinc-js)")/../lib" && pwd)
bitmap_sources=(
  src/commonMain/kotlin/borg/trikeshed/parse/json/JsonBitmap.kt
  src/commonTest/kotlin/borg/trikeshed/parse/json/JsonBitmapTest.kt
)
bitmap_js_libs="$bitmap_lib/kotlin-stdlib-js.klib:$bitmap_lib/kotlin-test-js.klib"
kotlinc-js -ir-output-dir "$bitmap_out/klib" -ir-output-name json-bitmap \
  -libraries "$bitmap_js_libs" "${bitmap_sources[@]}"
kotlinc-js -Xir-produce-js -Xinclude="$bitmap_out/klib/json-bitmap.klib" \
  -ir-output-dir "$bitmap_out/js" -ir-output-name json-bitmap \
  -libraries "$bitmap_js_libs" -module-kind commonjs
node "$bitmap_out/js/json-bitmap.js"
"$KONANC" -target macos_arm64 -generate-test-runner "${bitmap_sources[@]}" \
  -o "$bitmap_out/json-bitmap-native"
"$bitmap_out/json-bitmap-native.kexe"
```

JS automatically runs the registered tests; its bare adapter exits on a
failure and is silent on success. Native's generated runner prints results.
Use the appropriate Native target when checking on a different host.

Recorded on 2026-09-11: all 10 tests pass with Kotlin 2.4.20 on JVM
(GraalVM CE 25.3.4.1 / JDK 25.0.4.1), JS (Node 26.7.0), and macOS Native
(`macos_arm64`, macOS 15.7.9). The suite exercises all 256 byte categories,
packing boundaries through 33 source bytes, empty/truncated inputs, every
encoded-byte split of the fixtures, backslash runs, two/three/four-byte
UTF-8, and generated JSON whose expected events are recorded during grammar
emission. All 10 tests fail against the original `JsonBitmap.kt` from
TrikeShed base `534fc49a3`, compiled with its actual `CZero.kt` dependency.

The full `./gradlew jvmTest --tests 'borg.trikeshed.parse.json.*'` attempt
fails in `compileKotlinJvm` before tests: existing duplicate `UringChannel`
and `UringChannels` declarations, plus unrelated Kanban/document-curation
errors. These files are unchanged here. Linux and Wasm were not executed.
No benchmark was added and no runtime speedup is claimed.
