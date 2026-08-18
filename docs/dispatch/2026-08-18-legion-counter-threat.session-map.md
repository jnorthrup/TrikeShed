# Legion Counter-Threat Dispatch — 2026-08-18

Task file: `2026-08-18-legion-counter-threat.tasks.txt` (14 tasks, X2–X15)
Repo: jnorthrup/TrikeShed. Coordinated bugfix (this session): 542e3cae2 empty-whitelist
execve denial + 35b787ea5 build unbreak + d107ab682 ProcessWorker basename matching.

| #  | Task (short)                                    | Session ID            | Status at dispatch |
|----|-------------------------------------------------|-----------------------|--------------------|
| X2 | PatchAstLinter → drain branch arms              | 8243801156456693509   | Planning           |
| X3 | EntropyPathScanner → CAS-fallback arms          | 11004649906839023691  | Planning           |
| X4 | Behavioral audit → ConfixBlackboard WAL         | 3927598587916435246   | Planning           |
| X5 | Bounded noise injection → PijulChannel          | 15136221661295257931  | Planning           |
| X6 | DeterministicFormatter gate (new commonMain)    | 12529349414495707220  | Layer 4            |
| X7 | DeceptionPatternDetector → pre-MergeReceipt     | 363971734626386819    | Planning           |
| X8 | StigmergicProtocolDecoder → preflightPijulBatch | 11600634411112371934  | Planning           |
| X9 | stripAuthorMetadata → CAS-fallback arms         | 11535798985062104545  | Planning           |
| X10| Red-Green tests Layer 4 drain gate              | 16838520075250860708  | Planning           |
| X11| Red-Green tests Layer 5 drain detectors         | 3686455973728984503   | Planning           |
| X12| eBPF kprobe → daemon startup                    | 11380832347750396798  | Planning           |
| X13| FUSE content-hash namespace masking             | 15378520016874031539  | Planning           |
| X14| CollusionDetector drain tests (wave 2)          | 3932338514890529272   | dispatched 2026-08-18 |
| X15| Deterministic clock all stat ops (wave 2)       | 2250640878427217467   | dispatched 2026-08-18 |
| X16| eBPF-JIT → Truffle pointcuts, polyglot contain. | 1517397739843069751  | Planning (user-added) |

Disjoint file ownership enforced across all briefs (FlywheelDriver regions,
PijulChannel, new commonMain/test files, OroborosDaemon, FusePathCanonicalizer,
FunctionalUringFacade, pointcut/**). X12 owns OroborosDaemon.kt; X16 owns
pointcut/** + new ebpf adapter — no overlap.

External session consuming 1 slot at dispatch time: Bolt 3852178755134423116.
Cap 14/14 reached → X14/X15 deferred to wave 2 when a session completes.
