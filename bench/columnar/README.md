# Cursor assimilation benchmark

From the TrikeShed checkout:

```sh
./gradlew -p bench/columnar jvmTest bench --console=plain
```

Optional properties: `-Prows=65536 -Pwarmup=3 -Piterations=7 -Presults=results/cursor.json`.
Requires the repository's JDK 25 toolchain. The standalone build compiles selected **actual production source files**, with the same Kotlin version as the root. It does not load a Columnar checkout or copy the kernel. Initial root compilation failures are recorded separately and have been repaired; root JVM publication and the selected integration tests now pass. This slice proves only the selected Cursor/library functions.

The deterministic three-column Cursor has 64 group keys, four axis categories and amounts derived from integer arithmetic divided by 64. Each iteration performs sparse pivot, grouping and full reduction materialization. Every one of 256 resulting cells must exactly equal an independently computed integer reference. Values and metadata also pass through name projection. Construction of the lazy Cursor and the independent reference are outside the measured interval; lazy value evaluation, key indexing, shape checks and full result consumption are inside. This is a synthetic workload, not an allocation-free claim or application/product benchmark.

`results/cursor.json` contains all measured samples and the actual machine/JVM facts. These short warmups establish an executable baseline, not a stable cross-machine ranking. No historical Columnar timing is claimed: no comparable historical measurement was reconstructed.

Recorded run: 12 Cursor behavior tests and three iterable projection tests passed. The 65,536-row default run verified all 256 cells (checksum `0.578125`); its seven raw intervals and the measured median are preserved in the JSON report.

For the real file-backed write/append/read/query benchmark and selectable trace sleeve, see [io/README.md](io/README.md). It uses the full root JVM jar and follows the actual ISAM metadata/data/STATX facade path.
