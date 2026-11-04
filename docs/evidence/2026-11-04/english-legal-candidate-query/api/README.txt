Retained legal-candidate queries — actual v59 API and typed LCNC proof PASS
2026-11-04

Frozen source: 5548a842517e98f47f83aebfa30387dc2cc820b4
main.jar SHA-256:
b5a9df6993873660edb0c15a8739f2e589ccc54a0421d525eaccb0f5053f0e89
The coordinated build passed155 selected checks and all three JavaScript bundles.
execution-identity.json binds the unchanged main JAR, bundles and dependency-list
hashes. Every production class used here came from that frozen main JAR. External
classes had proof namespaces only, with JAR-only dependencies, no test classes,
production class overlays, friend access or live build/classes directories.

This is a retained-observation query proof. The earlier actual CoreNLP4.6 v58
Documents proof is published at ../../english-legal-candidates. Its seven exact
CBOR receipts and three exact synthetic UTF-8 sources are reused here. No new NLP
or document ingestion was performed. Exact original sourceSnapshot ProjectDoc
rows were reconstructed in a read-only corpus fixture so currentness could be
checked and explicitly changed/reset. These rows are a replay boundary, not a
claim that a live user's document is still current. Receipt identity does not
authenticate parser execution or producer authority.

The actual production POST /api/documents/english/legal-patterns/query owner takes
exactly inspectionCid, explicit mode=current|snapshot and an inspectionOnly visual
program. The registered document.english.patterns.query LCNC node uses the same
owner and presence semantics. Original text, receipt CID/schema, reconstructed
observations, complete index:copy graph identity and exact quotation spans pass
the existing bounded verifier. No parser reruns when deriving candidates or
querying them. A fresh isolated QuadStore uses the derived candidate's named graph
urn:trikeshed:english:legal:<candidate digest>. No global graph is queried or
mutated, and no candidate is admitted to NARS, Rete or legal review.

Observed cases:
  - Eight-pattern kind+integerValue30+unitLemma=day query returns exact 'within
    thirty (30) business days', its full source sentence, status and source CID.
  - The separate passive 'within 30 calendar days' is returned from the conflicting
    document; its 'within thirty (60) business days' duration is excluded.
  - The conflicting duration has no integerValue triple. Its number-form resources
    still retain30 and60, and conflicting-number-forms remains queryable. Absence
    means unknown or unrepresented, not false and not absence of an obligation.
  - Same-sentence modal/quantity bindings are labelled cooccurrence-only, without
    establishing negation, condition, exception scope or legal applicability.
  - Actual retained copy vertex2:3 ('requirements') preserves original source
    offsets4..16, index/copy, sourceText and candidateReceipt. It is another graph
    vertex anchored to the original word, not another lexical occurrence. The
    baseline corpus has70 source tokens and71 full parser vertices, one copied.
  - Changed current source metadata refuses with HTTP409 and no partial results;
    explicit snapshot mode succeeds with historical=true/currentness=not-checked.
  - Explicit null mode refuses409; non-inspection program400; nine patterns413.
    A separate manually annotated1200-token control exceeds the20000-quad cap and
    refuses409 with 'inspect a smaller text range', without partial results. This
    control is not an actual parser accuracy or performance result.
  - A strict-typechecked actual LcncRunner procedure matches the API dataset. A
    null input cannot fall back to a valid authored mode. Four accepted current
    queries complete while the parent/child owners drain, then registry entries
    disappear. Final wire/Panel owners and supervisors drained; fixture exit0.

api-summary.json is the compact case/status ledger and exact returned bindings.
api-result.json.gz preserves the original complete final API record:385383 raw
bytes,16408 gzip bytes, raw SHA-2565961206c0800703397e2c096c260030661bf3245cfe944dab8929f1e8f62b07e.
That raw digest is apiResultCid in audit/api-audit.json and the companion combined
audit. copy-source-query.json preserves the additional actual eight-pattern
copy/source query and response. manifest.json preserves the original replay
manifest, including its worker paths. cas-manifest.json maps portable filenames.
cas-readbacks contains eight unique exact CBOR blocks: seven input receipts plus
the new explicit-snapshot copy candidate. Four other derived candidate CIDs match
the existing input bytes exactly. sources contains the three exact synthetic
source bodies; deliberate CRLF bytes are retained with local -text attributes.
No supplied user PDFs or private source material are published.

Independent built-in Node comparator PASS:18 API/typed outputs,23 bindings and
seven refusals. It uses source bytes, CBOR and hashes rather than the production
verifier or parser. audit/negative-controls.json records six auditor-only tamper
refusals, including wrong named graph, invented unresolved integer, wrong copied
source span, altered candidate CBOR and altered durable source bindings and JSON bytes.
These are checks of the independent comparator, not additional host API calls.
The companion Panels audit additionally compares five durable runs and five raw
stored run receipts. Its browser captures and durable JSON blocks are published
once in ../panels. Candidate observations remain unreviewed throughout.

Portable read-only audit, from this directory (Node built-ins only):
  node audit/replay.cjs.txt input
  node audit/replay.cjs.txt query
  node audit/replay.cjs.txt query ../panels
The documentation-only runner remaps original worker file paths to the published
exact bytes and decompresses original records in memory. Archived auditor and
decoder sources are unchanged. All three portable replay reports were byte-for-
byte identical to the original independent reports; see
 audit/portable-replay-verification.json. The original comparator and decoder
source digests are retained in each report. No parser, Kotlin verifier, runtime
fixture or model is launched by these audit commands.

method contains the external fixture, drivers, compile/run commands and export
reader as non-executable source evidence with .txt suffixes. Shared Panels assembly
source is published once in ../panels. These files are documentation, not new
repository runtime scripts. proof-history.json preserves the compile/capture
attempts: an internal-codec reference and bracket errors were fixed in the external
harness; visual-guard expectations were corrected before runtime. Two browser
capture attempts needed native facet refresh timing and the actual failed-run
HTTP400 expectation. Production remained unchanged. The final complete browser
attempt passed; earlier primary API, drain and artifact checks also passed.

Limits: this loopback listener forwards real owners in reduced ModuleContexts,
not a full production daemon boot. Primary analysis Rete/blackboard stayed empty;
parser, extractor, model mux, global QuadStore, belief bag and review owners were
absent. The separate Panels context holds operational board/run records, without
legal world admission. Fixture-only syscall denial blocked outbound connect and
send operations; this is neither a full OS sandbox nor a production air-gap
certificate. Counts are not evidence confidence. Quantities are bounded lexical
readings, with no calendar conversion or deadline arithmetic. No legal force,
review approval, general parser accuracy or comprehension is established.
