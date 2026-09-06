# KeyMux and ModelMux operator surfaces

The daemon serves four separate pages on its existing listener:

- `/keymux`: provider credential presence, observed quota windows, reset times, and the endpoint registry.
- `/modelmux`: model selection, chat, parallel comparison, and parallel comparison followed by synthesis.
- `/mux/sessions`: durable conversations, follow-up turns, forks, branches, receipts, rename, archive, cancellation, and JSON export.
- `/mux/stats`: live attempt ledger, latency, provider token totals, local cache hits, time-window charts, and quota/session projections.

Navigation is also available from the blackboard toolbar. All assets, including the selected Lucide icons, are local; the pages do not depend on a CDN.

## Execution and accounting

The browser refreshes activity, quota, and sessions every two seconds. Provider discovery and endpoint metadata refresh every twenty seconds. Refresh can be paused; hidden tabs suspend refresh. Individual failed sources show an error and preserve the last snapshot. Unmeasured quota limits remain unknown.

Each chat attempt gets a `MuxCallRecord`, including failures before a credential or model can be resolved. A `MuxCallContext` carries numeric conversation and turn IDs through the coroutine tree. Receipts are captured from the individual call, never recovered from the shared `lastReceipt` property. Session attribution survives `LiveBrainClient` rebuilding its model mux.

Local cache hits remain visible in the ledger but do not increment provider token totals. Provider-reported cache reads and writes are shown separately. Quota metering updates are synchronized for parallel completions. No price or monetary cost is inferred.

Chat with no selected model uses the configured BrainClient route, including failover where available. Selecting a model pins that run. Comparison takes two to six explicit models and starts a child conversation for each. Collection keeps successful responses; synthesis makes one additional request through the first selected model. Failed branches remain inspectable. If every branch fails the parent fails; partial results are labeled partial. Four provider lanes and a sixteen-run admission limit bound concurrency. Output limits apply to each response, not to the entire conversation's accumulated spend.

The operator picker uses the same live, provider-qualified catalog as the LCNC `mux.models` node. It remains available when Hermes pins the legacy BrainClient to a single default model. `/api/mux/catalog` exposes that catalog and resolved credential identifiers without modifying the legacy `/api/mux/models` contract.

Cancellation cancels the owning coroutine and its fan-out children. Provider and key access still uses the existing KeyMux -> ModelMux -> HtxKey stack. No additional networking server or provider transport is introduced.

## Persistence and API

Conversations are checkpointed at `<forgeHome>/.modelmux/sessions.json` through the existing `JvmFileOperations.writeAtomically`, which forces the file, atomically replaces it, and forces the parent directory. The existing Couch attachment gateway also projects the snapshot at `modelmux/sessions`; the disk checkpoint restores sessions even when that gateway starts empty. Snapshots contain messages, branch identities, state, and completed attempt receipts. A restart marks previously active sessions interrupted; it does not repeat their provider calls. The store retains up to 250 sessions; a conversation accepts up to 200 messages and 500,000 characters of history. Forks inherit history but start their own usage counters.

`GET /api/mux/activity` returns up to 1,000 recent process/session attempts. Completed session receipts persist with the conversation; the process-wide ledger is bounded memory. Responses expose key identifiers and presence, never credential values or provider error bodies.

| Method | Path | Payload / result |
| --- | --- | --- |
| GET | `/api/mux/sessions` | Session summaries |
| GET | `/api/mux/sessions?id=<id>` | Conversation, branch IDs, receipts |
| POST | `/api/mux/sessions` | `{title?}` creates a conversation |
| POST | `/api/mux/sessions/run` | `{id, prompt, models?, mode?, maxTokens?, temperature?}` returns 202 |
| POST | `/api/mux/sessions/cancel` | `{id}` cancels the active run |
| POST | `/api/mux/sessions/fork` | `{id}` branches completed history |
| POST | `/api/mux/sessions/update` | `{id, title?, archived?}` |
| GET | `/api/mux/activity` | `{atMs, retention, calls}` |

Run modes are `chat`, `compare`, and `synthesize`. The existing `/api/mux/chat` remains available to existing clients and its ModelMux attempts enter process telemetry.

## Boundaries

Endpoint registry entries are declarations in the existing registry, not dynamic runtime bindings. The UI marks entries `Registry only` until their address and model are in the live roster. Enabling a registry entry does not create a credential or change the daemon's egress configuration.

This adds session and routing workflows comparable to the corresponding parts of an agent UI. It does not implement OpenCode's complete agent runtime: shell/file tools, permission prompts, diff review, and token-by-token streaming are not part of these pages. Existing agent jobs and LCNC tools remain in the blackboard.

OpenCode workflow references: [agents](https://opencode.ai/docs/agents), [permissions](https://opencode.ai/docs/permissions/). Icon source: [Lucide](https://lucide.dev/guide/lucide), pinned to 0.468.0; license is in `src/commonMain/resources/web/vendor/lucide-LICENSE.txt`.

## Verification

```sh
./gradlew jvmMainClasses --console=plain
./gradlew jvmTest --tests borg.trikeshed.forge.server.MuxSessionServiceTest --tests borg.trikeshed.forge.server.MuxQuotaConcurrencyTest --tests borg.trikeshed.forge.server.PatchWireTest --tests modelmux.ModelMuxTest --tests modelmux.QuotaLegionTest --console=plain
node --test src/jvmTest/js/mux-core.test.cjs
MUX_BASE_URL=http://127.0.0.1:8897 node src/jvmTest/js/mux-browser.check.cjs
```

The browser check requires Playwright and installed Chrome. It first checks real API routes without spending provider quota, then uses intercepted fixtures to verify populated views, fan-out submission, endpoint editing, safe message rendering, draft preservation, pause/recovery, canvas pixels, and desktop/mobile layout. `MUX_FIXTURES_ONLY=1` skips only the initial live smoke check.
