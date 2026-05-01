# libs/kursive — Boundary Audit & Path to Stable

## Boundary Audit

### Clean boundaries (no issues)
- **std.kt**: Pure functions and stateless parser factories. No global state.
  Deterministic for same input. Well-factored (ws, number, quoted, separated).
- **NAL.kt**: Pure enum taxonomy. NALLevel→operator mapping is bidirectional
  and complete. No side effects.
- **Narsive grammar**: `Narsive` object uses lazy initialization for mutual
  recursion (relationship/operation/compoundTerm depend on term, which depends
  on them). Init block resolves `_term` lateinit. This is clean.
- **JursiveCharSeries**: Checkpoint/rewind model is sound. No resource leaks.
  Event tracing is append-only with no global state.

### Boundary concerns (needs attention)

1. **NarsiveElementKind doubles as CoroutineContext.Key**
   - `NarsiveElementKind` implements `CoroutineContext.Key<NarsiveElement>`.
     This is unusual — typically Key is a singleton companion object, not an
     enum value. It works because enum values are singletons, but it couples
     the grammar taxonomy to the coroutine context type system.
   - `NarsiveElement` implements `CoroutineContext.Element`, which means every
     parsed node participates in coroutine context folding. This is the mechanism
     for fanout but may cause confusion when composed with other context elements.
   - **Status**: This is intentional design, not a bug. Document clearly.
   - **Fix**: Add KDoc explaining why enum-as-Key is valid and what invariants
     it provides (singleton guarantee, exhaustive fanout via entries).

2. **NarsiveSupervisorJob wraps kotlinx.coroutines.SupervisorJob but also implements Job**
   - Has both a `supervisor` delegate field and inherits from
     `AbstractCoroutineContextElement(Job)`. The `isActive()` and `cancel()`
     delegate to `supervisor`, but other Job methods (onCompleted, onCancelled,
     invokeOnCompletion) are not implemented.
   - `fanout()` scans all elements linearly each call. No caching.
   - **Fix**: Either implement the full Job interface or remove the Job
     inheritance and keep only the fanout/query methods. Cache fanout results
     if performance matters for large traces.

3. **Narsive._term lateinit var is public and mutable**
   - `var _term` is set in the `init {}` block but could theoretically be
     reassigned. `termStep` and `termParser` access it with `!!`.
   - **Fix**: Make `_term` private. Expose only `term` (the public getter).
     If late init is needed, use `lateinit` with proper visibility.

4. **SeriesBuffer is not thread-safe**
   - `SeriesBuffer<T>` uses a raw `Array<Any?>` with manual growth. No
     synchronization. Used in JursiveCharSeries (sink field) and in Narsive
   - **Fix**: Document that parsing is single-threaded. If concurrent parsing
     is needed, make SeriesBuffer thread-safe or use per-thread instances.

5. **Nars3Machine: no backpressure on channels**
   - `Channel<Nars3Message>()` uses RENDEZVOUS capacity (default). The refeeding
     loop sends back to the initial input channel but the initial sender also
     closes it immediately after sending. This creates a race between the
     refeeding launch and the initial send+close.
   - `LocalAtom` and `ChannelizedAtom` have identical implementations.
   - **Fix**: Use `Channel.BUFFERED` or `Channel.UNLIMITED` for arena chains.
     Differentiate LocalAtom vs ChannelizedAtom with actual behavior, or merge
     them into a single atom type.

6. **Jursive.kt toKursiveRowVec() hardcodes 17 columns**
   - The evidence→RowVec conversion uses a fixed column array
     (`KURSIVE_EVIDENCE_COLUMNS`) that must stay in sync with `TypeEvidence`
     fields. Any change to TypeEvidence requires manual update here.
   - **Fix**: Generate the column schema from TypeEvidence reflection, or add
     a consistency test that asserts column count matches TypeEvidence fields.

7. **Cross-module import: `userspace.concurrency.Job`**
   - `NarsiveSupervisorJob` imports `borg.trikeshed.userspace.concurrency.Job`.
     This creates a dependency from the parser layer to the userspace layer,
     which may cause circular dependency issues.
   - **Fix**: Use `kotlinx.coroutines.Job` directly if the userspace Job is
     just a typealias. If it has extra behavior, extract the needed interface
     to a shared module.

## Integration Steps

1. **Document NarsiveElementKind-as-Key pattern**: Add KDoc explaining the
   singleton-guarantee invariant and fanout semantics.
2. **Stabilize NarsiveSupervisorJob**: Decide whether it's a full Job or just
   a query structure. Add caching for fanout results.
3. **Fix Nars3Machine channel semantics**: Use buffered channels. Fix the
   race between refeeding and initial close. Merge duplicate atom types.
4. **Protect Narsive._term**: Make private, use lateinit.
5. **Decouple from userspace.concurrency.Job**: Use kotlinx.coroutines.Job
   or extract shared interface.
6. **Add fuzz testing**: Feed random input to Narsive.parseTask() to verify
   no infinite loops or crashes from malformed input.

## Path to Stable

- [x] NarsiveElementKind/Element KDoc pass
- [x] NarsiveSupervisorJob API cleanup (full Job or query-only)
- [x] Nars3Machine channel fix (buffered, race-free)
- [x] LocalAtom/ChannelizedAtom merge or differentiation
- [x] Narsive._term visibility fix
- [x] userspace.concurrency.Job decoupling
- [x] TypeEvidence column schema consistency test
- [x] Fuzz testing for parser robustness
- [x] Performance benchmark for large NAL programs (>1000 lines)
