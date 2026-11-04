# Proposed wire contracts and worked records

## Status and encoding

These are proposed boundary shapes for WP02, not executable current API payloads. Names introduce explicit taxonomy for discussion and must be reconciled with existing TrikeShed types. Examples use readable placeholder IDs; production records use validated content identities and canonical encoding. JSON is a documentation view; canonical retained encoding must be versioned and deterministic.

Reject unknown required schema versions, non-finite numbers, unbounded nesting, oversized strings and unresolved artifact references. Numeric local IDs are meaningful only with their dictionary/ontology epoch. Keep payloads immutable after admission.

## Source artifact

```json
{
  "schema": "source-artifact/1",
  "artifactId": "example-original",
  "kind": "html-capture",
  "originalCid": "<verified-original-cid>",
  "byteLength": 120000,
  "mediaType": "text/html",
  "acquiredAt": "2026-11-04T16:00:00Z",
  "declaredEffectiveAt": null,
  "sourceRevision": "capture-1",
  "derivatives": ["<safe-view-cid>", "<text-cid>"],
  "coverage": {"complete": false, "omittedResources": 2},
  "authority": "unassessed"
}
```

The displayed authority field is descriptive. It cannot replace a host-issued review capability. Acquisition and effective times differ. A derivative's own record names transformation policy and input CID.

## Anchored observation

```json
{
  "schema": "anchored-observation/1",
  "originalCid": "<original>",
  "textCid": "<text>",
  "sourceRevision": "capture-1",
  "coordinate": "utf16-half-open",
  "begin": 100,
  "end": 127,
  "quote": "<exact 27 UTF-16-unit span>",
  "blockId": "clause-7",
  "kind": "modal-marker",
  "extractorCid": "<parser-and-config>",
  "semanticStatus": "candidate"
}
```

Validate range length, scalar boundaries and quote equality against the retained text. Do not use the example quotation as a real fixture. For dependency nodes, retain sentence, token index and copy count separately from the source-word occurrence. A copied grammatical node may share one source anchor.

## Scenario frame

```json
{
  "schema": "analysis-frame/1",
  "frameCid": "<canonical-frame>",
  "parentFrameCid": null,
  "datasetCids": ["<selected-source-graph>"],
  "questionCid": "<question>",
  "assumptions": [
    {"propositionCid": "<p>", "polarity": "positive", "basis": "hypothetical"}
  ],
  "ontologyCid": "<ontology>",
  "mappingPolicyCid": "<mapping>",
  "reviewSnapshotCid": "<review>",
  "analysisCutoff": "2026-11-04T16:00:00Z",
  "bounds": {"branches": 64, "depth": 3, "activeSeats": 3}
}
```

The host resolves referenced review material and checks currentness. A client cannot submit an arbitrary `reviewSnapshotCid` and assume authority. Parent frames are immutable; children apply an explicit assumption/source delta and receive a new identity.

## Semantic event

```json
{
  "schema": "execution-event/1",
  "eventId": "<stable-event-id>",
  "runId": "run-example",
  "definitionCid": "<program>",
  "epoch": 2,
  "journalSequence": 1042,
  "producerId": "solver-worker-1",
  "producerSequence": 19,
  "invocationId": "question-3/solver/attempt-1",
  "kind": "result-checked",
  "causedBy": ["<invocation-admitted-event>"],
  "frameCid": "<frame>",
  "payloadCid": "<checked-result>",
  "durationMs": 184,
  "observedAt": "2026-11-04T16:01:00Z"
}
```

The journal writer assigns committed ordering. Producer sequence and causal references preserve independent production history. Observation time is not proof of causality. Transport retries retain event identity. Large arrays and raw documents belong in payload artifacts.

## Result taxonomy

Use separate fields for execution status, semantic outcome and coverage. For example, execution can complete successfully while a query remains pending or unsupported. A generic `ok=true` cannot carry all three meanings.

| Dimension | Proposed values | Interpretation |
|---|---|---|
| Execution | completed, refused, failed, timed-out, cancelled, incomplete | What happened to the attempt |
| Semantics | supported, unknown, pending, opposed, conflicted, candidate | What the declared engine/frame establishes |
| Coverage | complete-fragment, selected-subset, truncated, unsupported-present | What was considered |
| Proof coverage | none, first-proof, selected-proofs, exhaustive-for-frame | What justification was retained |
| Authority | read-only, candidate, admitted-for-specified-effect | What the host permits |

Not every combination is valid. A timed-out attempt cannot claim a complete-fragment UNKNOWN answer. A first-proof result can be supported without having exhaustive proof coverage. A candidate hypothesis can be a successfully completed model result while remaining ineligible for production.

## Control command and checkpoint

A command includes command ID, run, epoch, expected control sequence, target scope, requested action and budget-limited parameters. The response reports accepted/refused and a receipt. A later boundary event reports applied/not-applied and its checkpoint watermark. These are separate records.

A checkpoint contains definition/frame identities, selected scope, completed results, deferred frontier, outstanding/reconciled effects, remaining budget and journal watermark. Whole-run quiescence requires no unaccounted admitted work in scope. A partial checkpoint names the scopes that continued running.

## Production candidate and deployment

```json
{
  "schema": "production-candidate/1",
  "kind": "exact-result-reuse",
  "candidateCid": "<candidate>",
  "guard": {"frameCid": "<frame>", "questionCid": "<question>"},
  "action": {"kind": "return-retained-result", "resultCid": "<result>"},
  "dependencyCids": ["<source>", "<review>", "<mapping>", "<engine>"],
  "referenceEvaluatorCid": "<reference>",
  "fallbackProgramCid": "<analysis-program>",
  "shadowReportCid": "<shadow-report>",
  "authority": "candidate-only"
}
```

Deployment is a separate host record binding candidate, allowed scope/effect, policy, generation and currentness. An activation references that generation and exact input tuple/episode. An effect intent has an idempotency key; its receipt or uncertain outcome is retained separately.

## Worked event sequence

An illustrative run admits source frame F, starts selector I1, retains workset W, admits solver I2, receives raw result R, checks proof P, retains read-only result C, proposes production K, shadows K and admits generation G. A repeated query matches G and returns C. A review withdrawal emits X; dependency lookup suspends G. A previously matched but undispatched activation fails its generation check. The next query invokes I3 under frame F2.

Each arrow is an event with causal references. The visual transfer and the production effect are not the same event. The repeated query does not mint new source evidence. F and C remain historical after X, while current eligibility changes.

## Limits and compatibility

Start with conservative per-envelope limits and existing repository ValueBudget conventions. Store detailed proofs and source sets by CID with bounded paging. Negotiate observation capabilities rather than assuming every engine can emit internal steps. Migration must preserve old schema identity and explicitly reconstruct new projections; never rewrite an old receipt under a new schema while retaining its old CID.
