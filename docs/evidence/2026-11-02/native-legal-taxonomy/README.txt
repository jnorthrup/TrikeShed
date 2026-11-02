Synthetic legal-document taxonomy demonstration — actual native execution

This is a synthetic source-assertion example, not a production asserted-taxonomy
receipt or a finding of legal validity. No central source files or Gradle builds
were changed. The generator deliberately supplies host-approved fixture inputs;
it does not promote existing BookCurationReceipt norms or presumed statements.

Fictional filing A says:
  "Exhibit A is a certificate."
  "The index also calls Exhibit A a certificate."
Fictional filing B independently uses the same readable label "Exhibit A".
The two entities have different source-context IDs and different opaque terms:
  A: ebe772225c052c925783d603a28b15d17ecfab145485668c5c619fa476a117d6c
  B: e90310bd9713ab49e51a7f8fc66ed594e3f5aa14005e523d30f70783aadf43b93

The selected A frame contains only A's two source assertions, the two pinned
SUMO class edges and one explicit host logical rule. B's source candidate is
retained in source-manifest.json with a context-mismatch exclusion and is absent
from A's executable facts. A separate B frame demonstrates that B can be derived
only when its own source context is selected. Context selection is enforced by
this explicit fixture construction; native KIF modal/context operators and a
production authenticated asserted-taxonomy admission format are not claimed.

Actual SUMO terms verified from cached Merge.kif, upstream revision
25bb366fdffd8769e741d2e22f215c2785bddce1, file CID sha256:bda2f68ce375f64134c5c58656f94a50f920444b3b17b0329ee00fd0437b3cc2:
  (subclass Certificate Text)                 line 16053
  (subclass Text ContentBearingObject)        line 15788
The Certificate mapping is a synthetic assumption of this example. Only these
two taxonomy edges are loaded, not the complete ontology or any norm calculus.
The unrelated absent case predicate local:ServedOn is explicitly local, not a
claimed SUMO vocabulary term.

The sole host rule is explicit and independently attributed:
  (=> (and (instance ?entity ?child) (subclass ?child ?parent))
      (instance ?entity ?parent))

Actual path executed for every case:
  synthetic scoped tuple + exact quote -> compiled SumoNixBridge JSON and MLIR
  -> pinned mlnix-opt parse -> sumo-to-kif -> kif-to-nix
  -> actual mlnix run-mlir, exactly matching expected facts/rules
  -> rejoin original query/provenance sidecar -> pinned native mlnix Horn solver
  -> exact complete-JSON comparison with independent official Nix 2.35.4 run.

Results (all native checks and official-Nix comparisons passed):
  classification               SUPPORTED  A is ContentBearingObject via Certificate/Text
  absent-case-predicate        UNKNOWN    no local:ServedOn fact or rule
  cross-context                UNKNOWN    B entity excluded from selected A frame
  one-source-retracted         SUPPORTED  A's second quotation still supplies a witness
  last-source-retracted        UNKNOWN    no remaining A certificate assertion
  separate-context-B           SUPPORTED  B derives under its separately selected frame

classification.result.stdout contains the actual proof. Its two ordered rule
applications rest on the exact A source assertion and both quoted SUMO edges.
The asserted A leaf lists both quote-backed records with
witnessMode=any-one-asserted-source. They are alternative witnesses, not joint
premises or independent votes. one-source-retracted.result.stdout retains only
A's second source. last-source-retracted.result.stdout has proof=null. The proof
is the backend's first derivation, not an exhaustive alternative-proof DAG.
UNKNOWN is no derivation in this selected positive fragment; it is not refutation
or evidence that a document was never served. Duty, service validity, exceptions
and real-world obligation applicability remain UNKNOWN.

Evidence and replay:
  source-manifest.json         exact synthetic texts/spans, source/context/ontology
                               CIDs, lexical term dictionary, admitted/excluded
                               source IDs and frame IDs
  *.input.json                exact Kotlin host exports
  *.mlir / *.parsed.mlir / *.lowered.mlir
  *.native-values.stdout      actual native fact/rule interchange output
  *.native-input.json         lowered values rejoined with original sidecars
  *.result.stdout             actual native Horn result and source proof
  *.official-nix.stdout       independent exact oracle output
  results.json                six checked results, source IDs and witness modes
  GenerateLegalTaxonomyDemo.kt actual Kotlin exporter fixture generator
  run-demo.py                 actual native/oracle execution and assertions
  artifact-sha256.json         retained artifact hashes

Bootstrap did not read mutable central build output. It reused the independently
frozen v38 main.jar plus the separately compiled actual bridge helper. The bridge
source hash was checked equal to current central SumoNixBridge.kt before this
run; source/class/JAR hashes and exact upstream pins are in the manifest. Actual
native mlir-nix revision 1547e4a was built unmodified with official LLVM/MLIR
22.1.8 SDK. Every process had a 45-second wall limit and 8-GiB address-space cap;
this demonstration makes no production OS memory/network isolation claim.

Stage-specific input identities
The original Kotlin export and the JSON rejoined after native MLIR evaluation
have identical parsed payloads and the same frameId, but their JSON serialization
bytes can differ. source-manifest.frames.originalExportInputCid identifies the
exact *.input.json bytes. results.results.executedNativeInputCid identifies the
exact *.native-input.json bytes consumed by the native solver. Each result also
carries originalExportInputCid for direct comparison. The legacy inputCid field
in source-manifest remains the original export CID; in results it remains the
executed native input CID. stage-identity.json labels both stages and records
verified hashes, filenames and frame identity for all six cases. This labeling
change required no solver rerun.
