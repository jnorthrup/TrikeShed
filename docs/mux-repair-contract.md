# ModelMux/KeyMux Repair Contract

Standing rule (repair in place, NEVER delete/rewrite): `modelmux/ModelMux.kt`,
`modelmux/QuotaLegion.kt`, `keymux/KeyMux.kt` are repaired in place. This document is
the behavior contract. Every invariant below is pinned by a mocked test in
`src/commonTest/kotlin/muxcontract/` — those tests are the reconstruction spec. If the
sources are ever mangled, rebuild them until these tests pass again. The tests use
fakes for everything (HTX transport, file operations, reactor, key sources) — no
network, no filesystem, no environment variables, no JVM-only APIs.

Run the whole contract:

    ./gradlew jvmTest --tests 'muxcontract.*' --console=plain

## ModelMux invariants

M1. Key resolution chain (session): provider tag → `llm.<providerTag>.key`;
    else per-model → `llm.<modelId>.key`; else `llm.default.key`; else failure.
    Same chain for `.base_url`, then builder-registered URL, then the OpenAI default.
M2. Metering identity is the binding PATH (`llm.<x>.key`), never the secret value.
    The secret string appears in no metering/ledger/standings key.
M3. Cache identity is the full ContentId (sha256) of the canonical request bytes.
    Two requests that collide under 32-bit String.hashCode get distinct cache
    entries and distinct replies. A cache hit returns the stored payload and does
    NOT reach the transport.
M4. Every chat mints a receipt — success AND failure. A non-2xx response records
    its httpStatus BEFORE returning failure; a 429 exhausts the key in the legion
    (the provider's word outranks the ledger).
M5. The legion meters only real (non-cached) calls, under the M2 identity.
M6. stream() and embed() use the M2 identity for lease release — same defect
    class as M2, fixed at the same time.
M7. The builder constructs a QuotaLegion BY DEFAULT; `.noQuota()` opts out.
M8. quotaStandings projects reactor roster × ledger, usable-first; an exhausted
    key is refused by nextKey; window rollover resets spend.
M9. route() emits ModelSelectionEvent.ModelSelected for the head of a non-empty
    ranking, records it as lastSelection, and an observer that throws never fails
    the route. Empty route emits nothing.
M10. Receipts carry provider-measured cache token counts only (never synthesized).

## KeyMux invariants

K1. First-wins resolution across bindings; `getWithSource` names the source.
K2. Wildcards: resolver matches `*` per segment, exact segment count; top-level
    `pathMatch` is `/`-separated with `:var` segments; list() prefix-matching
    allows trailing query segments.
K3. set() writes to the first matching WRITABLE source, skipping read-only ones
    (env/reactor/fixed are read-only; persist/api accept writes).
K4. PersistSource stores key=value pairs in `keymux.conf` under its root, JSON
    codec by default, in-memory cache invalidated by write/invalidate.
K5. CachedKeySource caches per path for ttlMs, passes writes through and drops
    the entry; invalidate() clears and delegates; evictStale prunes by age.
K6. ReactorSource answers only `llm.<x>.key` paths, ACTIVE keys only, matching
    lastModel or provider, falling back to any ACTIVE key; `llm.default.key`
    means "first ACTIVE".
K7. rotate() promotes the next persist candidate's value into the first writable
    persist source; no candidates → null, current value retained.
K8. activeLeases is a lazy Series2<KeyId, LeaseMetadata> (Join, never Pair);
    indexing it is the only mutation of leaseVisits.
K9. watch() is an explicit emptyFlow stub — no silent flow behavior.
K10. TestKeySource is a test double; production sources never fabricate secrets.
K11. A bare `"*"` binding is the GLOBAL fallback (env/persist/api/reactor/harness
     all bind it) — multi-segment paths like `llm.openai.key` resolve through it.
K12. HarnessSource resolves `llm.<provider>.key` from (in order): process env
     (canonical names), $HERMES_HOME/.env, ~/.hermes/.env, profiles/*/.env,
     ~/.codex/auth.json, opencode auth.json, hermes auth.json (api-key-shaped
     fields only — OAuth accessToken is never extracted as an api key).
K13. `llm.<provider>.base_url`: <ID>_BASE_URL env/dotenv overrides the
     HarnessRegistry default; unknown provider → null, never a fabricated URL.
K14. Custom integrations: `llm.<dash-form-id>.key` resolves
     HERMES_CUSTOM_<ID>_API_KEY (env or hermes .env) with its paired _BASE_URL.
K15. HarnessSource is read-only (write() throws so set() skips it) and degrades
     to env-only when no FileOperations rides the context.
K16. Provider `.default.key` form (jules.default.key) resolves identically to
     the `llm.<provider>.key` form — the daemon seeding path depends on it.

## History of the assault (why this contract exists)

- Metering keyed by the secret value → tagged providers metered under null;
  receipts fell on the floor. Fixed via resolveKeyId (M2).
- 32-bit request hash returned wrong cached payloads on collision (M3).
- QuotaLegion was constructed in tests only; production receipts unrecorded (M7).
- stream()/embed() released leases by secret value — matched nothing, leaked (M6).
- keymuxd/KeyMux.kt was a 93.4%-similar drifted copy; aliased to canonical keymux.
- activeLeases exposed Series<Pair> — pair slop; now Series2 (K8).
- rotate()/listRaw() read keymux.conf with the legacy k=v line codec while write()
  persists JSON — rotation silently found zero candidates (K7 gate caught it).
- FirstWinsResolver required equal segment counts, so a bare "*" binding never
  answered multi-segment paths — the daemon's `KeyMux { env() }` lane was dead for
  every `llm.<provider>.key` lookup (K11).
- Keys existed only as raw process env; Hermes .env/profiles and codex/opencode
  credential stores were invisible to KeyMux (K12–K15, HarnessSource).
