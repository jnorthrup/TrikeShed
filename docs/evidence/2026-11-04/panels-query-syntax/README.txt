Panels query-syntax labels — actual v60 browser proof PASS
2026-11-04; source a42f1dbdefadf7fe774247b4abfd589e6b4ecb73

The v60 coordinated build passed155 selected checks and all three JS bundles.
The backend JAR is byte-identical to the previously verified v59 artifact:
b5a9df6993873660edb0c15a8739f2e589ccc54a0421d525eaccb0f5053f0e89
New spacegraph.js SHA-256:
8158c98757aafa01fed68ace364ae21b397bca7575815ab1177bb361452edfef

The actual new /panels bundle was exercised in Chromium with the unchanged actual
query and Kanban durable owners. No runtime owner, execution guard, sandbox,
serialized parameter value or parallel executor was added by the three-file UI
change. Classification uses SqlGraph.TYPES and SparqlGraph.contracts(), gated by
the existing inspectionOnly construction control.

Observed browser checks:
  - All13 nodes in the SPARQL syntax construction and all3 nodes in the SQL
    SELECT-star/table construction show 'Query syntax · Inspection only'.
  - Those grammar cards have no missing-runtime warning, robot sprite, agent
    seed/emit captions or concentric execution-lane frame.
  - Run, Live and Debug remain disabled. The footer says 'Inspection only · Run
    and Live are disabled', rather than inviting unavailable procedure execution.
  - Every rendered non-checkbox syntax input/textarea/select has a native title
    equal to its full value, including long predicate and table values. This was
    checked through actual DOM properties; the screenshots do not claim to capture
    the browser's native hover-tooltip popup.
  - A real unbound agent.list node in a mixed inspectionOnly construction still
    shows its missing-owner warning. The plain unbound executable control also
    retains its warning and normal enabled Run control. Neither control was run.
  - The real outer document.english.patterns.query construction keeps its normal
    executable presentation. One explicit Run completed HTTP200 and returned the
    unchanged exact 'within thirty (30) business days' binding and unreviewed
    current-source descriptor through a durable Kanban receipt.

Durable receipt:
sha256:858da54575a8a27d562bd108b5b704c39dd2258b4b66d598ada634c81b12e75b
durable-receipt.json contains the exact actual /api/lcnc/content response bytes;
their SHA-256 equals that receipt CID and their parsed query output equals the
actual run response. Candidate/source identity, currentness and cooccurrence-only
policy remain unchanged. These raw durable bytes are JSON, not candidate CBOR.

Before/after images are actual uncomposited browser captures:
  sparql-before-v59.png: unchanged earlier v59 capture, source5548a842, JSbdc75068…
  legal-inner-thirty-days-accurate-labels.png: actual new v60 SPARQL view
  sql-inspection-only-accurate-labels.png: actual new v60 SQL view
  mixed-real-executable-warning-preserved.png
  executable-owner-warning-unchanged.png
  outer-query-still-completes-durable-evidence.png
No SQL before image is claimed. The SQL view is a representative three-node
grammar construction; this is not an SQL execution or all-grammar accuracy proof.

browser-summary.json contains observed node labels, complete field values/titles,
footer, control cases and actual query results. browser-result.json.gz preserves
the complete original record in12870 bytes (raw254968); its hash is recorded in
the summary. execution-identity.json records exact frontend/backend/dependency and
external harness identities. .txt sources are external proof evidence, not
repository runtime scripts. sha256.json hashes the published files.

Scope: zero setup queries and exactly one authored outer run; zero new NLP,
extractor, provider/model or global QuadStore owner. Exact retained v58 actual
CoreNLP4.6 observations were reused; parserExecution remains not-reverified and
producer authority is not established. Primary analysis Rete/blackboard remain
empty. Actual Kanban has separate operational board/run records; this is not legal
fact or rule admission. Readiness warnings in unrelated palette/executable nodes
are intentionally preserved. Native field titles do not alter serialized values.
No legal force, deadlines, applicability or general production readiness is claimed.

All owners drained; fixture exit0. Browser errors and external requests were empty.
Fixture-only outbound syscall denial is not a full sandbox or production air gap.
The immutable snapshot and dependencies were verified unchanged after execution;
there were no test-class overlays, friend access or live build/classes.

History: external compile1 rejected an invalid empty Series literal in the new
unbound control fixture before any runtime started. The fixture was corrected to
the existing emptySeriesOf() API; compile2 passed. The first actual browser attempt
passed. Both compile logs and runtime/browser logs are retained. No production
change, extra parser, mirrored unit test or unrelated runtime proof loop was used
to make this presentation check pass.
