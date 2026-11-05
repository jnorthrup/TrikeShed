# R4c: interrupted execution, explicit retry and durable inspection

R4c advances [R4b's completed-receipt crash check](07-runtime-profile-recovery.md)
to unfinished direct LCNC runs. Its boundary is deliberately observable: the
production debugger holds a real node transition, the writer JVM receives
SIGKILL, and a new JVM recovers the retained run record. The interrupted run
is preserved. An explicit new Run action produces a distinct execution; a
third JVM then inspects both records without the miner or native runners.
This remains a cloud Linux x86-64 demonstrator, not a change on Jim's Mac.

## Existing recovery ownership

`LcncRunService.recover()` already reconstructs run rows from the production
board WAL and its CAS command pointers. Previously started `validating` or
`running` work is committed as `interrupted`, with `ok=false`,
`error=runtime_restarted` and a `previousReceiptCid` identifying its prior
record. The recovery commit has its own revision and receipt identity.
It does not manufacture a successful terminal result.

`LcncInvocationLedger` is a bounded live observation owner. The run service
retains its invocation snapshot when finishing a run. If the JVM dies before
that finish, the terminal snapshot may never have been written. In these
scenarios the interrupted receipt has no outputs or invocation-ledger CID.
That absence does not establish that no earlier computation or effect
occurred. The host-captured debugger response is explicitly last-observed
live evidence, separate from the recovered durable receipt.

The browser marks a lost response as an unknown outcome, sends no automatic
retry, disables the old step controls and labels the displayed pause as a
last observation. A later GET for the exact old token/run pair returns 404
`debug_run_not_active` with the matching recovered receipt. It does not
substitute a newer run with the same program name. The Evidence pane shows
`interrupted`, reports that per-node invocation evidence is unavailable, and
does not offer completed-output display for that receipt.

This direct-run policy is separate from durable queued work. The existing
Kanban recovery path can dispatch valid never-started queued requests. R4c
does not globally disable that policy or claim that every reopening is
read-only. Its interrupted attempts have already started and are not
re-enqueued by the demonstration.

## Reopening the document composition

The packaged entry adds `recover` alongside `run` and `inspect`:

```
/opt/trikeshed/bin/airgap-document /state /opt/trikeshed 18764 recover
```

Use the same scalar and Confix profile environment variables as `run`, as
specified in the [package README](../../release/airgap-document/README.md).
`run` still requires fresh state and performs the original source admission.
Both `recover` and `inspect` require existing state, remount the durable
project ledger, compare all six document revisions and verify their bytes.
Neither constructs a miner. Source validation precedes native registration.
`recover` checks and registers the pinned native modules; `inspect` does not.

The recovery proof hides installation source assets and PDF executables
from the second JVM while leaving the pinned bitcode available. It therefore
cannot satisfy recovery by extracting the fixture again. The ordinary named
preset loads the same admitted source reference and passage offset. Only
an explicit Run action submits the new direct execution. Its run ID differs
from the interrupted attempt, its program CID is identical, and the older
interrupted row remains unchanged. This is recomputation of the bounded
graph, not restoration of a paused native stack or reuse of unretained
partial outputs.

The final `inspect` JVM hides both assets and bitcode, has no miner or LLVM
runners, and reopens exactly the two stored run rows. It verifies the source
quotation through the existing receipt and content routes. No third LCNC
run is submitted.

## Three failure boundaries

| Hold before node | Work actually reached | Required recovered meaning |
|---|---|---|
| `document` | Direct execution admitted; first node has not run | Interrupted admission, with no invented result |
| `scan` | Document reference and bounded passage prepared; native scanner has not run | Interrupted partial traversal, with no invented terminal ledger |
| `selection` | Native scan returned; final geometry selection has not run | Interrupted traversal after native work, with no completed quotation claim |

Each scenario starts from fresh state and uses the real production UI,
debugger and HTTP routes. A controller captures the actual state/debug
responses, locates the Java descendant of its owned bubblewrap monitor,
checks its executable and main class, and kills that PID. It requires exit
137, a reaped Java PID and the absence of writer cleanup. The subsequent
recovery and inspection JVMs have distinct boot identities and drain
normally. The browser requests and original process outputs are retained.

A cooperative boundary avoids claiming hard native cancellation. These
experiments do not kill a guest at an arbitrary instruction, interrupt a
filesystem write, simulate power loss or establish every effect adapter's
idempotency. They do establish that an unfinished run remains distinguishable
from a completed one across the tested process exits.

## Independent state audit

The audit reads the retained files directly after all owners close. It
checks every CAS object against its path hash, then decodes the running,
interrupted and completed command envelopes. The interrupted command must
point to the exact preceding running receipt. The new completed command
must have a different run ID and the same frozen program identity. The
final inspector's two rows and six source revisions must match the recovery
process's records exactly.

For the explicit new run, the audit verifies the invocation-ledger run and
program IDs, eight ordered consumed wires, native `liveBytesAfter=0`, the
original PDF bytes and the exact UTF-16 quotation range. It does not turn
host-captured debug observations into a missing durable invocation ledger.
A successful new run cannot overwrite or rehabilitate the interrupted row.

Startup controls retain the prior fresh/existing-state and module-identity
refusals. Added controls check empty recovery and a changed admission source
identity, requiring refusal before the HTTP listener opens and unchanged
runtime bytes. Package membership, permissions and hashes remain checked
before execution. Runtime and source companion archives are separate from
the documents/evidence bundle.

## D3M and retained NAL work

The [D3M record](../d3m/2026-11-05-airgap-r4c.md) preserves two release-index
conflicts with their original index stages and base/left/right blobs. Both
independent sections survive. A later virtual-base conflict concerned only
a blank separator. The merged NAL refusal path also needed a compilation
fix: the established Series filter already returns a Series, so its extra
`toSeries()` conversion was removed while preserving the copied-node
refusal projection. The corresponding test assertions now use `.view` at
the stdlib `single()` boundary. Both original compile failures remain retained.

Incoming retained NAL work evaluates one source-checked historical English
frame through bounded atomic implication hypotheses. Its frame, source,
occurrence, proposition and evaluator identities remain explicit. It rejects
unsupported or partial frames, retains shared source ancestry, and keeps
NAL evidence coordinates separate from calibrated probability, source truth
and live admission. The historical CLI refuses damaged pack framing before
opening a recovery-capable store; its quiescent-input assumption is explicit.
These are separate capabilities in the integrated repository. They are not
silently added as new engines to the five-node PDF demonstration.

## Retained attempts and limitations

The preliminary browser harness first used an incorrect debug-route path,
then expected the cancellation wording for an ordinary transport failure.
Both failed attempts and their cleanup remain retained. The corrected
harness uses the existing `/api/lcnc/run/debug` route and checks the actual
unknown-outcome/no-retry message. Production UI behavior was not weakened
to satisfy either mistaken assertion.

The complete JDK remains bundled with 23 application dependency jars and the
three application/entry/web jars. Vendor JDK modifications and embedded
native source-build closure remain open. The network and filesystem
boundaries, external verification-host inputs, permission-preserving
extraction and resource interpretation remain those of R4a/R4b. This
increment closes only the named interruption/retry/inspection scenarios;
dense recursive visualization, broader LCNC authoring, arbitrary-write
crash safety and wider deployment environments still require work.
