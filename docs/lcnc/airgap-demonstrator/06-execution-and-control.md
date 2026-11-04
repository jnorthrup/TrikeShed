# Execution events clocks pause and rescaffolding

## Event ownership

Attach events to existing run, scope, node, transfer, review and effect boundaries. The event journal records what the host actually admitted or observed. A renderer cannot generate semantic completion. Each event has schema version, run ID, graph definition CID, epoch, producer, producer sequence, causal references, event kind, monotonic timestamp domain and bounded payload references.

Run-wide ordering can be assigned by the journal writer. Producer sequences preserve local order before aggregation. Causal links remain authoritative across producers; wall timestamps are display metadata and cannot establish global causality. Keep an event identity independent of its transport delivery so replayed SSE messages deduplicate.

## Proposed event families

| Family | Examples | Retention |
|---|---|---|
| Admission | run admitted, invocation admitted, budget refused | Durable semantic |
| Execution | node started, batch checkpoint, completed, failed | Durable boundary; batch detail bounded |
| Transfer | output available, delivery admitted, input consumed | Durable for causal path; aggregate payload detail |
| Review | assessment recorded, approval withdrawn, currentness changed | Durable authority |
| Inference | frame assembled, proof returned, result checked, unsupported | Durable result/coverage |
| Production | candidate retained, shadowed, generation activated/suspended | Durable authority |
| Effect | intent retained, dispatched, receipt verified, uncertain outcome | Durable effect |
| Control | pause requested, boundary acknowledged, fork created | Durable command/result |
| Measurement | queue count, CPU time, memory, throughput | Aggregated with interval and loss counters |

Transport a payload CID rather than a complete document on every event. A preview is redacted and bounded by the source's visibility policy. Polling and SSE must expose the same semantic watermark. Browser reconnect supplies the last journal sequence; the host returns a gap/snapshot protocol if that segment is no longer online.

## Four clocks

**Wall clock** supplies human dates and external event times. **Monotonic execution time** measures local elapsed durations. **Logical time** identifies epoch, scope iteration and committed event ordering. **Presentation time** controls replay/animation pace.

Store timer durations in a declared unit. Across processes, transmit measured durations or calibrated clock-domain metadata; do not subtract unrelated monotonic timestamps. The UI shows queue wait, active execution and result retention separately. “Working for 8 seconds” must not mean “queued for 7.9 seconds.”

An engine may have a deadline, a known work-unit total, or only elapsed time. Percentage completion appears only when a meaningful denominator exists. A solver exploring an unknown search space displays steps and budget remaining, not an invented 87%.

## Human pacing

The host executes at its allowed rate. A projection process aggregates observations into 100–250 ms windows initially, while the renderer animates at 30–60 frames per second. The exact window is configurable and benchmarked. Critical failures, pause acknowledgements and authority changes bypass ordinary cosmetic coalescing.

The UI offers live, follow-with-delay, replay and synthetic modes. Live mode shows current counters with brief event animation. Follow-with-delay creates a bounded lag for readability and shows the lag explicitly. Replay reads retained events and can slow down or seek. Synthetic mode uses a fixture generator and has a persistent label.

If the visual queue grows, aggregate more aggressively rather than slowing the semantic engine by accident. When the user requests an execution pace limit, that is a distinct host control affecting admission, with its own receipt.

## Pause semantics

The default button means **stop admitting new work in the selected scope and settle to a checkpoint**. It immediately emits a request acknowledgement, then reports outstanding work until the boundary is reached. Existing CCEK Jobs and Channels remain the lifecycle; the UI's pause state is a projection of the owner's control and completion records.

1. Authenticate and serialize the control request against run ID, epoch and expected control sequence.
2. Stop new admissions in the selected scope. Upstream producers see bounded backpressure or a retained deferred frontier.
3. Let cooperative batches finish. Wait for admitted transfers and child jobs required by the boundary.
4. For noncooperative native work, display that it is still in flight. Offer the declared terminate-and-restart path if necessary; record incomplete attempt and reap descendants.
5. Persist checkpoint references, queue/frontier state, budget counters and journal watermark.
6. Emit boundary reached only when the selected consistency condition is true.

A quiescent whole-run checkpoint has no unaccounted admitted work and no uncommitted effect in that scope. A partial inspection hold may leave other scopes running and must be labeled accordingly. The operator can inspect a moving system, but it is not a globally consistent snapshot.

## Step and continue

Step permits one selected boundary transition or one explicitly bounded batch. A node that spawns multiple children does not turn a click into unlimited work; its declared step granularity is visible. Continue releases the hold for the same immutable definition and epoch. Changing a source or semantic parameter creates a fork.

Commands are idempotent by command ID and checked against the current control sequence. Double-clicking pause or reconnecting a browser does not apply two interventions. A stale command returns the current state and a conflict. The browser is not the sole owner of the paused run; losing the UI does not strand resources indefinitely without a host policy.

## Rescaffolding protocol

The operator edits a copy of the paused definition. Validation computes affected nodes by parameter/wire/source/policy dependencies. Pure completed results can be reused only if their complete input and engine identities match. Non-deterministic model outputs can be replayed as recorded artifacts but are not represented as newly generated results.

Retain a fork manifest with parent checkpoint, old/new graph CIDs, changed fields, reused result CIDs, cancelled/deferred frontier, new budget and effect policy. Allocate a new epoch. Build new owned channels and scopes through existing construction contracts. Do not mutate active workers' graph definitions in place.

For an already committed effect, the continuation references it rather than replaying it. A compensating operation is an explicit separate effect with its own authority. Rewinding the visualization cannot undo a retained publication or external action.

## Scheduling and fairness

Use bounded work queues with separate classes for interactive inspection, foreground analysis and background consolidation. Reserve capacity for control and inspection so a saturated parser lane cannot make pause inaccessible. Start with weighted fair scheduling and per-run quotas; add more complex scheduling only after measurements show a need.

A worker acquires budget before admitting work and accounts for completion, failure or refusal. Budget dimensions include wall time, CPU time where observable, bytes, active tasks, iterations, facts and model tokens. A wall-time cancellation does not reclaim a native process's memory until it actually exits.

Fanout consumes the parent's remaining global budget rather than minting a new full budget per child. A retry uses a new attempt ID and consumes additional budget. Retry eligibility distinguishes transient I/O failure from semantic refusal and deterministic invalid input.

## Durability and backpressure

Critical records are appended through a bounded journal path. When storage cannot retain a required authority/effect event, the relevant action stops with a storage failure. Diagnostic metrics can drop/coalesce, but the drop count and covered time interval are retained. This avoids making instrumentation a hidden unlimited-memory queue.

Snapshot-plus-tail reconstruction must yield the same result as full journal replay. The journal stores actual decisions, scheduling outcomes relevant to semantics and model responses needed for deterministic replay. Bitwise re-execution of every ML/native engine is not promised; replay of retained observations is a separate, testable guarantee.

## Failure and recovery table

| Failure | Required outcome |
|---|---|
| Browser closes | Host work remains owned; reconnect recovers status by run ID |
| Worker dies before result commit | Attempt failed/incomplete; no successful output edge |
| Result committed but acknowledgement lost | Retry finds existing result by idempotency identity |
| Journal disk full | Authority/effect path refused; diagnostic degradation visible |
| Event subscriber falls behind | Gap plus snapshot/watermark; no invented intermediate sparks |
| Source changes during analysis | Historical result retained; current publication check refuses stale authority |
| Process ignores cancellation | Host escalates within adapter policy, records outcome, reaps descendants |
| Crash after effect dispatch | Reconcile sink receipt; uncertain outcome remains explicit until resolved |

## Required observable proof

A recorded demo must pause while a parser or solver is active, show that the request and boundary acknowledgement are different events, fork with a smaller branch budget, resume, and compare retained outputs. Replaying that run must reproduce its causal graph without invoking the engines or effects. A dropped observation segment must be visibly marked, and all terminal semantic receipts must remain recoverable.
