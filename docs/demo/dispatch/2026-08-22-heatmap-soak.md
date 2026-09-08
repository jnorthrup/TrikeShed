# Heat map & soak — activity × technical debt (2026-08-22)

Measured over the last 5 weeks (2,129 commits). Activity = commits / files / automated-arm share / multi-session collisions
(from `logs/oroboros-daemon.log*` collusion signals). Debt = stubs + TODO, commonMain rule violations, hidden tests
(build.gradle.kts `exclude` patterns), ratchet entries (`gradle/js-target-debt.excludes`), duplicated class names.

## Heat map

| zone | activity | debt | heat |
|---|---|---|---|
| `commonMain/userspace` | 111 commits · 142 files · 68 automated | 12k LOC · 113 commonMain violations · 106 stubs · 38 TODO · 54 tests hidden (7 patterns) · 2 ratchet entries | 🔥🔥🔥 |
| `jules` + `flywheel` + `daemon` | 312 commits · 208 automated · FlywheelDriver.kt 148 commits / 98KB / 5 sessions · WorkDrain.kt 159 commits · OroborosDaemon.kt 69 | flywheel 3.5k LOC with 2 tests · crash loop · unbounded retries · autonomous push | 🔥🔥🔥 |
| `htx` + `reactor` + `litebike/taxonomy` | TlsEndpoint / HtxElement 5 sessions, HtxRequest 4 · reactor 38 commits | 17 htx tests hidden · 3-way `enum Protocol` · duplicated TransformCode, HttpMethod, ConnectionState, ElementState, Socks5Command, PeerAddress | 🔥🔥 |
| `forge` + `script.js` | script.js 7 sessions / 29 commits · ForgeApp 29 · index.html 18 | WorkDrain.kt outside `borg.trikeshed` · gallery museum · ForgeBlock / KanbanCard duplicated | 🔥🔥 |
| `parse/confix` + `couch` | parse 56 commits · TypeDefOracle 15 | ConfixKit kid-order bug hidden 5 weeks (ViewServerTest excluded) · JsonSupport lossy · Codec.kt on ratchet · clock shim ×3 | 🔥🔥 |
| `util/oroboros` | moderate, manual | 30 tests hidden (10 files) | 🔥 |
| `classfile` | none | 57 TODO · 58 stubs · 0 tests · slab excluded | ❄ |
| `window` | none | 21 tests hidden | ❄ |
| jvmTest excludes | — | all 4 patterns name deleted files | dust |

Cross-cutting: 122 `@Test` hidden behind 14 commonTest patterns; 34 duplicated class names.

## Soak (tasks 43–50) — sustained, per zone

43. **userspace — the rule becomes the fence.** Move the 113 violating files (NIO/`java.*`/clock in commonMain) to jvmMain or behind `expect`, sub-package by sub-package: `nio/channels`, `nio/process`, `kernel`, `network`. After each move un-exclude its commonTest pattern (network 7, reactor 10, context 14, btrfs 5, uring/ByteRegion 15) and green-or-delete with a reason. Accept per step: gate + compileKotlinJs green; ratchet unchanged or shorter; violation count strictly down.
44. **Dispatcher complex — freeze, fence, test.** `TRIKESHED_FLYWHEEL=off` default (task 16). `FlywheelDriver.kt` behind a `Flywheel` interface whose only members are the three ff-merge sites and the push; split the rest by concern (poll / preflight / drain / settle). Collusion signal → gate: a drain touching FlywheelDriver.kt, OroborosDaemon.kt, script.js, WorkDrain.kt is Quarantined on arrival (task 19 FSM). Accept: ≥10 flywheel tests incl. FSM + gate; FlywheelDriver.kt < 40KB.
45. **WorkDrain.kt comes home.** `src/commonMain/kotlin/forge/doc/WorkDrain.kt` (159 commits, no package owner) → `borg.trikeshed.forge.doc`; first tests; on the collusion gate. Accept: gate green; `git log --follow` intact; one test per public fun.
46. **One `Protocol`.** `litebike/taxonomy/Taxonomy.kt` wins (newest, used by ProtocolDetector); retire `userspace/network/Protocols.kt` and the copy in `reactor/ReactorAlgebra.kt`; then TransformCode, HttpMethod, ConnectionState, ElementState, Socks5Command, PeerAddress (newest wins; others typealias for one release, then deleted). Accept: duplicate-class count 34 → ≤20; HtxParserContractTest + ProtocolDetectorTest green.
47. **Un-hide htx.** Drop `**/htx/**`; 17 tests green against current HtxElement / HtxRequest / TlsEndpoint. Accept: 17/17, pattern removed.
48. **Parser round-trip property.** `JsonSupport.stringify∘parse` identity on strings (quotes/escapes/unicode) and integer-vs-real preserved; reference behaviour = `graal/subvm/Teleport.parseCanonical`. Fix `Json.kt` reify; un-ratchet `parse/json/Codec.kt`. Accept: 1,000-value property test green; ratchet one line shorter.
49. **Un-hide util/oroboros.** 30 tests (coordinator, version gateway, file watcher, ForgeHome, attachments, git/couch gateways, CAS bus, storage, network). Remove pattern; fix or delete per file with reasons; reconcile-NPE fix (task 18) covered by GitCouchGatewayTest. Accept: 30/30 green or explicitly deleted.
50. **Sweep the dust.** Delete the 4 dead jvmTest patterns; `classfile` and `window`: a consumer in 30 days or `docs/attic/` with a dated note. Accept: exclude block ≥4 lines shorter.

Order: 44 → 43 → (46 ∥ 47) → 48 → 45 → 49 → 50.
