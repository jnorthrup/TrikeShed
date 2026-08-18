# Jules drain reconciliation — 2026-08-18 (post-daemon obsoletion)

Drain executed CLI-lane (daemon obsolete per operator; not restarted).
WAL was wiped 15:19 (8 bytes) — reconciliation is against git + cloud board + CAS.

## Terminal sessions (27 Completed + 1 Failed + 1 Paused)

| Session | Disposition | Evidence |
|---|---|---|
| 10373835449454975939 | LANDED | origin/bolt-zero-allocation-channelize-… merged (ancestor of master) |
| 11325156128887202285 | LANDED | origin/jules-blackboard-adapter-… merged |
| 11380832347750396798 | LANDED | CAS patch applied, commit d27b170d3 (OroborosDaemon eBPF kprobes) |
| 11953326023123964330 | LANDED | origin/palette/contextual-aria-label-board-… merged |
| 12529349414495707220 | LANDED | CAS patch applied, commit f39cf6f47 (DeterministicFormatter) |
| 13181807474373252458 | LANDED | origin/feature-directory-entropy-monitor-… merged |
| 13287738544189719497 | LANDED | origin/fix-behavioral-graph-auditor-… merged |
| 13687888226042003420 | LANDED | origin/feature-fuse-path-canonicalizer-… merged |
| 14370004043455888706 | LANDED | origin/feat-ebpf-kprobe-tracepoint-sys-enter-… merged |
| 14680760769015081375 | LANDED | origin/bolt/optimize-channelize-allocations-… merged |
| 15173564803305585324 | LANDED | origin/sentinel-fix-deception-vulnerabilities-… merged |
| 15317859954173353486 | LANDED | origin/sctp-bytebuffer-optimization-… merged |
| 15636844234403323176 | LANDED | origin/feature-resource-acquisition-detector-… merged |
| 16609143232232775395 | LANDED | CAS patch applied, commit a1ef4f138 (CryptoAndFormatTaxonomy) |
| 17487099305667126994 | LANDED | origin/palette-add-aria-label-board-new-card-… merged |
| 2274920288333098645 | LANDED | origin/sentinel-entropy-path-scanner-… merged |
| 2360358907792328148 | LANDED | origin/jules-2360358907792328148-d56dea4c merged |
| 2764812560702838562 | SETTLE-REJECT | foreign-repo delta (agent/agent_init.py); CAS sha256:90afe6866a5d40eea67ce07c06c230d2aeeed369172d48d5a08c70f71c4a4225 |
| 3396278465969982618 | SETTLE-REJECT | superseded: ConfixWal.append already in-tree (ConfixWal.kt:30, richer NIO framing); CAS sha256:61b2f31461870aaf48590c1d00d5c33d79f473d9ee03f5d0a7b2bb91b59d382a |
| 3571152037768288357 | LANDED | origin/fix-jules-author-metadata-stripping-… merged |
| 360537909355070573 | SETTLE-REJECT | superseded: richer CryptoAndFormatTaxonomy landed via 16609…; CAS sha256:ad873b9947d62b579b4f604975fe0b99fde9cf81affaa94adc8b458f201563f3 |
| 4002741279672346942 | LANDED | origin/bolt-nested-loop-allocations-… merged |
| 4131858137313685197 | LANDED | origin/feat-cross-instance-collusion-detector-… merged |
| 4613213766505760741 | LANDED | origin/layer5-stigmergic-decoder-… merged |
| 65864586523842441 | LANDED | origin/perf/optimize-keymux-daemon-providers-… merged |
| 8243801156456693509 | LANDED | CAS patch applied, commit c301cd577 (PatchAstLinter wiring) |
| 8353443862209661572 | LANDED | origin/sentinel-layer-4-ast-linter-… merged |
| 11117939812899314401 | FAILED | Bolt probe, no delta; board-terminal, not re-dispatched |
| 4305474369351412165 | PAUSED | Tree-sitter linter task; superseded by in-tree PatchAstLinter wiring (commit c301cd577); not re-dispatched |

## Two-step reject deviation (recorded, not papered over)

`JulesPatchReviewCli reject` is WAL-gated ("no causal card") and the WAL was
wiped before this drain; the daemon that maintains it is obsolete per operator.
Rejects here are recorded via CAS bytes + this ledger instead of WAL causes.
CAS bytes retained (never applied): the three sha256 CIDs above.

## Slots freed / refilled

X14 → 3932338514890529272, X15 → 2250640878427217467 dispatched after drain.
Map updated: docs/dispatch/2026-08-18-legion-counter-threat.session-map.md.

Rows: 29. Terminal sessions reconciled: 29. INCOMPLETE: none.
