# PRELOAD contract audit: portable ordering and LCNC

## Scope and method

Bounded review of LcncFacts, LcncRunner, CcekNodes and LcncScopeFrame,
with named follow-ups into the common collection contracts, Series operations,
ArticulatedNode and BoardStoreElement. This is not a repository-wide compliance
claim. Each exploratory question was limited to two dependency hops; unresolved
frontiers are retained below instead of being treated as negative evidence.
Native validation extended this scope to its concrete test-compiler diagnostics
and compiled test signatures within the LCNC package.

The predict-rlm package was unavailable. Evidence curation used bounded Codex
subagents with file-scoped context, followed by supervisor source review. This
is an RLM-style review, not a claim that predict-rlm executed.

## Substitutions in this increment

| Finding | Contract | Substitution and acceptance |
| --- | --- | --- |
| LcncFacts.shapes uses the JVM-only toSortedMap extension in commonMain | User requires real portable sorted maps, not avoidance of ordering | Supply a common balanced TreeMap behind the existing SortedMap contract. Use explicit toCommonSortedMap at the caller. Verify ordered mutation, comparator equivalence, null values, backed ranges and balanced-tree invariants. |
| Two index-only loops in LcncFacts.learn | PRELOAD: looping with views | Iterate node and wire Series through view; preserve tuple insertion and authored order. |
| Three index-only loops in LcncRunner | PRELOAD: looping with views | Iterate through view; retain duplicate identity checks, cancellation and work limits. |
| requiredScopeIns creates a temporary mutable list for read-only consumption | PRELOAD: categorical idempotency | Select a Series and project names with alpha; bridge through view at the any predicate. |
| Concurrent GitPijulGateway replacement passes a value where a factory is required | Compiler diagnostic, common API portability | Preserve getOrPutIfMissing and supply its lambda. Do not restore the JVM-only putIfAbsent call. |
| CcekIncarnationTest imports JVM atomics and uses synchronized in commonTest | Portable common tests and nonblocking coroutine coordination | Use coroutine Mutex for shared counters and received-signal state. Make timeouts fail rather than allowing a zero-work concurrency test to pass, and cancel test scopes in finally. |
| BoardVocabularyConsistencyTest uses Java reflection | Portable common tests; exercise the real public boundary | Verify columns on ForgeKanbanIngest.fallbackReduction's produced board, without exposing private fields or substituting a duplicate column list. |
| Two ModelMux test names contain commas rejected by Native | Native test compiler diagnostics | Rename only those tests; retain assertions and fixtures. |
| Four LCNC tests infer exception return types from assertFailsWith | Executable regression evidence, not merely annotated methods | Explicit Unit results for three LcncConcentricScopeTest methods and LcncFanInTest.unknownNodeTypeStillThrows. Native rejected these signatures; the JVM report discovered only seven of ten concentric-scope methods before correction. |

The common LCNC ordering regression pins alphabetical traversal and the existing
first-key tie rule for equally specific literal shapes. Existing scope tests
remain the regression boundary for execution behavior. Build/test outcomes are
recorded below after execution, not inferred from these substitutions.

## Ordered map design

TreeMap is an original common-Kotlin adaptation of the repository's TreeSet AVL
rotation cases, not a copied JDK, Rust or Haskell implementation. Its existing
SortedMap interface remains the public contract; no JVM library is introduced.

- Immutable nodes share unchanged subtrees. Mutable root ownership supplies the
  normal put/remove API and live half-open range views.
- Subtree counts provide comparator-order rank and indexed selection as Join
  values. Lookup, update and deletion are logarithmic; updates allocate a
  logarithmic path. Full-map size is constant-time; bounded size uses ranks.
- Snapshot returns a Series of Join values over a captured root without copying
  all entries. Bound evaluation is lazy, and indexed access uses subtree counts.
  This is a mapping snapshot, not a deep copy of mutable keys or values.
  A bounded snapshot retains the captured full tree, not only visible entries;
  constant-time capture trades retained historical structure for copying cost.
- Comparator equivalence defines key identity. Replacement retains the original
  key object; nullable values and comparator-supported nullable keys are valid.
- Ordered entries, keys and values are backed mutable collections. Structural
  changes invalidate iterators except their own remove; value updates remain
  visible. This map is not thread-safe and does not own coroutine dispatch.

Acceptance includes randomized differential operations, ascending/descending
insertion and deletion, AVL height/count/order invariants, nested range rejection,
collection mutation, comparator-equivalent keys and snapshots after mutation.
Separate comparison-count tests bound work at thousands of entries. These are
correctness/complexity checks, not a cross-implementation throughput benchmark;
no claim of being the fastest map or best design for every workload is made.

## Open findings and prescribed next increments

1. **Bound the complete agent pipeline.** CcekNodes.liveOf creates an unlimited
   per-agent Channel and ignores trySend's result. ArticulatedNode.subscribeAgent
   accepts a suspending callback, so bounded send is available. However,
   ArticulatedNode.start launches work before acquiring its semaphore: suspended
   child coroutines could become the replacement unbounded queue. Bound admission
   before launch as well as delivery. Define slow-agent isolation, cancellation
   and shutdown behavior together; test stalled consumers beyond capacity and
   verify delivery, admitted-work bounds and termination. A channel-only edit is
   not completion.
2. **Align fake drain admission.** CcekNodes.fakeOf marks a node drained but send
   still appends pending, recorded and markdown state. Reject new work after
   drain while retaining previously accepted work. Test those exact effects.
   Do not infer live hard cancellation from its method name: ArticulatedNode.cancel
   closes intake and waits for fanOutJob completion before cancelling child scopes.
   Its complete lifecycle under stalled delivery remains unverified.
3. **Keep blocking persistence off the reactor.** The pre-existing BoardStoreElement
   edits replace Dispatchers.IO with Dispatchers.Unconfined. That resolves a
   common-platform name problem but does not offload blocking CAS/WAL operations.
   Put blocking execution behind a platform-owned persistence boundary or an
   explicitly supplied platform dispatcher. Verify reactor progress during slow
   persistence, durable acknowledgement ordering, failure and cancellation.
   These concurrent edits are preserved, not certified as safe.
4. **Separate identities before changing their representation.** CcekNodes uses
   string service handles and a NUL-concatenated agent-buffer key, contrary to
   PRELOAD's packed domain identities. Intern at the owning service boundary;
   compose stable ordinals with the existing Join/packed primitives. Test delimiter
   collisions and stable reattachment. This is not a CoroutineContext.Key identity
   violation, and a generic sorted map need not impose packed keys on every caller.
5. **Specify status retention.** CcekNodes has both drop-oldest status delivery
   and unbounded retained status history. Decide which is a transient projection
   and which is durable evidence; bound projections and reuse the durable spine.
   No silent truncation or new independent history store is prescribed.

## Deliberately retained

- LcncRunner's argumentBindings.toList is a detached receipt snapshot; the live
  buffer is cleared on a later invocation. A lazy view would corrupt historical
  results, so this is a justified materialization boundary.
- MANY-port payload Lists and public KIF/contract Lists are existing consumer
  contracts, not automatically violations. No unverified wire-shape change.
- LcncScopeFrame uses a singleton key, and CcekNodes compares context keys by
  reference identity. Display-only toString is not an identity comparison.
- The passive scope frame is not promoted into a reactor merely because it is
  context-resident. Lifecycle ownership must be established before adding effects.
- LinearHashMap hot lookup, immutable CAS identity, canonical Confix and derived
  ISAM indexing remain separate. The specialized CAS B+tree is not forced into
  a generic mutable ordered-map API.
- Existing SortedMap/NavigableMap interfaces and their notices are preserved.
  A full NavigableMap implementation and correction of its nullable-return
  contract are separate work; this increment must not imply they are complete.

## Supervised Developer Review

Accept this increment only after the ordered map, LCNC regressions, JVM build
gate and common/native compilation pass. Keep the pipeline and persistence
findings open until their coupled behavior is tested. Integrate independently
passing increments at the shared checkpoint; do not hold ordered-map completion
for an unrelated ontology, storage or lifecycle redesign.

## Validation receipt

- `jvmMainClasses`, `compileCommonMainKotlinMetadata`, and `compileKotlinMacos`
  passed on the final source revision in the same invocation as the JVM tests.
- Final expanded run: 92 selected JVM tests passed: TreeMapTest (11), TreeMapComplexityTest (2),
  LcncFactsOrderingTest (2), LcncFactsTest (8), LcncScopeSemanticsTest (6),
  LcncConcentricScopeTest (10), LcncFanInTest (8), LcncSubprogramRecursionTest (5),
  LcncArgumentRuntimeTest (5), GitPijulGatewayTest (7), CcekIncarnationTest (8),
  BoardVocabularyConsistencyTest (3), AcpContentParseTest (9), and RosterIdsTest (8).
  Zero failures, errors or skips, counted from Gradle's XML reports.
- The map tests include 16,000 randomized operations, nine adversarial
  insertion/deletion order combinations, and comparison budgets through 8,192
  entries. These operation counts are not counts of separate test cases.
- Repository-built Snapshot bytecode contains only frozen-root/comparator/bounds
  storage plus lazy/indexed access fields; it has no captured live-map owner.
- No compiler warning references the changed TreeMap, LcncFacts, LcncRunner or
  GitPijulGateway source files in the final validation log. Existing warnings
  elsewhere and the macosX64Main source-set configuration warning remain.
- IntelliJ supplied the existing failed-build diagnostics directly, without a
  new search dialog. Its historical report is not relabelled as a fresh green
  IDE inspection. Compilation and tests above are command-confirmed evidence.
- JVM/compiler log: `/private/tmp/trikeshed-sorted-map-validation.log`.
- `compileTestKotlinMacos` passed after replacing the test portability blockers
  above. Compiler-visible LCNC signatures identified exactly four non-Unit
  no-argument test methods; all four now execute and pass in the JVM run.
- Cross-platform test log: `/private/tmp/trikeshed-sorted-map-cross-platform-tests.log`.
- Native execution: 61 tests passed on macOS ARM64, with zero failures, errors
  or skips: TreeMapTest (11), TreeMapComplexityTest (2), LcncFactsOrderingTest (2),
  CcekIncarnationTest (8), BoardVocabularyConsistencyTest (3),
  LcncConcentricScopeTest (10), LcncFanInTest (8), AcpContentParseTest (9),
  and RosterIdsTest (8). Counts come from `build/test-results/macosTest` XML.
- `git diff --check` passed. No runtime daemon restart, server replacement,
  dependency addition or test exclusion was used for this increment.

### Supervised Developer Review

The portable ordering increment has passing JVM and Native execution plus
common/native compilation evidence. The open pipeline, persistence, identity and retention findings above
remain open; this receipt does not certify their behavior.
