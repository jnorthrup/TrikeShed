Retained English corpus topics? port: public-API RED/GREEN observer
2026-11-05. Observer package only; no production JARs or dependencies are included.

The same separately compiled observer classes ran against two immutable 120-JAR
classpaths, using fresh copies of the same genuine archived English inspection.
The production corpus runner, LCNC walker, corpus/LDA implementation, retained-reader
and PackCasStore all reported the expected frozen main JAR as their CodeSource.

RED v75: source 9a4eaac3d7bbde1ab52a7aae6a23a1cda7ab34b3
main SHA256 a15bee549a19af990aa57a34cccde775737633bb11418e116ace183c8dc2f867
GREEN v76: source 0219223a3846887fe283b93598942de332214cd2
main SHA256 d0a8133b10e4b18e1bd4076c1440688ad94a2eeb2bbcdefe743f4759ace75636

Actual graph: json.value receipt list and topic configuration, text.value current
mode, production document.english.corpus with its visible topics? input, then
scope.out topics/topicCid. Full authored programs and actual outputs are embedded
in each writer.json. The canonical comparison calls the public production
ModuleRouteRegistry POST /api/documents/english/corpus handler in process.
This is not a socket HTTP or browser/screenshot proof.

Observed RED: topics? configuration is ignored, topics?=null does not override an
authored model, and invalid topics? configs incorrectly succeed. Canonical topics=null
already takes precedence. Observed GREEN: all four checks pass, including complete
canonical-route/graph output equality and identical retained corpus/topic CIDs:
topic sha256:974a6278d9a14b49fec408f23fe5f5b57f8186fbe88af0f64cff17208e034090
receipt sha256:df26801c501f8a21c35ee726cda2669cfd995bf72236dc8acccd374cd9f76fbb

Input identity: inspection
sha256:3f958801fe455258b0b4dea29a8cc4b77c26ce508e8988b67055c67cde221a0d
from positive-unmutated-runtime.tar.gz, SHA256
51b3526fcfa478b497550014d9c0c5d73a0a42d596ec76303fdb8c0fe58b9acc.
It contains the labelled synthetic 41-byte pilot “Tenant is a Person. A Person is an
Agent.”, its original production project metadata and archived parser receipt. No
user PDF, fresh parser, generative provider or new Stanford-execution claim is involved.
The writer checks every recorded source snapshot against the remounted real
JvmProjectCorpus, including exact project/document/CID/revision/sequence bindings.
The route rechecks currentness before and after corpus construction and retention.

Both writer processes exited zero after draining the wire and ProjectScopes,
completing/joining the owner and closing CAS. Original source/head bytes and the
entire original 7666-byte CAS prefix remained unchanged; only derived descriptive
corpus/matrix/topic records were retained. Blackboard and Rete stayed empty; no
belief bag, KIF bank or NARS Rete was configured. Statistics remain unreviewed and
do not become facts, legal conclusions, review authority or confidence.

Each subsequent reader ran as a separate process after writer exit and independently
bounded-hash-verified seven retained blocks, including original inspection/source
and derived receipts. Reader currentness is not claimed: it verifies retained bytes;
currentness was checked by the writer and production corpus handler. Each case's
retained-cas.tar.gz preserves the actual after-close CAS pack used by that reader.

Contents
- verification.json: compact comparison, process exits and immutable artifact identities.
- inputs/: exact original public synthetic archive and setup metadata.
- red-v75-attempt1/ and green-v76-attempt1/: full actual writer output (programs,
  configuration, canonical-route reports, topic maps, nulls/refusals and provenance),
  cleanup, separate-process reopen readbacks, raw process logs and retained CAS pack.
- observer/: exact corrected source as text, original failed compile-attempt source,
  both compile logs, exact command scripts as text, compiled observer-class hashes,
  and frozen snapshot identity/classpath metadata. No friend paths, overlays, test
  helpers or live application class directories were used.

Compile attempt 1 failed on two observer closing-bracket typos; its source and errors
are preserved. Corrected attempt 2 emitted the observer classes subsequently used
unchanged for all four successful writer/reader processes. No production code changed
to make the observer pass. No further runtime was required after the paired result.

Exact invocation shape (full commands/arguments are in verification.json):
  bash compile.sh OLD_SNAPSHOT FRESH_OBSERVER_CLASSES
  EXPECTED_SOURCE_HEAD=... EXPECTED_MAIN_SHA256=... bash run-observer.sh CASE SNAPSHOT OBSERVER_CLASSES writer red|green
  EXPECTED_SOURCE_HEAD=... EXPECTED_MAIN_SHA256=... bash run-observer.sh CASE SNAPSHOT OBSERVER_CLASSES reopen red|green

These scripts record this session's explicit artifact paths/toolchain. Reproduction
needs the matching production/dependency JARs, which are identified, not distributed
here. SHA256.json hashes every other payload file and intentionally excludes itself.
