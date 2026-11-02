Bounded synthetic structured-premise benchmark — actual native execution
2026-11-02. No main-source edits, Gradle, model calls or live NARS bag access/mutation.

Question tested
Can an explicitly described structure selector retain premises needed for a held-out positive
Horn class/role consequence in a small distractor corpus? This is a benchmark selector, NOT
actual NARS recall, learned semantic quality or a generally complete goal-directed solver.

Fixture and baseline
100distractors: (mentions NoteNNN eligibleFor Alice ProjectA).
7meaningful source records: Employee subclass Person; Alice instance Employee; ProjectA instance
Project; assignedTo(Alice,ProjectA); authorizedFor(Alice,ProjectA); an explicit instance/subclass
propagation rule; a rule requiring both class constraints and both ordered role constraints to
derive eligibleFor(Alice,ProjectA). The exact goal fact is absent from every input.
The lexical baseline scores number of DISTINCT exact lowercased goal symbols found in source
text, breaking ties by original corpus order; retains top16source records. Distractors are
intentionally lexically confusable and outrank necessary rules. It is a simple stated comparator,
not a calibrated retrieval system or a fair estimate of every NARS recall policy.
The structure selector builds predicate and consequent-head indexes in one linear pass, then
expands argument-compatible rule dependencies from the goal, propagating bound constants.
It conservatively selects matching facts/rules, never asserting a derived fact itself. At most
64expanded patterns and16selected records. This experiment uses10patterns and7selected rows;
index construction scans all107inputs, and18indexed candidates are inspected afterward.
The measured workset reduction is not a solver-internal operation-count or runtime speedup claim.
All selection happens BEFORE native evaluation, not by reading a completed proof afterward.

Actual outcomes
Complete positive theory107source records: SUPPORTED.
Lexical top16: UNKNOWN (required fixture-source recall0/7).
Structured top16budget:7selected, SUPPORTED (fixture-source recall7/7), same status as reference.
Role-swap control changes assignment to assignedTo(ProjectA,Alice): complete UNKNOWN;
lexical UNKNOWN; structured6selected UNKNOWN.
Missing-premise control removes authorization: complete106records UNKNOWN;
lexical UNKNOWN; structured6selected UNKNOWN.
Duplicate-source control adds second record for the SAME exact Alice-instance source bytes:
complete108records SUPPORTED; lexical UNKNOWN; structured8records SUPPORTED.
Proof alternative source IDs are alice-employee and alice-employee-alternative with the SAME
sourceCid. The UNIQUE asserted-source-CID set is identical to the positive reference.
WitnessMode=any-one-asserted-source, not a joint premise or independent statistical vote.
No NARS state/evidence was loaded or changed; confidence calibration/invariance was NOT measured.
There is no claim a duplicate proof path was added: this control duplicates one assertion record.

Execution/provenance
Actual frozen Kotlin SumoNixBridge exports, actual native mlnix-opt --sumo-to-kif --kif-to-nix,
actual native mlnix run-mlir, actual native mlnix positive-Horn execution, and official Nix2.35.4
oracle comparison. Every native-lowered facts/rules value matches the exported structure;
every complete result matches official Nix JSON exactly, with executed inputCid verified.
12cases completed. Native binaries are SHA256-pinned in results.json and checked unchanged.
mlir-nix1547e4a37ea1393a7cd3e3cfe7a89aff38a36dba;
sumo-nix1e2fb138d562769d21e7f3e6cfdade9129705ec4; ontology=null;
static adapter SHA256e5c5a5df8b465dd93cdc18a4081df1aea0a6cec0049b3834cbdf5b13cfe38c3c.
Copied helper class files and scripts have frozen-helper-sha256.json; helper dependencies use
immutable v38jar/cache paths, not root live build classes. Synthetic host-explicit APPROVED
fixtures are experiment assumptions, not authenticated legal sources or production review.
Native process deadline45seconds,8GiB address-space limit; observed stdout<=1MiB.
Official oracle additionally uses clearenv/network namespace isolation. No native OS isolation
claim is made; the static positive solver uses local files and no evaluation downloads.

Replay (existing local SDK/cached dependencies)
 python3 prepare.py
 python3 compile-export.py
 python3 run.py
Read results.json/selection.json and per-phase stdout/stderr for exact commands/counts/proofs.
No model, training, NAF, quantified inference, real-world authority, or bag admission is exercised.
The entire corpus fits the existing128input bridge bound (maximum108source records).
FirstProofOnly remains true; UNKNOWN means no proof in this selected positive fragment, not
classical refutation or corpus completeness. Selection truncation would be explicit; none occurred.

Next decision
This supports a small query-local structure-aware workset prototype for this declared class/role
fragment. It does not justify changing live confidence, Mint/Reinforce/Attend policy or replacing
all bag recall/indexing. A later live attention bridge needs receipt-checked owner identity first.
