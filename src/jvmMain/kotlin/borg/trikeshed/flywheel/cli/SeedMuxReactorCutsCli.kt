package borg.trikeshed.flywheel.cli

import borg.trikeshed.jules.JulesCause
import borg.trikeshed.userspace.nio.file.spi.JvmAppendWal
import borg.trikeshed.utils.kanban.JulesBoardStore
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * Seeds the modelmux/keymux Series/Cursor/reactor-alignment cut list as durable
 * Jules work items. Idempotent: entries already present in the queue are skipped.
 *
 * Usage: SeedMuxReactorCutsCli [forgeHome]
 */
data class MuxCut(
    val workId: String,
    val title: String,
    val spec: String,
    val score: Double,
)

fun main(args: Array<String>) = runBlocking {
    val forgeHome = File(args.firstOrNull { !it.startsWith("--") }
        ?: System.getenv("TRIKESHED_HOME")
        ?: File(System.getProperty("user.home"), ".local/forge").path)
    val store = JulesBoardStore(JvmAppendWal(File(forgeHome, JulesBoardStore.WAL_FILENAME)))

    val acceptance = listOf(
        "Acceptance:",
        "./gradlew jvmMainClasses --console=plain",
        "RED first: add the failing test BEFORE the production cut, observe the",
        "failure, then land the fix and re-run to green.",
    ).joinToString("\n")

    val cuts = listOf(
        MuxCut(
            workId = "mux:key-lease-series-view:20260814",
            title = "Keymux: expose active leases as pure Series of (key-id, metadata) pairs",
            score = 0.85,
            spec = """
                Make KeyMux expose active leases as a pure Series of (key-id, metadata)
                pairs instead of a mutable map. Verify that range and projection
                operations stay lazy and that the reactor remains the single writer.

                Context: KeyMux holds leases in mutable state
                (src/commonMain/kotlin/keymux/KeyMux.kt). Reactor discipline wants
                all mutation owned by CCEK elements; views are lazy Series.

                Required production cut:
                1. Lease-view API returning Series<Pair<KeyId, LeaseMetadata>> —
                   lazy, no copying; mutable map stays as private backing.
                2. Laziness verified by a test: take(n) touches <= n underlying
                   entries (instrument the backing with a visit counter).
                3. Single-writer invariant documented and asserted: only the
                   reactor element mutates; the Series view is read-only.

                Files expected:
                src/commonMain/kotlin/keymux/KeyMux.kt
                src/jvmTest/kotlin/keymux/KeyMuxLeaseSeriesTest.kt (new)

                ${acceptance}
            """.trimIndent(),
        ),
        MuxCut(
            workId = "mux:model-catalog-cursor:20260814",
            title = "Modelmux: model catalog (provider, free-tier, quota, latency) as Cursor",
            score = 0.85,
            spec = """
                Represent the current model catalog (provider, free-tier flag, quota
                remaining, latency estimate) as a Cursor. Cache hits/misses become
                projections over that Cursor rather than side-effecting lookups.

                Context: ModelMux (src/commonMain/kotlin/modelmux/ModelMux.kt)
                resolves provider sessions from KeyMux and calls /chat/completions.
                There is no unified catalog view.

                Required production cut:
                1. ModelCatalogEntry(provider, model, freeTier, quotaRemaining,
                   latencyEstimateMs).
                2. Catalog exposed as a Cursor<ModelCatalogEntry> per PRELOAD.md
                   algebra; advance is lazy, no eager materialization.
                3. Cache hit/miss computed as pure projections (filter/map) over
                   the Cursor, not side-effecting map lookups.
                4. Request path unchanged in this cut — view + tests only.

                Files expected:
                src/commonMain/kotlin/modelmux/ModelCatalog.kt (new)
                src/jvmTest/kotlin/modelmux/ModelCatalogCursorTest.kt (new)

                ${acceptance}
            """.trimIndent(),
        ),
        MuxCut(
            workId = "mux:causal-routing-rule:20260814",
            title = "Causal routing rule skeleton (Rete-style production under reactor ownership)",
            score = 0.8,
            spec = """
                Add a minimal Rete-style production that fires when a model request
                arrives: "if free-tier quota > threshold and latency estimate < bound
                -> prefer that provider." Keep the rule declarative and under the
                reactor's ownership.

                Required production cut:
                1. Declarative rule object: condition set over catalog entries
                   (quotaRemaining > threshold, latencyEstimateMs < bound), action =
                   rank preference. No imperative branching in the rule body.
                2. Fires inside the reactor element that owns model requests —
                   never on a caller thread.
                3. Rule evaluation is a pure function of (rule, catalog, request);
                   deterministic and unit-testable without a live reactor.

                Files expected:
                src/commonMain/kotlin/modelmux/CausalRoutingRule.kt (new)
                src/jvmTest/kotlin/modelmux/CausalRoutingRuleTest.kt (new)

                ${acceptance}
            """.trimIndent(),
        ),
        MuxCut(
            workId = "mux:omniroute-strategy-join:20260814",
            title = "OmniRoute routing strategies (priority/weighted/cost/rr/auto) as Join algebra",
            score = 0.8,
            spec = """
                Encode the core OmniRoute routing strategies (priority, weighted,
                cost-optimized, round-robin, auto) as a small Join-based algebra so
                strategy selection itself stays composable and testable.

                Context: PRELOAD.md defines Join<A,B> as the kernel composition
                operator. Strategy selection should compose, not branch.

                Required production cut:
                1. RoutingStrategy sealed hierarchy of the five strategies; each
                   strategy is a pure ranking function over candidates.
                2. Selection encoded as Join composition:
                   Join(strategyA, strategyB) applies A then B; must be associative
                   in effect with an identity element (pass-through).
                3. Pure functions only — strategies read health/latency inputs,
                   they never write them.

                Files expected:
                src/commonMain/kotlin/modelmux/RoutingStrategy.kt (new)
                src/jvmTest/kotlin/modelmux/RoutingStrategyJoinTest.kt (new)

                ${acceptance}
            """.trimIndent(),
        ),
        MuxCut(
            workId = "mux:free-tier-pool-projection:20260814",
            title = "Keymux: free-tier pool projection consumed by auto-fallback without materializing",
            score = 0.8,
            spec = """
                Project the key Series into a free-tier-only view. Auto-fallback
                logic should be able to switch to the next free key without
                materializing the whole pool.

                Required production cut:
                1. Free-tier projection as a lazy Series filter over the key
                   lease Series (depends on mux:key-lease-series-view).
                2. nextFreeKey(excluding) advances the projection cursor only as
                   far as needed — verify with a visit-counter test that skipping
                   one exhausted key touches O(1) entries, not the pool.
                3. No intermediate List materialization in the fallback path.

                Files expected:
                src/commonMain/kotlin/keymux/KeyMux.kt
                src/jvmTest/kotlin/keymux/FreeTierPoolProjectionTest.kt (new)

                ${acceptance}
            """.trimIndent(),
        ),
        MuxCut(
            workId = "mux:quota-telemetry-couch:20260814",
            title = "Modelmux: quota/spend telemetry as append-only Couch-style projection",
            score = 0.75,
            spec = """
                Store per-provider quota and spend snapshots in a benign Couch-style
                projection (append-only, content-addressable). The reactor reads the
                projection; nothing else writes it.

                Context: CouchIndexBridge and the CAS stack
                (src/commonMain/kotlin/borg/trikeshed/couch/) provide the
                append-only content-addressed store pattern.

                Required production cut:
                1. QuotaSnapshot(provider, windowStart, quotaRemaining, spent)
                   appended after each reactor-owned request completes.
                2. Snapshot bodies content-addressed (ContentId) and append-only;
                   no in-place updates, no deletes.
                3. Read path: reactor and projections read the latest snapshot
                   per provider by cursor; external writers forbidden (assert in
                   test that the write API is reactor-internal).

                Files expected:
                src/commonMain/kotlin/modelmux/QuotaTelemetry.kt (new)
                src/jvmTest/kotlin/modelmux/QuotaTelemetryCouchTest.kt (new)

                ${acceptance}
            """.trimIndent(),
        ),
        MuxCut(
            workId = "mux:python-key-refresh-hook:20260814",
            title = "Pointcut-friendly Python key refresh hook (observable auth.json watch)",
            score = 0.7,
            spec = """
                Provide a narrow, observable surface so a Python-side refresh of keys
                (or a simple auth.json watch) can be noticed by the reactor almost as
                a native event, without forcing a full process boundary.

                Required production cut:
                1. A file-watch element (CCEK) on the KeyMux auth file path that
                   emits a single causal event on mtime/content change.
                2. Event shape: KeysRefreshed(source, contentId) — content-addressed
                   so downstream projections can dedupe.
                3. The reactor reloads keys from KeyMux on the event; Python needs
                   nothing beyond writing the file. No polling loop, no subprocess.

                Files expected:
                src/jvmMain/kotlin/borg/trikeshed/keymuxd/AuthFileWatchElement.kt (new)
                src/jvmTest/kotlin/borg/trikeshed/keymuxd/AuthFileWatchElementTest.kt (new)

                ${acceptance}
            """.trimIndent(),
        ),
        MuxCut(
            workId = "mux:modelmux-lifecycle-ownership:20260814",
            title = "Oroboros ownership of modelmux open/close lifecycle inside MuxReactorElement",
            score = 0.85,
            spec = """
                Ensure modelmux lifecycle (open -> active -> draining -> closed)
                lives entirely inside the MuxReactorElement, matching the Oroboros
                "one event loop, everything else suspended" discipline.

                Context: AGENTS.md reactor rules — no blocking calls in coroutine
                scope; close() happens in finally of mainImpl, not signal handlers.

                Required production cut:
                1. Lifecycle states as an explicit FSM inside the mux reactor
                   element; transitions emitted as causal events.
                2. close() drains in-flight requests under cancellation, then
                   closes transports — all inside the element's coroutine scope.
                3. No lifecycle mutation from outside the element; document the
                   single-owner invariant on the element.

                Files expected:
                src/jvmMain/kotlin/borg/trikeshed/reactor/MuxReactorElement.kt (new
                or the existing mux element if one already exists)
                src/jvmTest/kotlin/borg/trikeshed/reactor/MuxReactorLifecycleTest.kt (new)

                ${acceptance}
            """.trimIndent(),
        ),
        MuxCut(
            workId = "mux:auto-fallback-rule:20260814",
            title = "Auto-fallback production rule: 429/quota-exhausted -> next candidate, same lease",
            score = 0.85,
            spec = """
                Wire a causal rule that, on a 429 or quota-exhausted signal,
                produces the next candidate model from the current Cursor
                projection and re-issues the request under the same lease.

                Context: KeyMux already rotates credentials on 429 (quota
                containment) at the transport layer; this cut generalizes it to
                model-level fallback as a reactor-owned production rule.

                Required production cut:
                1. Rule input: (429|QuotaExhausted signal, current Cursor
                   projection, lease id). Output: fallback request descriptor.
                2. Re-issue under the SAME lease — lease identity survives
                   fallback; assert in test.
                3. Candidate order from the Cursor projection (free-tier first
                   when available); no full-pool materialization.
                4. Bounded retries (constant) to avoid infinite fallback loops.

                Files expected:
                src/commonMain/kotlin/modelmux/AutoFallbackRule.kt (new)
                src/jvmTest/kotlin/modelmux/AutoFallbackRuleTest.kt (new)

                ${acceptance}
            """.trimIndent(),
        ),
        MuxCut(
            workId = "mux:compression-hint-projection:20260814",
            title = "Lazy token-compression hint Series consulted by modelmux before a call",
            score = 0.65,
            spec = """
                Expose a Series of token-compression opportunities that modelmux can
                consult before a call; keep it a pure projection so callers may
                ignore it.

                Required production cut:
                1. CompressionHint(contextSha, technique, estimatedTokenSaving)
                   as a lazy Series projection over recent request contexts.
                2. Consulted (never forced) before a call: if the projection's
                   first element clears a saving threshold, apply; else call
                   through unchanged.
                3. Pure projection: no side effects, callers may ignore; asserted
                   by a test that runs the request path with the projection
                   present but unused and observes identical wire behavior.

                Files expected:
                src/commonMain/kotlin/modelmux/CompressionHint.kt (new)
                src/jvmTest/kotlin/modelmux/CompressionHintProjectionTest.kt (new)

                ${acceptance}
            """.trimIndent(),
        ),
        MuxCut(
            workId = "mux:lease-expiry-causal-event:20260814",
            title = "Keymux lease expiry/revocation as a single causal event (no polling)",
            score = 0.8,
            spec = """
                When a lease expires or is revoked, emit a single causal event that
                both keymux and any dependent Kanban/FSM projections can observe,
                rather than polling.

                Required production cut:
                1. LeaseExpired(leaseId, reason: Expired|Revoked, at) causal event
                   emitted once per terminal lease transition.
                2. Reactor-scheduled expiry check (element-owned timer), not a
                   poller thread; exactly-once emission asserted in test.
                3. Dependent projections (Kanban/FSM) subscribe to the event
                   stream; no projection re-derives expiry by reading clocks.

                Files expected:
                src/commonMain/kotlin/keymux/KeyMux.kt
                src/jvmTest/kotlin/keymux/LeaseExpiryCausalEventTest.kt (new)

                ${acceptance}
            """.trimIndent(),
        ),
        MuxCut(
            workId = "mux:provider-health-series:20260814",
            title = "Modelmux: provider health Series (success rate, latency) — reactor-written only",
            score = 0.8,
            spec = """
                Maintain a live Series of provider health (success rate, recent
                latency) that is updated only by the reactor. Routing strategies
                read it; nothing else mutates it.

                Required production cut:
                1. ProviderHealth(provider, successRate, recentLatencyMs,
                   updatedAt) as a Series exposed by the request-loop element.
                2. Updated by the reactor after each call; write path not
                   reachable from outside the element (assert in test).
                3. Routing strategies (RoutingStrategy.kt) consume it as a pure
                   input projection.

                Files expected:
                src/commonMain/kotlin/modelmux/ProviderHealth.kt (new)
                src/jvmTest/kotlin/modelmux/ProviderHealthSeriesTest.kt (new)

                ${acceptance}
            """.trimIndent(),
        ),
        MuxCut(
            workId = "mux:key-snapshot-couch-recovery:20260814",
            title = "Couch-layered key snapshot on reactor drain for restart recovery",
            score = 0.7,
            spec = """
                On reactor drain, snapshot the current key Series into a Couch
                projection so a restarted Oroboros process can restore leases
                without external side channels.

                Required production cut:
                1. On drain (element close path), append a content-addressed
                   snapshot of the lease Series to the Couch projection
                   (companion to mux:quota-telemetry-couch).
                2. On startup, restore leases from the latest snapshot by
                   ContentId; replay is idempotent (same ContentId -> no-op).
                3. No side channels: no temp files, no external KV; the Couch
                   projection is the only recovery source.

                Files expected:
                src/jvmMain/kotlin/borg/trikeshed/keymuxd/KeySnapshotRecovery.kt (new)
                src/jvmTest/kotlin/borg/trikeshed/keymuxd/KeySnapshotRecoveryTest.kt (new)

                ${acceptance}
            """.trimIndent(),
        ),
        MuxCut(
            workId = "mux:model-selection-event:20260814",
            title = "Python-observable model selection event (pointcut-style, no reactor parsing)",
            score = 0.7,
            spec = """
                When modelmux finally chooses a provider/model, emit a lightweight
                event that a pointcut-style observer (including a Python guest) can
                see without needing to parse internal reactor state.

                Required production cut:
                1. ModelSelected(provider, model, strategy, requestId, at) causal
                   event emitted at selection time.
                2. Serialized to the observer surface as a single JSON line on the
                   forge event stream (append-only file or socket the Python guest
                   already tails).
                3. Internal reactor state stays opaque: the event carries
                   everything an observer needs; no parsing of reactor internals.

                Files expected:
                src/commonMain/kotlin/modelmux/ModelSelectionEvent.kt (new)
                src/jvmTest/kotlin/modelmux/ModelSelectionEventTest.kt (new)

                ${acceptance}
            """.trimIndent(),
        ),
        MuxCut(
            workId = "mux:e2e-free-tier-combo:20260814",
            title = "E2E free-tier auto-combo: two free keys + one paid, reactor sole owner",
            score = 0.9,
            spec = """
                Jules verification task: given two free-tier keys and one paid key,
                a sequence of requests should prefer free tiers, fall back only when
                quota is exhausted, keep all state as Series/Cursor projections,
                and leave the reactor as the sole owner of both keymux and modelmux.

                Scope: this is the integration test that verifies the mux cut list
                (parent gap:mux-reactor-alignment). It should be attempted AFTER the
                prerequisite cuts land; if they have not landed yet, leave this RED
                with a clear skip/assumption note naming the missing pieces.

                Scenario (fake transports, no live network):
                1. Seed two free-tier keys (quota A=2, B=3) and one paid key.
                2. Issue 6 requests: first 5 must route to free tiers (A then B
                   as each exhausts), 6th falls back to paid.
                3. Assert every routing decision is derivable from Series/Cursor
                   projections (no hidden mutable map consulted).
                4. Assert single-writer: all mutations observed through the
                   reactor's causal event stream, in order.

                Files expected:
                src/jvmTest/kotlin/modelmux/FreeTierAutoComboE2ETest.kt (new)

                ${acceptance}
            """.trimIndent(),
        ),
    )

    val existing = store.loadQueue().map { it.workId }.toSet()
    var appended = 0
    for (cut in cuts) {
        if (cut.workId in existing) {
            println("[SEED] SKIP (already queued): ${cut.workId}")
            continue
        }
        store.appendWork(cut.workId, JulesCause.WorkQueued(
            workId = cut.workId,
            tier = "forge",
            title = cut.title,
            spec = cut.spec,
            parent = "gap:mux-reactor-alignment",
            score = cut.score,
            at = System.currentTimeMillis(),
        ))
        appended++
    }
    println("[SEED] appended $appended of ${cuts.size} cuts (queue had ${existing.size} entries)")
}
