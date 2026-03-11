# WAM CCEK Channelization Recovery

This note recovers the old "endgame" and composition corpus and restates it against the current TrikeShed codebase.

The target is not to revive v2superbikeshed whole. The target is to extract the parts that still help TrikeShed channelize a WAM-like production system, where a Prolog-style search can be projected either as a bounded graph/job search or as an answer stream.

## Recovery Set

### Endgame docs

- `/Users/jim/work/old/v2superbikeshed/KMP_ENDGAME_ARCHITECTURE.md`
- `/Users/jim/work/old/v2superbikeshed/docs/ENDGAME_CCEQ_CHANNELIZATION_SPEC.md`
- `/Users/jim/work/old/v2superbikeshed/ENDGAME_ARCHITECTURE_RESECTION.md`

### Composition docs

- `/Users/jim/work/old/v2superbikeshed/COMPOSITIONAL_CONTEXT_PATTERNS.md`
- `/Users/jim/work/old/v2superbikeshed/SIMPLE_CONTEXT_COMPOSITION.md`
- `/Users/jim/work/old/v2superbikeshed/trikeshed-ccek/META_COMPOSITION_ARCHITECTURE.md`
- `/Users/jim/work/old/v2superbikeshed/trikeshed-ccek/src/commonMain/kotlin/borg/trikeshed/ccek/ContextCompositionBehavior.kt`
- `/Users/jim/work/old/v2superbikeshed/trikeshed-ccek/src/commonMain/kotlin/borg/trikeshed/ccek/MetaCompositionPatterns.kt`
- `/Users/jim/work/old/v2superbikeshed/CCEK_KEYEDSERVICES_ARCHITECTURE.md`

### WAM / Prolog / channelization docs

- `/Users/jim/work/old/v2superbikeshed/docs/.claude/agents/nars-prolog-wam-converter.md`
- `/Users/jim/work/old/v2superbikeshed/tools/wam-codegen`
- `/Users/jim/work/old/v2superbikeshed/docs/tools/wam-codegen`
- `/Users/jim/work/old/v2superbikeshed/docs/samples/channelization-min/wam`
- `/Users/jim/work/old/v2superbikeshed/KEY_COMPOSITION_MERMAID.md`
- `/Users/jim/work/old/v2superbikeshed/MAIN_KEY_COMPOSITION_GRAPH.md`

## Current TrikeShed Ground Truth

These are the live surfaces that matter now:

- `src/commonMain/kotlin/borg/trikeshed/ccek/KeyedService.kt`
- `src/commonMain/kotlin/borg/trikeshed/ccek/CcekScope.kt`
- `src/commonMain/kotlin/borg/trikeshed/ccek/transport/StreamTransport.kt`
- `src/commonMain/kotlin/borg/trikeshed/context/HandlerRegistry.kt`
- `src/commonMain/kotlin/borg/trikeshed/net/channelization/Channelization.kt`
- `src/commonMain/kotlin/borg/trikeshed/net/channelization/ChannelGraph.kt`
- `src/commonMain/kotlin/borg/trikeshed/net/channelization/ChannelSession.kt`
- `src/commonMain/kotlin/borg/trikeshed/net/channelization/ChannelExchange.kt`
- `src/commonMain/kotlin/borg/trikeshed/net/channelization/HttpLikeSessionProof.kt`

The conductor truth already sharpens the interpretation:

- `conductor/tracks/ccek-keyed-services_20260309/plan.md`
- `conductor/tracks/relaxfactory-literbike-arrangement_20260309/plan.md`
- `conductor/tracks/relaxfactory-literbike-arrangement_20260309/arrangement.md`

Those files already reject CCEK as transport owner and keep the old composition corpus only as scripting or coverage grammar.

## What Survives

### 1. CCEK stays minimal

The winning reading from the archive is the simple one:

- CCEK means typed `CoroutineContext.Key`-based capability injection.
- `KeyedService` plus `coroutineService(...)` is the correct center.
- Small focused services beat monolithic "meta context" stacks.

Use the following old docs as support for that reading:

- `SIMPLE_CONTEXT_COMPOSITION.md`
- `COMPOSITIONAL_CONTEXT_PATTERNS.md`
- `CCEK_KEYEDSERVICES_ARCHITECTURE.md`

### 2. Composition grammar survives as scenario language

The useful part of the old meta-composition material is not the runtime architecture claim. It is the vocabulary for:

- service replacement
- service validation
- staged state or pipeline descriptions
- bounded orchestration scenarios

Use the following as grammar only:

- `ContextCompositionBehavior.kt`
- `MetaCompositionPatterns.kt`
- `META_COMPOSITION_ARCHITECTURE.md`

### 3. Platform-aware endgame survives

The real surviving endgame claim is:

- common semantic layer
- platform-specific reactor/backend floor
- no fake universal kernel abstraction above everything

That aligns with current TrikeShed channelization:

- choose a light path in `Channelization.kt`
- keep transport capability carriers in CCEK
- keep graph/session/block execution above transport details

### 4. WAM belongs in tables plus channels, not in CCEK

The old WAM and Prolog materials are still useful if interpreted this way:

- WAM search operators become compile-time tables, registries, and typed handler lookups
- runtime search steps become graph facts, jobs, sessions, and blocks
- answers can be projected either as a finite result set or as an open stream

That means WAM is a protocol or execution grammar layered on top of:

- `HandlerRegistry`
- `ChannelGraph`
- `ChannelJob`
- `ChannelSession`
- `ChannelBlock`
- `ChannelEnvelope`

## What Does Not Survive

Do not revive these as architecture owners:

- monolithic `CcekContext` or four-field forced context shells
- string-keyed context maps
- QUIC or reactor ownership inside CCEK
- "kernel database" and eBPF stored-procedure theater as accepted architecture
- giant phase stacks that restate the ask without proving code

The archive can still be mined for nouns, not for automatic authority.

## Codified Projection: WAM as Search or Stream

The clean TrikeShed formulation is:

### CCEK layer

CCEK only supplies installed capabilities.

Examples:

- `WamTableService`
- `SearchStrategyService`
- `ProofSinkService`
- `ClauseStoreService`
- `StreamTransport`

These are keyed services, not runtime owners.

### Context/registry layer

`HandlerRegistry` owns dynamic lookup of handlers by:

- functor
- opcode
- protocol
- clause family
- continuation form

This is the right home for WAM opcode-to-handler overlays and goal-family routing.

### Channelization layer

Channelization owns execution shape:

- `ChannelGraph` holds facts about goals, choicepoints, continuations, and resource availability
- `ChannelJob` represents one search step, reduction, unification pass, or continuation wakeup
- `ChannelSession` owns one query or one proof session
- `ChannelBlock` carries one term, clause packet, trail delta, or answer chunk
- `ChannelEnvelope` adds routing metadata so the same proof can be emitted as byte-stream, message-stream, or local in-memory exchange

### Search projection

For a Prolog-style search:

- initial query becomes one `SessionFact`
- each active goal becomes a `GraphFact.CustomFact("goal", ...)`
- each choicepoint becomes a job activation rule or dependency fact
- each continuation step becomes a `ChannelJob`
- each emitted answer becomes a `ChannelBlock`

### Stream projection

For a stream-style answer path:

- a proof session stays active while answers continue
- each answer chunk is a `ChannelEnvelope`
- finite proofs terminate the session
- infinite or watch-style proofs remain a live stream transport concern

## Suggested TrikeShed Ownership

### CCEK owns

- typed capability lookup
- bounded service installation
- strategy hints
- transport capability carriers

### Channelization owns

- session lifecycle
- graph facts
- job activation
- block exchange
- stream projection

### WAM layer should own

- clause tables
- opcode/functor registries
- search-state encoding
- answer projection rules

WAM should not own transport details, and CCEK should not own proof execution semantics.

## Minimal Production Shape

If this is implemented in TrikeShed, the smallest sane shape is:

1. Define a typed WAM table surface.
   - one service key for immutable clause or opcode tables
   - one registry overlay for handler lookup

2. Model a query as a channel session.
   - one `ChannelSessionId` per query
   - one graph for active goals and choicepoints

3. Represent search steps as jobs.
   - unification
   - clause selection
   - continuation scheduling
   - answer emission

4. Represent answer flow as blocks.
   - one `ChannelBlock` per answer or delta
   - message-stream for structured proof exchange
   - byte-stream only at explicit protocol boundaries

5. Keep platform backends below this layer.
   - NIO baseline
   - io_uring or kqueue only as backend choice
   - never let backend details redefine the search model

## Near-Term Docification Targets

The next concrete docs that would make this operational are:

- `conductor/WAM_TABLE_SCHEMA.md`
  - typed table schema for predicates, clauses, opcodes, and handler overlays
- `conductor/WAM_CHANNEL_SESSION_MODEL.md`
  - how a query, choicepoint, continuation, and answer map onto `ChannelGraph`, `ChannelJob`, and `ChannelBlock`
- `conductor/WAM_STREAM_PROOF.md`
  - one worked example showing a Prolog-style search projected as a live answer stream

## Bottom Line

The old endgame and composition docs are recoverable, but only after a hard reduction:

- keep CCEK as minimal typed keyed services
- keep composition docs as grammar and scenario vocabulary
- keep endgame as platform-aware backend separation
- place WAM search semantics on the current channelization graph/session/block surfaces

That gives TrikeShed a credible route to a production WAM-like system without re-importing the old overgrown CCEK architecture.
