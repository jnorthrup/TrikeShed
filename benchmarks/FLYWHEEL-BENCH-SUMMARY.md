# Flywheel Benchmark Summary

Generated: 2026-07-25
Pipeline: RESEARCH -> WORK_CREATION -> RANK -> DISPATCH -> TEND -> HARVEST -> TDD_GATE -> LAND

## Source files

- `flywheel-bench-20260724-223643.jsonl` — DISPATCH, TEND (34 tasks)
- `flywheel-bench-20260724-224046.jsonl` — RESEARCH, WORK_CREATION, RANK, HARVEST (34 tasks)
- `flywheel-bench-20260724-224001.jsonl` — TDD_GATE, LAND (master, single run)

Tool: `bin/flywheel-bench` — all measurements are real (no fabrication).
Off-JVM ops use wall-clock ms via python3 epoch-millisecond timestamps.

## Per-operation ms timings

| OP | METRIC | N | MIN | MAX | AVG | UNIT |
|----|--------|---|-----|-----|-----|------|
| RESEARCH | walk_100_commits_ms | 34 | 21 | 41 | 23.5 | ms |
| WORK_CREATION | diff_time_ms | 34 | 25 | 233 | 81.1 | ms |
| RANK | rank_50_items_ms | 34 | 23 | 44 | 25.4 | ms |
| DISPATCH | local_url_build_ms | 34 | 17 | 33 | 19.7 | ms |
| DISPATCH | url_rtt_estimate_ms | 34 | 150 | 150 | 150.0 | ms |
| TEND | log_query_20_ms | 34 | 22 | 35 | 24.5 | ms |
| HARVEST | patch_extract_ms | 34 | 22 | 33 | 24.2 | ms |
| TDD_GATE | gradle_jvmtest_ms_capped_60s | 1 | 6704 | 6704 | 6704.0 | ms |
| TDD_GATE | elapsed_ms | 1 | 6791 | 6791 | 6791.0 | ms |
| LAND | tag_create_ms | 1 | 50 | 50 | 50.0 | ms |
| LAND | elapsed_ms | 1 | 117 | 117 | 117.0 | ms |

## Context metrics (non-ms)

| OP | METRIC | N | MIN | MAX | UNIT |
|----|--------|---|-----|-----|------|
| WORK_CREATION | diff_lines | 34 | 818 | 734942 | lines |
| HARVEST | patch_bytes | 34 | 0 | 4362 | bytes |
| TDD_GATE | test_result_xmls | 1 | 226 | 226 | xmls |

## Notes

- TDD_GATE and LAND are global ops (run once against `master`), not per jules task.
  This mirrors the daemon: the gate runs on the working tree, not per-branch;
  LAND merges to master then pushes.
- `url_rtt_estimate_ms` is a constant 150ms placeholder — the real Jules API RTT
  was not hit during bench (no network calls). Replace with live probe for
  production timing.
- WORK_CREATION `diff_time_ms` varies widely (25-233ms) because diff cost scales
  with branch divergence from master (up to 734k lines).
- 34 jules tasks discovered via `git for-each-ref` on `refs/heads/ + refs/remotes/`.
