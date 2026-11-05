# Required anchor coverage: frozen v73 CLI evidence

The actual frozen final-JAR run passed 166 checks over 20 CLI invocations:
16 returned JSON reports and four refused invalid inputs as expected. Every
invocation used the same immutable 120-JAR classpath. All JAR and fixture hashes
matched before and after execution.

Tested source: `08dd94f6cf88e1fb002291bcc3b2195a9f426608`.
Final `main.jar` SHA-256:
`72f74004dc435f869787d6d77e04eddbd31927065894bbe4e4f908245636a87a`.
The snapshot is
`/workspace/library-files/english-inference-coverage-v73-proof/snapshot`.
`artifact-origins.json` records every classpath path/hash, classpath order, Java
origin, source head, snapshot metadata hashes and external verifier identity.

| Supplied control | Existing grounded agreement | Required anchor coverage |
| --- | --- | --- |
| Legacy manifest, original partial control | Same fields and outcomes as frozen v71 | Undeclared; null rate |
| Extended manifest, empty prediction seat | MISSING 16, scored 0, explicit UNKNOWN 0 | Six declared, zero comparable, null rate |
| S2, one of two anchors | true | Required 2, matched 1, incomplete; exact second anchor missing |
| S2, both anchors | true | Required 2, matched 2, complete; no missing anchors |
| S6, explicit UNKNOWN without quotes | Existing agreement retained | No declaration; coverage null |
| S2, both anchors with wrong label | false | Complete, independently of label agreement |

For both partial/full extended controls, only one of the six declared cases is
comparable; the other five have no supplied prediction. The rate is 0/1 or 1/1,
respectively. It is not a six-case success rate. Legacy blind requests match the
frozen v71 report exactly; extended blind requests have the same source bytes,
propositions and identities. No gold requirements leak into requests. Source
bytes, 30 UTF-16 anchor spans and all 16 proposition identities were independently
checked, including canonical CBOR identity reconstruction.

Externally authored negative controls remain separate from production fixtures:

- Malformed quotes, wrong roles, duplicate quotes and spurious exact-quote
  padding cannot enter the coverage denominator. `acceptedQuotes` is false and
  matched/missing/complete comparison fields are null.
- An UNKNOWN prediction with an empty quote array covers zero of two declared
  requirements; it does not gain vacuous completeness.
- Duplicate predictions retain INVALID_PREDICTION and null comparison fields.
- Empty, duplicate or wrong-role requirement lists produce INVALID_FIXTURE and
  refuse blind preparation. A requirement of the wrong JSON type refuses input.

These are manually supplied annotation controls, not outputs from a predictor,
trainer, belief bag or reasoner. Complete anchor coverage does not validate rule
application, bindings, guards, contexts, current authority, independence, formal
proof paths or legal truth. S6's explicit UNKNOWN does not establish understanding
of authorization. Missing semantic predictions stay MISSING.

The records are compact archives of the actual attempt:

- `cli-records.jsonl`: all commands, exit codes, exact UTF-8 stdout/stderr and
  their hashes. Empty stdout on the four refusals is preserved.
- `mutation-inputs.jsonl`: all 11 distinct external mutated input files, exact
  UTF-8 contents, original paths and hashes.
- `verification.json`: all 166 checks and references/hashes for the raw attempt.
- `fixture-origins.json`: unmodified source and fixture origins/hashes.
- `verifier-reference.json`: external verifier SHA-256 and replay invocation.
  The verifier remains outside Git under PRELOAD's no-scripts rule. Reconstruct
  commands using its documented classpath and argument encoding.
- `build-provenance.json`: root's build command, artifact hashes, selected 426
  JVM tests with zero failures/errors/skips, all JVM test sources compiled, and
  all three browser builds passed. This evidence agent ran no builds.

The first root build compiled main and the three browser bundles but failed to
compile the CLI test's untyped `emptyList()` assertion. No tests ran in that
attempt. Root retained the failure log and committed the test-only explicit
element type in `08dd94f6c`; the subsequent run passed. The failed attempt and
both log hashes remain recorded; no production change was made to force success.

The complete raw attempt and executable verifier are retained at
`/workspace/library-files/required-anchor-coverage-v73-proof/attempt-20261105T003944.144996Z`
and `/workspace/library-files/required-anchor-coverage-v73-proof/verify.py`.
Original frozen v71 reports and corpus bytes remain unchanged. This commit adds
documentation and recorded evidence only, from the tested source base.
