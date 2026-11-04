Final v55 Camel and LCNC browser evidence — PASS
2026-11-04

This evidence uses synthetic source text and explicitly labelled synthetic parser
annotations. No private document corpus is included. No CoreNLP parser or model
provider ran in this Camel-specific fixture.

Frozen source: aaba7d5504d516e4ff6a68c19db11630e16c22ef
main.jar SHA-256:
c95b96cba473abdb412b04baed9f23fd915f25aba50d92a3d74a519648df0bf1
spacegraph.js SHA-256:
8a86bee9a35fb8f657d2e7b3762ab1046b3c286aa82c0d3ab2fa41035f8a128b

execution-identity.json records the three JavaScript bundles, successful-build
snapshot manifest verification, JAR-only dependencies, fixture source and compiled
class hashes, installed Camel manifest/JAR hashes, and completed cleanup.
No production class overlays, test classes, friend access or live build/classes
were used. The successful immutable main artifact was not changed by the proof.

The actual immutable /panels browser bundle displayed EnglishLcncPreset.build(CID).
Loading admitted no authored program. The surface made a separate read-only
vm.camel.catalog call to populate endpoint options. One explicit browser Run used
the real KanbanModule /api/lcnc/run service and retained its actual durable inline
receipt. The external harness supplied the HTTP listener, reduced ModuleContext,
synthetic source/parser reader, initial graph and controlled lifecycle timing.

Actual installed engine: Camel 4.8.5 DefaultCamelContext loaded through the verified
module URLClassLoader. VmHost.NONE was used. This route executes Camel on the JVM;
it is not fresh GraalJS isolation or a VM sandbox. Reach was LOCAL and the route
was fixed to direct:english-evidence -> mock:english-evidence. This does not prove
arbitrary Camel EIP network behavior or a full production daemon boot.

Retained English inspection CID:
sha256:151f4d3e166f6c6c3eab5bdcc879a58a916b01b1299dd10e9d618c8813ad3e53
Completed durable inline run receipt CID:
sha256:d959743cf0ea180c33460465bb1e27c98cba10520c503d44172a57b72a2316b4

The actual Camel body/reply and EvidenceCid header carried the exact selected
inspection CID. EnglishLcncPreset verified unchanged identity before the actual
DocumentEnglishWire/EnglishObservationWire query owner projected that retained
receipt into its explicit english-receipt-observations dataset. The bounded SPARQL
query returned the lexical quotation "not". Result bindings and source descriptor
were preserved in the canvas display nodes and durable receipt readback.

transport.json, query.json and retained-durable-receipt.json contain those observed
values. run.json is the actual HTTP run response; result.json contains the complete
browser assertions and observations. cas-manifest.json identifies exact retained
CAS bytes, including the durable receipt, frozen program, inspection CBOR, root
state CBOR and invocation ledger. Every CAS file was hash-checked before copying.

hostile-changed-cid.json records a real local Camel dispatch whose body/reply was
changed while the header retained the expected CID. Actual evidence verification
refused it before query. cancellation-drain.json records cancellation of a waiter
after its command was admitted: an explicitly labelled external timing barrier
held the actual dispatch; drain waited, the engine completed with the selected
CID, the owner closed, and subsequent admission was refused.

Fixture-only seccomp denied outgoing connect/sendto/sendmsg/sendmmsg. Browser
external requests and browser errors were empty. This does not establish a full
OS sandbox or production air gap. Cleanup completed and the fixture exited 0.

Read the actual screenshots in this order:
  camel-exact-receipt-inert-preset.png
  camel-completed-exact-receipt-query.png
  camel-durable-receipt-evidence.png
  camel-output-bindings.png
  camel-output-query.png
  camel-output-source.png
  camel-output-transport.png
  camel-output-dispatch.png
  camel-output-verify.png
  camel-actual-engine-refusal-and-drain.png

The last screenshot is clearly labelled an external proof observer, separate from
the actual canvas. It displays the actual engine and lifecycle/refusal outputs.
Closeups used actual UI tree expansion and drag/zoom gestures after execution;
screenshots were not composited. Whole-graph fit text is small, so use closeups and
JSON for detailed inspection.

The reduced host exposes agent/model vocabulary with no corresponding owner;
unavailable palette entries therefore remain visible. The note is presentation
only and has no runtime runner. Generic concentric captions such as "agent signals
-> board projections" do not establish active agent processing. Bound executed
nodes have their actual successful outputs and immutable invocation records.
This is a scoped execution proof, not a polished production-readiness claim.

The run was an inline authored run, not proof of saved-program history membership.
The canvas fits/repositions presentation fields, so its current program CID can
differ from the frozen run's CID while the authored definition matches. The
Evidence screenshot reports that distinction; no claim of exact canvas byte
identity or current source revisions is made. Query mode was retained snapshot.
Observations remain unreviewed and semantic/producer authority is not established.

Fixture.kt.txt, driver.cjs.txt, deny-egress.py.txt and observations.html.txt preserve
the exact reviewed external harness sources as text evidence, not repository
runtime additions. external-artifact-sha256.json is the original external proof
directory's manifest; sha256.json hashes the files actually published here.

First v54 attempts are retained in v54-setup and are not silently counted as
successful complete browser proofs:
  Attempt 1: the harness incorrectly counted the read-only catalog POST as authored
    graph execution. Its reduced registry initially omitted the actual catalog
    runner. The fixture drained and exited 0; the browser assertion failed.
  Attempt 2: actual program execution, exact transport identity, query and durable
    readback passed. The capture assertion expected a deep tree value to be visible
    before the actual canvas tree was expanded. The browser assertion failed.
  Attempt 3: the same execution checks passed. The capture helper expected a scale()
    CSS transform, while the actual camera uses matrix(). Browser capture failed.
  Attempt 4: the corrected external browser harness completed all assertions.
    The final v55 run then repeated the proof against the final immutable artifact
    and improved the decoded receipt and unobscured display screenshots.

All three failed setup/capture logs and the earlier v54 PASS result/identity are
published. Failure PNGs and build/runtime binaries remain external. No production
code was changed to make these captures pass.
