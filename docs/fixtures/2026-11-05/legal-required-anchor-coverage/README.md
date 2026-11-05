# Required annotation anchor coverage

The optional `requiredAnchors` case field adds an annotation coverage metric to
the existing `provisional-legal-eval/1` manifest. It does not change the existing
any-adequate-anchor `groundedAgreement` metric. Old manifests still load, all old
report fields keep their meaning, and score reports gain additive coverage
fields. Proposition identities exclude requirements, just as they exclude labels
and anchors. The blind `prepare` requests contain neither requirements nor gold
anchors; their proposition identities and request contents remain unchanged.

`requiredAnchors` is either absent/null or a nonempty array of at most 64 distinct
existing witness objects. Each object identifies the exact excerpt CID, half-open
UTF-16 span, quote and annotated role, and must equal a member of the case's
validated `anchors`. The excerpt CID can be omitted in the JSON declaration to
use the case's CID, as with existing anchors. Empty arrays, duplicates and
nonmembers make the fixture invalid; malformed JSON field types fail input
decoding. The field describes a conjunction: every declared anchor must appear.
It cannot express alternative paths or nonexistent premises. Do not declare
missing authorization as a source quote, or require both repeated occurrences
when either one is an adequate alternative.

A valid case with declared requirements has a `requiredAnchorCoverage` object:

| Field | Meaning |
| --- | --- |
| `requiredCount` | Size of the explicit nonempty declaration |
| `acceptedQuotes` | Null without one prediction; false for malformed, duplicate, excessive or unannotated quotes; true when every supplied quote exactly matches an annotated role/span |
| `matchedCount` | Number of required exact witnesses supplied, only when `acceptedQuotes` is true |
| `missingAnchors` | Exact required witnesses omitted, only when `acceptedQuotes` is true |
| `complete` | Whether all required anchors were supplied, only when `acceptedQuotes` is true |

The coverage object is null for undeclared requirements and invalid fixtures.
MISSING and INVALID_PREDICTION retain those row statuses and have null comparison
fields, not a fabricated false coverage result. A prediction with invalid quotes
also has null comparison fields and `acceptedQuotes:false`. Existing malformed
witness handling remains unchanged: such a unique prediction can still have the
old row status SCORED with `witnessIntegrity:false` or `acceptedWitness:false`.

Coverage is independent of the predicted label. `acceptedQuotes` concerns exact
annotated quotes; existing `acceptedWitness` also checks witness sufficiency for
the predicted label. Thus a prediction can cover all required SUPPORTS anchors
while predicting UNKNOWN: coverage is complete, but `acceptedWitness` and
`groundedAgreement` are false. An explicit UNKNOWN with no quotes covers zero
anchors if a nonempty CONTEXT requirement is declared. Without a declaration it
has no coverage metric. No missing semantic capability becomes an UNKNOWN answer.

The summary exposes both populations:

- `requiredAnchorCases`: all valid cases declaring requirements, including
  MISSING and INVALID_PREDICTION rows.
- `requiredAnchorComparableCases`: declared cases with one prediction and only
  accepted exact quotes, including a valid empty prediction witness array.
- `completeRequiredAnchorCoverageCases`: comparable cases covering every
  required anchor.
- `completeRequiredAnchorCoverageRate`: complete/comparable, null when there are
  no comparable cases. Always read this alongside the declared case count and
  existing missing/invalid totals; one comparable success among six declared
  cases is not six successes.

This companion reuses all 16 invented cases and source bytes from
`../../2026-11-04/english-symbol-corpus`. Six cases declare requirements:
S1, S2, S3, S9, C2 and C3. C1's repeated occurrence alternatives and the absent or
unsupported premise scenarios stay undeclared. The original corpus, frozen v71
reports and verification hashes are unchanged.

Use the existing CLI from the repository root with this `manifest.json` and:

- `docs/fixtures/2026-11-04/english-symbol-corpus/empty-seat-predictions.json`:
  MISSING 16; six declared cases, zero comparable; null coverage rate.
- `docs/fixtures/2026-11-04/english-symbol-corpus/controls/scorer-contract-predictions.json`:
  S2 supplies one of its two required anchors. Old grounded agreement remains
  true, required coverage is 1/2 and incomplete. S6 stays an explicit UNKNOWN
  without a coverage declaration.
- `full-anchor-predictions.json`: S2 supplies both anchors. Old grounded agreement
  remains true and required coverage is complete. Five other declared cases
  still have no prediction; the comparable denominator is one.

These are deliberately supplied annotation controls, not model predictions,
inference performance, proof certificates or training results. Even complete
anchor coverage does not verify rule application, bindings, guards, context,
current authority, independence or legal truth. Formal required-premise paths
remain in the separate original gold sidecar. `expectation.json` records expected
results only; this implementation agent did not run Gradle, builds or the changed
CLI. Root owns execution and publication evidence.
