# LCNC airgap demonstrator: baseline curation vertical slice

The retained demo brief (`docs/lcnc/airgap-demonstrator/`) at delivery levels **D0** (contract + fixture)
and **D1** (instrumented vertical slice), for the baseline curation use cases. It drives real engines,
keeps every artifact under a content identity with stage receipts and a durable journal, and runs with
network denied to its whole process tree.

The default profile runs **no generative language model**: nothing decodes text, no Jeva, no LLM. Its lanes:

| Lane | Kind | What it returns |
|---|---|---|
| `utils/stanza/run.py` | Stanza's trained tagger and parser (no sampling), hand-written frame rules, pinned SUMO | frames with typed roles |
| `utils/stanza/operators.py` | rules over that parse; loads no model | operator candidates read off parse-tree constructions, each with a deterministic and an ontological weight |
| `utils/q4bert/worker.py` | the one model: a 4-bit BERT encoder with its masked-language-model head | for each operator cue, the probability the context gives that word and that class of word at its own position |
| `ConditionalEvaluator` | deterministic | missing / affirmed / denied / conflicting evidence for one conditional, per context |

"Deterministic" below means repeatable: the same input gives the same bytes in a new process. The parser
under both Stanza lanes is a trained network and can attach a clause wrongly.

The encoder scores one masked position per question. It has no decoding loop and reports `outputTokens: 0`
on every call. Its output is evidence kept beside an operator: it never changes a weight, admits nothing
and rejects nothing. `--model none` drops it; `--model jeva` swaps in the earlier Jeva decision lane (a
quantized causal language-model backbone used as a scorer), which is outside this profile.

This is a bounded slice, not the 25-work-package programme: no visualization, no Rete compiler, no Sulong
runtime, no browser. The contracts it implements are the ones the brief requires of every later tier.

## Use cases

| UC | Name | What it shows |
|---|---|---|
| UC1 | intake-retention | Original bytes immutable under a CID; extraction a distinct identity; coverage of the selected range; authentication stays `unverified`; the range ends on a marker so no sentence is cut (I01, I02, I04) |
| UC2 | deterministic-frames | Stanza lane with the party-definition preamble; `we` bound to Coinbase; the amendment clause reads deontic permission; quotation receipts with UTF-16 offsets; candidates never auto-admitted |
| UC3 | operator-logic | Operators from constructions (`mark(if)+advcl`, `result+obl(in)`, `aux(may)+root`, ...); both weights recomputed from their recorded parts; every cue and operand compared with the retained text at its offsets; a second run in a new process gives the same bytes; reference operators found by marker |
| UC4 | masked-cue-evidence | The 4-bit BERT lane: bundle verified and pinned (checkpoint, tokenizer, sequence limit, head, quantization, normalization); every cue answered or refused with a reason; contested cues queued for review; operator bytes unchanged; over-limit input refused, not truncated, and the worker keeps serving |
| UC5 | evidence-states | F05-style authored clause: missing→pending, affirmed→eligible, denied→ineligible, conflicting→conflict; a hypothetical premise in the same list is invisible to the asserted evaluation. Then the same four states over the conditional UC3 read from the source, hypothetically, because that operator is not admitted (I03, I05) |
| UC6 | admit-revoke-replay | Explicit scoped admission of the amendment operator (found by marker); production shadow→active with an effect receipt; withdrawal suspends the generation; stale activations fenced; reuse never raises confidence (I06, I12, I13) |

Plus: a config change gives a new graph definition identity (I11); the model choice is part of the
definition; `replay` reads the journal and runs nothing.

## The two weights, and what the encoder adds

- **deterministic** = base × resolved / required roles of the construction. Base is 0.9 when a causal verb
  is the clause's own predicate, 0.8 when the cue has one standing reading (`if`, `unless`, `not`), 0.5
  when it has two (`may`, `or`, `when`). A role the tree fills by inheritance or an anaphor is unresolved.
- **ontological** = SUMO checks entailed / total for the operator's own relation on these operands, on the
  first mapped WordNet sense. `anySense` is the same ratio if another mapped sense of the same word may
  stand in. "Any attempted transactions in such items will result in loss of the item" scores 0.33 and
  1.0: the first senses (the written record of proceedings; the thing lost) are no Process, later ones are.
  "As a result of our security protocols" scores 0.33 and 0.33: no sense fits `causes`, so the operator
  needs another relation.
- **cue evidence** asks whether the context alone would have put the cue there. All four causal verbs and
  all eight conditionals in the slice are supported. Twenty-four operators are contested and queued, and
  contested does not mean wrong. "You may have to agree to additional terms": the deterministic lane reads
  a permission; the encoder puts will/would slightly ahead of may/can/might/could at that position (0.43
  against 0.40 for "may" itself), and a reader takes "may have to" as a possible requirement, not a right.
  That reading is doubtful. "You must register for a Coinbase account": the context alone would have said
  "may", the source says "must"; the reading is right and rests on that one word. Both belong in front of
  a reviewer, for different reasons.

Neither weight is a probability or a parser confidence, and none of this establishes legal force.

## Egress

`--airgap egress-blocked` (the default) is the brief's *egress-blocked runtime* profile for the run's own
process tree:

1. The harness re-executes itself under the macOS sandbox with the rule `(deny network*)`, which the
   kernel applies to it and to every descendant.
2. Before any engine starts it probes that TCP, UDP, loopback, bind and name resolution are refused. The
   first probe cannot emit traffic; the others run only after it was denied. If any probe is allowed, no
   engine starts and the run exits 2.
3. Every engine process starts behind `airgap/egress_guard.py`, which refuses and records each attempt to
   connect, bind, send or resolve (destination and calling code, never payload) and reports whether the
   kernel denial holds inside that process.
4. The report carries the probes, the process inventory, the policy text with its digest, and the ledger.

`--airgap application-local` skips step 1 and 2 and makes no airgap claim.

Neither is the physically disconnected profile. The host keeps its network; other processes are not
constrained; native code inside an engine is constrained by the kernel rule, not by the ledger. Only macOS
has a verified control here; elsewhere `egress-blocked` refuses to start rather than pretend.

## Run

Requirements, all local and already present on the reference machine:

- Stanza venv, models and SUMO: `utils/stanza/setup.sh` and `./gradlew fetchSumoCorpus fetchSumoFullCorpus`.
- An interpreter with numpy and torch for the encoder (`TRIKESHED_Q4BERT_PYTHON`, default
  `~/.local/share/TrikeShed/bert/bin/python`) and the bundle `utils/q4bert/quantize.py` makes
  (`TRIKESHED_Q4BERT_BUNDLE`, default `~/.local/share/TrikeShed/q4bert/legal-bert-base-uncased-q4g32`).
- The harness itself needs only the Python standard library (3.9 or later).

    cd utils/lcnc-demo
    python3 demo.py run                              # six use cases, network denied, 4-bit BERT lane
    python3 demo.py run --model none                 # deterministic lanes only
    python3 demo.py run --scenario uc3               # one use case and what it depends on
    python3 demo.py run --airgap application-local   # no kernel control, no airgap claim
    python3 demo.py replay <run-dir>                 # trace replay, nothing executes
    python3 -m unittest discover -s tests            # 44 contract tests, no weights

A missing bundle is an unavailable lane with its reason, and the deterministic record is still retained.
A bundle that is present but fails its own digests is a failed check. Nothing is downloaded at run time.

Runs land in `~/.local/share/TrikeShed/lcnc-demo/runs/<epoch>/`, outside the repository:

    report.json        every check with observed and expected values; egress evidence; results per use case
    articulation.txt   the slice's operator logic as text: per sentence its composed form, then each operator
                       with its construction, both weights and their parts, the cue evidence, what stays open
    journal.jsonl      the durable event history `replay` reads
    inventory.json     what the run read, with digests (not a sealed bundle)
    egress.jsonl       one line per process from its own ledger
    cas/               every retained object under its SHA-256
    stanza-out/        frames, gaps, operators, logic, masks as the lanes wrote them

Private source bytes are retained in the run CAS and never checked in; fixtures name the capture by path.

## Reference run

2026-11-19, macOS 15.8.3 arm64, one agreement, characters 3110–16975 of the capture (89 sentences):

- 199 frames; 192 operator candidates; 173 with a classed cue.
- Two runs in separate process trees gave the same frames, the same operator bytes, the same encoder
  numbers and the same articulation.
- Cue evidence: 149 supported, 24 contested, 19 without a classed cue; the cue is the top word in 140 of
  173; 7,516 input tokens, 0 output tokens, about 50 ms per cue on 4 CPU threads.
- Egress: 6 of 6 probes denied; 4 engine processes, 4 ledgers, kernel denial confirmed in each; no
  connection, send or name lookup attempted. One loopback `bind(('::1', 0))` was refused in the Stanza
  process: urllib3 testing for IPv6 when it is imported.
- 63 checks passed in about 30 seconds.

The fixture expectations (`fixtures/f01-operators.json`, `fixtures/q4bert-pin.json`) are observations
from this configuration, kept as regression pins. They are single clauses, not a benchmark, and two of
them are kept because the deterministic reading is doubtful there.

## Invariant map

I01/I02 `cas.py`, `curation.retain_source` · I03 `graph.py` contexts, `ConditionalEvaluator` contexts, UC5 ·
I04 `review.admit`, quotation receipts · I05 `ConditionalEvaluator` · I06 `review.try_activate` ·
I07 refusal receipts · I08 `journal.py` fsync before report · I11 `graph.with_config` ·
I12/I13 `review.py` generations and dependencies · I14 `airgap.py` (process-tree control with evidence;
not host isolation) · I15 separate CPU and model cost fields in receipts.

## Boundaries

- One document, one range, English. Nothing here is a measurement of accuracy: there is no labelled set.
- The operator lane takes the parse as given. A wrong attachment gives a wrong operator with a confident
  weight; the weights describe the construction and the ontology fit, not the parser.
- Stanza frames and operators are not bridged into the Kotlin `DocumentCurator`; this harness mirrors the
  Kotlin record semantics in Python. No Kotlin code loads the BERT bundle.
- Scope of every operator is a stated convention (subordinate clauses outermost), listed as unresolved.
- Synthetic material (the F05 clause, the premises, the stub engine) is labeled and never stands in for
  engine evidence.
