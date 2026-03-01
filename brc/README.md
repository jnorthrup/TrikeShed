# 1BRC Harness — TrikeShed Variants

Benchmark harness for the [One Billion Row Challenge](https://github.com/gunnarmorling/1brc),
implemented as multiple TrikeShed variants showcasing different compositions of
Series, Cursor, FileBuffer, and coroutine patterns.

## Variants

| Script | Class | Strategy |
|---|---|---|
| `calculate_average_baseline.sh` | `BrcBaseline` | Naive `BufferedReader` + `HashMap` (Java-style baseline) |
| `calculate_average_cursor.sh` | `BrcCursor` | Cursor-composition via `j`/`α` operators, ISAM roundtrip |
| `calculate_average_mmap.sh` | `BrcMmap` | `FileBuffer` mmap + direct byte scanning, no allocation |
| `calculate_average_parallel.sh` | `BrcParallel` | Chunked mmap + coroutine fan-out, merge reduce |
| `calculate_average_fixedpoint.sh` | `BrcFixedPoint` | Integer-only fixed-point (×10), custom open-addressing hashmap |

## Quick Start

```bash
# 1. Generate test data (100K rows for validation, 1B for benchmark)
./brc/create_measurements.sh 100000          # small
./brc/create_measurements.sh 1000000000      # full

# 2. Validate all variants produce correct output
./brc/test.sh

# 3. Run the comprehensive test harness (7 data sets × 5 variants)
./brc/harness.sh

# 4. Test a gunnarmorling/1brc fork
./brc/test_fork.sh <github_user>              # clone & test
./brc/test_fork.sh --local ~/forks/1brc thomaswue  # test local clone

# 5. Run benchmarks (requires hyperfine)
./brc/evaluate.sh

# 6. Run a single variant
./brc/calculate_average_mmap.sh
```

## Test Harness

### Shell Harness (`harness.sh`)

Comprehensive correctness + format test suite for any `calculate_average_*.sh` script.
Tests 7 data sets per variant: canonical, all-negative, boundary, single-row,
unicode station names, constant value, and 200-station random data.

```bash
# Test all TrikeShed variants
./brc/harness.sh

# Test specific variants
./brc/harness.sh baseline mmap fixedpoint

# Test an external 1brc fork directory
./brc/harness.sh --fork /path/to/fork

# Test a single script
./brc/harness.sh --script /path/to/calculate_average_foo.sh

# Options
BRC_VERBOSE=1 ./brc/harness.sh       # show diff on failure
BRC_SKIP_BUILD=1 ./brc/harness.sh    # skip Gradle build
BRC_TIMEOUT=60 ./brc/harness.sh      # custom timeout per run
```

### Fork Tester (`test_fork.sh`)

Clone and test any gunnarmorling/1brc fork by GitHub username:

```bash
./brc/test_fork.sh gunnarmorling           # test original baseline
./brc/test_fork.sh thomaswue               # test thomaswue's fork
./brc/test_fork.sh --local ~/forks/1brc royvanrijn
BRC_BENCHMARK=1 ./brc/test_fork.sh thomaswue   # also benchmark
```

### JUnit Harness (`BrcHarnessTest.kt`)

Programmatic test suite at `src/jvmTest/kotlin/borg/trikeshed/brc/BrcHarnessTest.kt`.
Runs all 5 variants through 10 test cases covering:

- Canonical 1BRC sample data
- All-negative temperatures
- Boundary values (±99.9)
- Single row / single station
- Unicode station names (Москва, 日本橋, São Paulo, ...)
- Rounding semantics (IEEE 754 roundTowardPositive)
- Output format compliance (sorted, `{Station=min/mean/max, ...}`)
- Cross-variant consistency (10K random rows, all variants agree)
- 500 unique station names

```bash
./gradlew jvmTest --tests "borg.trikeshed.brc.BrcHarnessTest"
```

### Test Data Sets

| File | Description |
|---|---|
| `data/measurements_test.txt` | Canonical 1BRC sample (21 rows, 9 stations) |
| `data/measurements_negative.txt` | All negative temperatures |
| `data/measurements_boundary.txt` | ±99.9 and 0.0 boundary values |
| `data/measurements_single.txt` | Single station, single row |
| `data/measurements_unicode.txt` | UTF-8 station names with accents, CJK |
| `data/expected_*.txt` | Corresponding expected outputs |

## Data File

By default all scripts use `./measurements.txt` in the project root.
Override with env var: `BRC_FILE=/path/to/measurements.txt ./brc/evaluate.sh`

## Fork Compatibility

The harness follows the same shell-script conventions as the original
[gunnarmorling/1brc](https://github.com/gunnarmorling/1brc):

- Each variant has a `calculate_average_<name>.sh` that emits _only_ the result
  line to stdout.
- Optional `prepare_<name>.sh` for compilation steps.
- `evaluate.sh` uses `hyperfine` to time all variants.
- `test.sh` diffs each variant's output against a reference.

## Building

The variants live under `src/jvmMain/kotlin/borg/trikeshed/brc/` and are built
as part of the normal Gradle JVM compilation:

```bash
./gradlew jvmJar
```
