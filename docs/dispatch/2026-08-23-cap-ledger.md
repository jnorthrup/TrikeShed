# Cap ledger — 2026-08-23

Index of every reconciliation file under `docs/dispatch/`. A track is "capped" when every row of its
dispatch has exactly one disposition and the file ends `INCOMPLETE: none.` Nothing below is re-dispatched.

| date | track | file | rows | disposition summary |
|---|---|---|---|---|
| 2026-08-18 | Jules drain (post-daemon obsoletion) | `2026-08-18-drain-reconciliation.md` | 30 | 24 LANDED (branch merges + CAS patches), 3 SETTLE-REJECT, 1 FAILED, 1 PAUSED, X8 landed post-drain |
| 2026-08-22 | N-way funnel merge drain | `2026-08-22-funnel-merge-reconciliation.md` | 51→0 arms | every arm LANDED / already-present / ledger-union / build-gate reject; 0 unmerged branches, 0 undrained CAS cards |
| 2026-08-17 | Crypto & format taxonomy (root dispatch, absorbed) | `2026-08-17-crypto-format-taxonomy-reconciliation.md` | 2 | 1 LANDED (`taxonomy/CryptoAndFormatTaxonomy.kt`; JS-ratchet chokepoint noted), 1 SETTLE-REJECT probe |
| 2026-08-18 | Legion counter-threat X14/X15 (wave 2) | `2026-08-18-legion-x14-x15-reconciliation.md` | 2 | 2 DEFERRED→soak (detector test not drain-wired; uring quantization tests hidden) |
| 2026-08-22 | Tasks 21–27 pointcut/hotswap | `2026-08-22-tasks21-27-reconciliation.md` | 7 | 4 LANDED (hotswapFeed, HotSwapAgent, KataSandboxRunner, GRAAL_LLVM facet), 3 DEFERRED→vm-substrate (23/24/25) |
| 2026-08-22 | Tasks 33–42 associative engine / grid / canvas | `2026-08-22-tasks33-42-reconciliation.md` | 10 | 8 RETIRED (33–40, no owner, zero artefacts), 1 PARTIAL→RETIRED (41), 1 DEFERRED→vm-substrate (42) |
| 2026-08-22 | Heat-map soak 43–50 | `2026-08-22-heatmap-soak-reconciliation.md` | 8 | 7 DEFERRED→soak, 1 PARTIAL→soak (48, yaml round-trip only) |

Totals: 7 reconciliation files; 59 rows + 51 funnel arms; open tracks after this cap: **soak** (43–50, X14, X15)
and **vm-substrate** (23, 24, 25, 42). Neither has a dispatch file yet; they inherit from the rows above.

## Residue (Jim runs; tooling never runs git)

Verified 2026-08-23 against `ls -a` at repo root: every glob below matches at least one existing file
(`fix_auditor_leak.py fix_bb.py fix_test.py patch_kanban.py patch_main.diff patch.diff patch_file.sh
patch_test.sh replacement.py test_script.py test_compile.sh test_env_replacement.kt test_system_ops.kt
getenv_test.kt system_ops_test.kt system_properties_test.kt plan.md plan2.md..plan5.md req_plan.md
test_plan.md drain-dedupe-plan.md health_test_output.txt test_output.txt dont-redo` + the two absorbed
crypto dispatch files). Worktrees: 11 `agent-*` + `m3-surface-projection` present. The three `.rej`/`.orig`
scratch files exist. `.zenith/mailbox/jules/*.jsonl` present.

```
for w in .claude/worktrees/agent-* .claude/worktrees/m3-surface-projection; do git worktree remove --force "$w"; done; git worktree prune
git rm -q fix_*.py patch*.py patch*.diff patch*.sh replacement.py test_*.py test_*.sh test_*.kt getenv_test.kt system_*_test.kt plan*.md req_plan.md test_plan.md drain-dedupe-plan.md health_test_output.txt test_output.txt dont-redo .jules-dispatch-crypto-format-taxonomy.md .tasks-crypto-format-taxonomy.txt 2>/dev/null
rm -f src/commonMain/kotlin/borg/trikeshed/jules/JulesSessionCard.kt.rej src/jvmTest/kotlin/borg/trikeshed/jules/JulesBlackboardAdapterTest.kt.orig src/jvmMain/kotlin/borg/trikeshed/forge/donor/HermesDonorTrace.kt.orig
find .zenith/mailbox/jules -name '*.jsonl' -mtime +1 -delete
```

Not in the block (left for Jim's call, root-level but not in the agreed list): `patch_strip_test.kt`,
`test.kt`, `test-plan.md`, `nal_projection.txt`, `.tspy-rga-map.md`, `.tspy-rga-tasks.txt`, `.tasks.txt`.
