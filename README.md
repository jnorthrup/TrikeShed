
<img width="1536" height="2752" alt="image" src="https://github.com/user-attachments/assets/71d3a139-1f0b-40d4-8e4c-ad0e1252dd7f" />

 
# TrikeShed  


## tldr 
this is the backbone of the json scanner and the fast-enough single-threaded database within trappings.
```kotlin 
interface Join<A, B> {    val a: A;   val b: B;
    operator fun component1(): A = a//destructuring 1&2
    operator fun component2(): B = b
    val pair: Pair<A, B> get() = Pair(a, b); ...}

typealias Twin<T> = Join<T, T>
typealias Series<T> = Join<Int, (Int) -> T>
val <T> Series<T>.size: Int get() = a
/** index operator for Series*/
operator fun <T> Series<T>.get(i: Int): T = b(i)
[...] dozens of mix-ins and specializations

typealias JsElement = Join<Twin<Int>, Series<Int>> //(openIdx j closeIdx) j commaIdxs
typealias JsIndex = Join<Twin<Int>, Series<Char>> //(element j src)
typealias JsContext = Join<JsElement, Series<Char>> //(element j src)
typealias JsPathElement = Either<String, Int>
typealias JsPath = Series<JsPathElement>

typealias RowVec = Series2<Any, () -> RecordMeta>
/** Cursors are a columnar abstraction composed of Series of Joined value+meta pairs (RecordMeta) */
typealias Cursor = Series<RowVec>
 ```

---

## Run it in anger — please

The best thing you can do for this project is point it at your **real** mess and
tell us where it creaks. Not a polite demo folder — the 2GB Telegram export,
the directory with spaces and emoji in the names, the repo with 40k files.
That's the workload it's built for, and every place it stumbles is a bug we
want to hear about.

**Quickstart (five minutes, one port):**

```bash
git clone git@github.com:jnorthrup/TrikeShed.git && cd TrikeShed
./gradlew runOroborosDaemon --args='--watch'  # builds the live feed and launches with HotSwapAgent (JDK 25)
```

Then open **http://localhost:8888** and poke it where it counts:

| Surface | What to try in anger |
|---|---|
| `/graal` | **Drag a real folder onto the terrain.** It becomes its own project database — cloned into the forge home (APFS/btrfs reflink, so it costs nothing), every file a CAS-backed attachment, a new territory on the map. Wheel-dive to any single byte of it. |
| `POST /api/lcnc/run` | Run a stored LCNC program in the daemon — `{"program":"preset-scope"}` executes a concentric scope call (bind → run → gather) and returns its `scope.out` values. `GET /api/lcnc/contracts` is the palette. |
| `/` | The board. Make cards, move them, kill the daemon mid-flight, restart it, and check that nothing you did was lost. (That's a promise we test; hold us to it.) |

**When it breaks — and somewhere it will — that's the good part:**

- 🐛 [It broke](https://github.com/jnorthrup/TrikeShed/issues/new?labels=quickstart-feedback,bug&title=%5Bquickstart%5D%20&body=**What%20I%20did:**%0A%0A**What%20happened:**%0A%0A**What%20I%20expected:**%0A%0A**Log%20tail%20(logs/oroboros-daemon.log):**%0A%60%60%60%0A%0A%60%60%60) — stack traces, log tails, and "it just sat there" reports are all equally welcome.
- 🧗 [It fought me](https://github.com/jnorthrup/TrikeShed/issues/new?labels=quickstart-feedback,friction&title=%5Bfriction%5D%20&body=**Where%20I%20got%20stuck:**%0A%0A**What%20I%20tried:**%0A%0A**What%20would%20have%20helped:**) — confusing moments count as defects here. If you had to read source to proceed, that's an issue.
- 💡 [It should also…](https://github.com/jnorthrup/TrikeShed/issues/new?labels=quickstart-feedback,idea&title=%5Bidea%5D%20&body=**The%20workload%20I%20care%20about:**%0A%0A**What%27s%20missing:**) — tell us about the workload you *wanted* to throw at it.

No issue is too small and none needs polish — two angry sentences and a log
line beat a week of silence. The Gradle daemon task forwards its output to
the terminal; the last thirty lines plus what you
dragged, clicked, or expected is a perfect report.

## Panels: retained party simulations

Medical and legal tuple-review results offer **Load retained simulation for
this receipt (no execution)**. The construction pins the curation receipt,
loads its canonical Stanford rule candidates through `document.sheet`, and
feeds the selected page to the existing Rete evaluator. Explicitly wired JSON
assumptions are carried over; otherwise the list starts empty. Edit `subject`
and `obj` (party identity), then Run. Save the construction to reopen the same
evidence without relying on transient canvas outputs or calling NLP/a model
again.

In the concentric evidence sheet, navigate to **Rule candidates**, choose index
groups or another row page, then **Load simulation from this rules page** (or
the AI deliberation action). This pins the visible page's receipt, offset,
page size and index selections, not the original node output. New assumptions
start empty for explicit editing. A changed sheet or canvas refuses an in-flight
load; neither browsing nor loading runs curation, inference or a model.

This is page-scoped: inspect `sourceOrdinals`, `selectedRows`, `rowCount`,
`pageSize` and `next`, and choose offset, limit or indexed groups explicitly.
An empty assumption list produces no firings. Positive and negative support
keep their rule/source identities and remain dependent hypothetical evidence,
not observations, votes, approval, or clinical/legal conclusions. The run
receipt records the consumed curation CID. No live rule admission or actuation
is wired by this construction.

The `nars.reteFire` result has a read-only simulation view: inspect assumptions,
filter firings by party/object, and page through 16 firings at a time. Positive
and negative milli-evidence remain separate, with dependence and floor status.
Expand a firing's source to see rule/source/firing IDs and every matching
retained quotation candidate, including its sentence and offsets. Missing
provenance is explicit; no quotation is inferred. Raw copy/drag values remain
available. Filtering or opening evidence does not execute the construction.

The rules-page and curation-result actions also offer **Load language-authored
document route (no execution)**. Select Kotlin common, Java, Clojure, JavaScript,
Groovy, Ruby or Python; availability is checked before replacing the canvas.
The language facet offers the same construction with a blank receipt. Review
the page, assumptions and OWN-trust source before Run. A scope packages the
receipt CID, returned page `selection` and original Rete `evaluation`; the
language authors a Confix Camel route while Camel carries that payload unchanged.
The default route sets the `CurationReceipt` header and chooses a local review
log, or a separate empty-firings log. Empty firings are not proof of absence;
neither route means approval. Source quotations are included in the local log,
so use synthetic records. No parser/model request, live admission, board task or
external endpoint is wired. `document.sheet.selection` contains the same page
identity, offset, size, source ordinals, axes and group selections shown in the
sheet, without duplicating its cells or index groups. Save the construction to
retain its authored source and page parameters; normal run receipts retain the
executed evidence, route and invocation ledger.

Collapsing a scope hides its interior while keeping its public ports and cables
visible. Named ports come from its immediate `scope.in` / `scope.out` children;
editing their name or kind updates the ring without silently renaming cables.
Nested formals remain local to their own ring. Expand to inspect the child nodes;
Enter or Space also toggles the focused collapse control. Folding changes
presentation only, not execution. A `scope.in` default is absent until **Use
default** is checked; checking it with an empty field explicitly supplies empty
text. Unchecking it removes that default. Loading or adding a formal does not
silently make a required binding optional.
The Evidence facet identifies interpreter-owned scope operations separately
from registry runners. A binding identifies dispatch ownership, not proof that
inputs, services or a named scope body are ready.

The result also offers **Load AI deliberation for this receipt (no execution)**.
It preserves the returned rules-page selection and explicitly wired assumptions,
then exposes the evidence and simulation prompt templates on the canvas. Three
existing `council.seat` nodes receive that same prompt as separate simulated
party-a, party-b and reviewer perspectives. Inspect their system charges, model
selection and token limits before Run. Use synthetic records: Run sends the
selected quotations and hypotheses to the configured model provider.

Turn records retain actual model identities and refusals; the normal run receipt
retains the consumed curation CID and node outputs. Agreement is not independent
evidence. Responses remain proposals, never rule admission, a council ruling,
task creation, clinical/legal conclusions or external actuation. Retained
simulation and document-quality task review remain separate constructions that
make no new model request.

In **Evidence → Recorded node outputs**, a successful seat response offers
**Load review task … (no execution)**. This separate construction pins the exact
run receipt and seat node. Write a review question, then Run to create an ordinary
local TODO card. Blank questions, refused/omitted responses, missing or mismatched
source content, and receipts not matching the board's persisted run command are
rejected. Repeating the same run/node/question returns the same card without
overwriting later card state; a different question creates a distinct card.

The card retains the frozen program, original model response, curation receipt
and operator question as content-addressed data. Neither model text nor the
question becomes `AGENT`, `MODELS` or other job directives. Creation does not
claim the card, run NLP/a model, admit rules or perform an external action.
The question records operator intent, not authenticated identity or medical/legal
approval. Ordinary board gestures govern subsequent work.

In Kanban, expand **Review document task (read-only)** on the created card to
reopen its question, model response and source identities without replaying the
construction. Links retain the original run, frozen program and curation receipt.
Inspection validates the retained content hashes, reports whether the source
version is still current, and never offers source correction for a model proposal.

## Panels: dense Couch compression

The **Confix & facets** source view indexes explicit, bounded Couch revision
batches into a separate ISAM projection. Its optional **Zstd dictionary CID**
selects a compression dictionary for that batch only; blank uses the portable
dictionary-free codec. This is distinct from dictionary-coded column values.
The JVM daemon supplies the native dictionary codec; other hosts must supply a
codec before offering this option. There is no silent fallback.

Publish trained Zstd or raw-content dictionary bytes through the existing Couch
CAS route (`POST /trikeshed/_cas`, raw binary body), then paste its returned
`sha256:…` CID into the source view. The equivalent workspace request is
`PUT /api/lcnc/workspace` with
`{"action":"index-couch","limit":32,"dictionaryCid":"sha256:…"}`.
The import receipt reports the selected dictionary; each leaf file records its
own CID. Reopening resolves historical dictionaries from the same CAS, even
when a later batch uses another dictionary or none. The receipt's `through`
sequence covers only the durable indexed prefix, not the unindexed source tail;
it is null for an empty projection. Retain dictionary CAS blobs for
the lifetime of the projection. Indexing neither rewrites old revisions nor
changes Couch bodies, replication checkpoints, or runs a panel graph.

For an indexed `vm-worlds/…` publication, **Index published file tree** follows
that exact revision's canonical manifest CID into the same owned projection
lane. This writes separate dense metadata (paths, extent CIDs and lengths),
using the selected optional Zstd dictionary; shared roots are indexed once.
**Inspect indexed file tree** exposes its Confix paths and cursor facets after
reopen. **Inspect publication document** returns to the Couch pointer document.
The corresponding requests are `PUT /api/lcnc/workspace` with
`{"action":"index-file-tree","source":{"kind":"couch","id":"vm-worlds/…","rev":"…"}}`
and `POST` with the same source plus `"facet":"file-tree"` inside `source`.
Indexing is explicit and capped at 1 MiB of manifest bytes. It neither opens
file payloads nor validates their availability, restores a guest, changes the
live mount, or implements canonical Btrfs disk compression. Missing or corrupt
metadata is refused, not silently replaced by the current guest world.

## Panels: language-authored Camel routes

Panel loads require every node contract, including nested ring children, before
replacing the canvas. Missing contracts, duplicate identities, malformed graph
arrays, or dangling wire endpoints refuse the load without dropping nodes or
cables, changing history, or overwriting the saved scratch. The persistent
refusal offers **Retry contracts**; then retry the load. Failed startup
hydration pauses restore/seeding and automatic execution. Kind-unsafe cables
remain editable (red/dashed); the daemon still owns semantic type validation.

In `/panels`, open the language facet and choose **Author Camel route in …**.
The construction loads for review; it does not execute until Run. Kotlin,
Java, Clojure, JavaScript, Groovy, Ruby and Python use the existing typed
language lane to return a Confix route definition. Wire that value to
`vm.camel`'s `route` input; the message goes to its separate `messages` input.
The older payload-transform construction remains available.

This is data-defined Camel routing, **not native Camel DSL**. For example,
the route parameter accepts:

```json
{"from":"direct:review","steps":[
  {"setHeader":{"name":"ReviewParty","value":"reviewer"}},
  {"choice":{"when":[{"bodyEquals":"missing","steps":[{"setBody":"request-evidence"}]}],
             "otherwise":[{"setBody":"review-evidence"}]}},
  {"to":"mock:review"}
]}
```

The closed step set is `to`, `setBody`, `setHeader`, `choice` with literal
`bodyEquals` branches, and `stop`. Unknown steps and dynamic endpoint
indirection are refused. The module and reach gates inspect every branch
before a context starts. Execution owns and closes one Camel context; its
receipt retains the definition, reply and headers. OWN trust is required:
host Camel effects are not a sandbox. The example labels a reviewer, not an
approval or a clinical/legal decision.

For **native Java or Groovy Camel DSL**, use the corresponding **Native … Camel
DSL** action in the language facet, or the **Native … Camel review route** in
the Confix & facets construction gallery. Native availability checks the JDK
compiler and manifest-verified modules independently of the GraalJS function
adapter; loading never executes source. The same `vm.camel` node accepts
`authoring=native-dsl`, `language=java` or `groovy`, and a `source` containing a real `RouteBuilder.configure()`
body. `input` is the structured `messages` value; `id` is the invocation ID;
`entry` is the node's `from` parameter, which must name a declared `direct:`
consumer. `java.util.*`, Camel core, builder and model types are imported.
`source` and the Confix `route` input are mutually exclusive. Clearing native
source refuses execution; it never falls back to `from`/`to`. The preset uses
an actual Java processor or Groovy closure, header predicate, Simple expressions
and `toD`. Groovy compiles against a fresh invocation-owned runtime and class
cache, released after Camel shutdown. No shared Groovy metaclass state is intended.

Native source compiles in memory against the manifest-verified mounted jars,
not TrikeShed's implementation classpath. Static model endpoints are checked
before route startup; concrete dynamic URIs pass through the same reach and
module gate before Camel resolves their endpoints. `toD` component optimization
and eager component startup are disabled so that lookup remains observable.
Route error handlers cannot mask an admission refusal as node success.
The one-shot receipt records the source CID, actual routes, reply and headers;
return follows producer/context cleanup. Nothing enters the persistent registry.
Inputs and returned headers cross the common bounded Confix-value boundary;
opaque host objects are refused, never silently stringified as evidence.
Groovy decimal literals are `BigDecimal`; use an explicit `d` suffix for a
Confix floating-point header (for example `1.25d`). No implicit decimal coercion
or precision loss is applied at the boundary.

This is **OWN-trust host code**, not a sandbox: processors, preconstructed endpoints,
custom components and direct host I/O remain trusted code. Statement, call-count
and wall-clock limits are unsupported and refused when requested. Do not author
detached work and expect Camel shutdown to own it. Native DSL support currently
covers Java and Groovy; the seven-language Confix-authoring path remains separate.
The Groovy function adapter's cooperative loop checks do not apply to native DSL.

Typed guest values currently allow five nesting levels, so deeply nested
choices must enter through the route node's Confix parameter. Available
languages and their compiler/runtime limitations are reported in the facet;
the Kotlin source lane runs on JVM and does not prove cross-target portability.

The Evidence facet reads immutable run receipts and their frozen programs. Exact
program CIDs include layout. When automatic output layout or manual positioning
changes that CID, the facet separately compares the authored definition while
ignoring node position, size, collapse and viewport. Parameters, wires and their
order, nested scopes, controls and Kanban remain compared. This read-only
comparison neither executes nor saves a program, and does not establish that
inputs, dependencies or results are still current.

Expand **Node invocations** in a run receipt to inspect the guest used by a
`vm.polyglot` node, including failed calls. Its bounded snapshot retains VM ID,
language, tier, counters and up to eight root-return profiles before guest
release. These are copied from that guest's existing hypervisor profiles, not
matched against daemon-wide pointcuts. Missing/oversized observations and omitted
profiles are explicit. A tier without profiles reports unavailable, not zero
execution. JVM-language locations refer to the Graal invocation wrapper, not
the Java/Kotlin/Clojure/Groovy body. This is retained observation, not a full
trace, live breakpoint control or proof of successful cleanup; reading it
does not execute the program or call a model.

**Inspect output … in Confix** and **Inspect invocation … in Confix** open the
same workspace drawer at that retained record's path. The source is the exact
receipt or invocation-ledger CID, not the canvas, latest run, or live VM.
Path selection, token paging and independent cursor indexes operate on those
original bytes; omitted values stay omission markers. **Back to execution
evidence** returns to the existing evidence view. Choose **Current canvas** in
the source selector to leave retained-content inspection. Neither action loads,
runs or changes the construction.

The source view also accepts a **Retained JSON content ID** directly. Its API is
`POST /api/lcnc/workspace` with
`{"source":{"kind":"cas","cid":"sha256:…"},"path":["lcncRun","outputs","function"]}`.
Only existing, hash-verified UTF-8 JSON is accepted, within the workspace's
1 MiB, depth and token budgets. No missing-content fallback or CAS write occurs;
binary/CBOR content continues through the existing typed storage projections.

Graal's blackboard lists CID-pinned **Receipt / Program / Invocations in Confix**
links for up to 32 LCNC run pointers. Kanban proposal-review cards also link the
original run and frozen program into this same drawer. A **Pinned Confix link**
in the drawer preserves the selected content ID and JSON path, not a moving
program name, token page or cursor-index selection.
`/panels?content=sha256:…&path=…` opens an inspection-only, ephemeral view: it
does not restore scratch, hydrate a construction, arm sources or execute nodes.
Malformed, missing and conflicting source links stay inert with no fallback.
The ordinary **Open Panels editor (saved scratch)** link is a separate navigation.
These links expose existing retained bytes; they neither prove current run
liveness nor supply a source debugger for a released guest.

The Evidence view also lists up to eight recent named-program runs or inline
runs matching the frozen definition, including presentation-only differences
such as the viewport after reload. Exact CIDs remain distinct. Inline labels
do not establish saved-program membership. Discovery compares at most 32
distinct snapshots and reports when that limit prevents further matching;
older or edited versions remain inspectable by receipt CID. Definition matching
alone does not establish that inputs, dependencies or results are current.

**Debug nodes** admits one ordinary durable program run, initially paused before
its first non-annotation node. **Step into next node** executes the pending node
and stops at the next boundary, entering rings and each/carry iterations under
the existing common Kotlin executor. The panel shows bounded, copied parameters
and resolved wired inputs, the scope path, iteration, pause ordinal, run ID and frozen
program CID. A skipped/unfed node can still be inspected before the executor
decides to skip it. Ordinary node stepping runs a polyglot guest call to completion.

At a `vm.polyglot` pause, **Into guest source** instead suspends the same call
through Truffle, then offers **Into guest source**, **Step over source** and
**Step out of source**. In-process JS, Python and Ruby expose bounded stack frames
and copied scalar locals at statement and function-entry/exit stops. Each pause
identifies its before/after anchor; object inspection does not invoke getters or
evaluate expressions. Coordinates refer to generated guest source, including the language
adapter, not directly to the authored body. JVM-language wrappers and process
guests explicitly refuse source stepping before evaluation. **Step into next
node** from a source pause finishes the guest and stops at the next node.
**Run to line** resumes to a one-shot breakpoint in the current guest source,
without replaying earlier effects. The panel numbers a bounded source window;
enter any line within the reported source length. Truffle can resolve a
non-executable line to a nearby executable location: the next hit shows both
requested and stopped-at lines. A target that is never reached may complete
without another source pause. Resolution uses statement and function boundaries;
for example, a Ruby block's untagged tail expression can resolve to its block
entry. Variable editing is not implemented.

**Continue run** releases subsequent nodes, including their effects; **Cancel
run** uses the existing run cancellation route. Pauses count against the run's
timeout (120 seconds by default), and ordinary completion/failure/cancellation
retains the same receipt and invocation evidence. Module drain releases debug
holds and finishes admitted work. The controller is transient and removed on
exit; daemon restart marks unfinished runs interrupted, not resumed. Closing a
browser is not a cancellation guarantee. A lost response must be checked in run
records before another execution is requested.

Once the first controller snapshot identifies the run, the address bar and
**Reconnect this run** link pin both its token and run ID. Reloading that link
opens an empty, execution-disabled observation page before scratch restoration,
contract hydration or timers. It does not submit a run, load its frozen program,
replay an effect or overwrite saved scratch. Stepping and cancellation remain
explicit actions on the observed run. **Stop observing** stops only the page's
poller; it does not release a pause or cancel the run. Pauses still consume the
original timeout. Observation failures disable controls until a fresh snapshot
arrives. An exited controller or reused token disables controls and shows only
the exact run's retained receipt pointers, when available, with Confix links.
A recorded `running` status without a controller is unresolved, not completion.
Reload before the first identified snapshot has no recovery identity yet;
check run records rather than resubmitting. Recovery links are not authenticated
session grants; they use the same local API access as the original debugger.
Live parameter picklists retain an empty choice until explicitly selected;
discovering a job does not select it for a command or save a new parameter value.

API: add a fresh UUID `debugToken` to `POST /api/lcnc/run` for a whole program.
Read `GET /api/lcnc/run/debug?token=…`, then include `&runId=…` to pin subsequent
observations. Send `POST /api/lcnc/run/debug` with
`{token, runId, ordinal, action}` for the observed pause, choosing from its `actions`
list (`step`, `continue`, `source-into`, `source-over`, `source-out`, `source-line`).
`source-line` also requires a positive integer `line` in the current generated
source. Malformed/out-of-range lines return 400 without releasing the pause. Active
token collisions, run identity mismatches and stale/repeated pause commands
return 409; commands missing a valid run ID return 400. An exited controller
returns 404. A run-bound GET may include that exact run's compact `receipt`
projection with a 404/409; this does not make the controller live. The pause ordinal is distinct from the walk's
`invocationOrdinal`; nested callers cannot reuse a prior pause command. These
commands advance the existing run, never replay
it. The token is a correlation key, not an authentication boundary.

## Process-host callbacks

The Python/Hermes sleeve's host callbacks and both JVM child workers use
`ProcessCalls` in commonMain userspace NIO: one owning SupervisorJob, bounded
admission and a joined reply channel. Python host callbacks have four execution
permits; restricted host callbacks and child guest operations have one.
Excess calls are refused before running.
Deadlines include queue time; an expired queued call cannot execute later.
A callback deadline initiates cancellation, then joins callback cleanup before
returning the failure reply. A callback that cannot finish cleanup can therefore
delay that reply; this is not a hard-kill guarantee for host code.

The JVM SPI adapts blocking callbacks with interruptible execution; it does not
own a separate callback pool or deadline scheduler. Drain closes admission,
finishes admitted calls, and joins reply delivery. A transport timeout or worker
revocation does not establish host-effect completion; closing the isolate still
joins admitted callbacks. Child operations retain process-main-thread affinity,
including Python thread locals and SQLite connections. Close reserves a final
operation even at capacity: admission stops, preceding operations finish, the
guest context closes, then its acknowledgement follows earlier replies. Ordinary
restricted-worker close no longer uses a one-second kill timer. Interrupt and
transport failure remain explicit revocation paths, not successful drain.
Process launch and raw pipe transport remain platform boundaries; this change
does not claim those I/O paths have been migrated to the uring facade.

The existing Teleported wire tree admits only finite `Real` values. Host and
Graal projections retain NaN and infinities as opaque number markers, including
inside nested values, rather than emitting invalid JSON and losing the reply.
The Node process backend uses the same markers for non-finite results and
host-call arguments instead of silently replacing them with null.
Finite doubles outside the Long range retain their value instead of saturating
during canonical encoding. Opaque markers do not make those values actionable
LCNC data.

## Curator — mostly done

The curator use case is mostly done. The hard work is in place: reading documents
and sections, extracting concepts and relations, and carrying that evidence
through NLPCORE, Rete and NARS. The remaining work is in the controls and polish,
not a replacement runtime.

<img width="1490" height="899" alt="TrikeShed curator" src="https://github.com/user-attachments/assets/2f655967-82a1-4faa-bc2e-e671c9f13950" />

**Ingest, briefly:** start the daemon, open `http://localhost:8888/graal`, and drag
a folder onto the terrain. That is the current folder-ingest entry point; the
files become a project database. Open `http://localhost:8888/curator` to work
through the curated volumes, sections, concepts and their relationships.

Volume/concept CRUD controls and editing tweaks are still missing. A focused
ingest GUI, or a Notion-like document workspace over the same machinery, is
future consideration—not something already shipped. The core is there; the
whole editing experience is not finished.

For local-only model use, launch with `--airgap` (or `TRIKESHED_AIRGAP=1`). Jev
and other remote model calls are disabled even when credentials are configured.
BERT uses local weights and tokenizer files; missing assets or insufficient
retained labels remain unavailable, without a remote fallback. This is model
egress control, not a machine-wide network firewall.

---

# TrikeShed Concept Map — for the Kotlin Maintainer

> One place a new maintainer who only knows Kotlin can read end-to-end.
> Covers the architecture spine, data algebra, runtime contracts, and the integration seams you will touch.

---

## 0. Quick Orientation

```
TrikeShed/
├── src/                    ← single source root (KMP: JVM / JS / WASM / native)
├── utils/htxc/             ← standalone CLI (composite build, see 8.3)
├── utils/ingest/           ← catalog tool (composite build)
├── build.gradle.kts        ← locked: Kotlin 2.4.10, Gradle 9.6.1, JDK 25, GraalVM CE 25.0.2
├── settings.gradle.kts     ← compose plugin, prefer-project repos
├── gradle.properties       ← jvmargs, native ignore
├── docs/                   ← GitHub Pages output (./gradlew generateForgePages; -PforgePagesStages=jvm,js,wasm)
└── PRELOAD.md              ← kernel algebra cheatsheet (read first)
```

 **Toolchain** — JDK 25 (GraalVM CE 25.0.2), Kotlin 2.4.10, Gradle 9.6.1.
**No libs/ subprojects** — everything lives in `src/`.
**Confix** — owns JSON, YAML, and CBOR formats on every target, including JVM; `kotlinx-serialization-json` and `kotlinx-serialization-cbor` are not dependencies. `commonMain` explicitly depends on `kotlinx-serialization-core` for annotations and serialization contracts, with the `kotlin("plugin.serialization")` plugin generating serializers.
**License** — AGPLv3 (effective 2017). Do not change.
**Task ledger** — `doc/todo.md` is live flywheel intake; dated task lists elsewhere are historical unless explicitly refreshed. The current marketability/MCP board audit is `docs/marketability-kanban-mcp-audit.md`; its **MKT-001…015 market backlog remains inert until explicitly dispatched**, but its MCP implementation backlog (KMFSM-001/004/005/006/009/010) shipped 2026-08-30 — the board is projected to any MCP client at `POST /api/mcp` (`docs/guide-mcp-kanban.md`), with `scripts/demo-mcp-kanban.sh` as the runnable proof.
**Architecture docs** — `doc/rewire.md` (user-centric Forge workspace architecture, storage unification, K8s emulation via GraalVM pointcut server), `doc/taste.md` (high-performance hierarchical-UI engine principles, 10-point gap review).
**Compiled-out layers** — `classfile/slab/**` is excluded from `commonMain` compile in `build.gradle.kts` (~20 `TODO()` stubs: GraalJS eval, DuckDB c-interop, `FacetedCursorContract`, `MiniDuckContract`; files preserved on disk). `CircularQueue.poll/peek/iterator.remove` converted from `TODO()` to `error(...)` — loud hollow, not silent stub.
**Static assets** — `src/commonMain/resources/web/` (index.html, styles.css, script.js, manifest.webmanifest, icons/) is the single source of truth for the Forge HTML shell; the `generateForgeAssets` Gradle task bakes these into the Kotlin-internal `ForgeAssets` object so no runtime resource lookup is needed.
**Categorical idempotency** — the kernel maxim (see PRELOAD.md): if a structure is not mutated, it stays in the category it came from. `Series` that gets copied to `List` only to be read back is a type demotion. `LinearHashMap` (KMP-native) replaces `MutableMap` where the map is not mutated post-construction; CasStore uses it as the blob backing.
 
**Storage unification** — one CAS, five lenses (auxiliary CAS / materialized / reified Confix / btrfs content / graph trees). `doc/rewire.md` §0. Projection registry (`project(cid): Lens`) is the one new piece (T-CAS-PROJ-1).

---

## 1. Kernel Algebra (the mental model)

All shapes collapse to `Join<A,B>`:

```kotlin
interface Join<A, B> { val a: A; val b: B }
infix fun <A,B> A.j(b: B): Join<A,B> = object : Join<A,B> { override val a = this@j; override val b = b }

typealias Twin<T>    = Join<T, T>
typealias Series<T>  = Join<Int, (Int) -> T>          // size + index function
typealias Series2<A,B> = Series<Join<A,B>>             // split-storage specialization
typealias Cursor     = Series<RowVec>                  // columnar dataframe
typealias RowVec     = Series2<Any, () -> RecordMeta>  // value + metadata supplier
```

Key operators (in `lib/Join.kt`, `lib/Series.kt`):

| Symbol | Meaning |
|--------|---------|
| `a j b` | infix `Join` constructor |
| `s.α { it → it*2 }` | lazy projection (map) over a `Series` |
| `x.`↺`` | left-identity anchor — constant supplier `() -> x` |
| `s[i]` / `s[i0 until i1]` / `s[1,3,2]` | index, range, reorder |
| `s_ [1,2,3]` | Series literal |
| `join(c1,c2)` | widen columns (Series2) |
| `combine(c1,c2)` | concat rows |

**Cursor rules** — prefer projection over mutation; range selection is composition, not control flow; preserve metadata through transforms; widen/combine explicitly; keep transforms pure.

**Read** `PRELOAD.md` and `src/README.md` before touching code — they are the algebra contract.

---

## 2. Architecture Spine (runtime layers)

**Ultimate design:** the running CCEK reactor is the process city; LCNC is its
composition and facet grammar; one inclusive grand blackboard projects every
element, channel, flow, job, guest, store, pointcut, and receipt into a shared
causal dimension system. Kanban and the Graal RTS terrain are coherent views
through one zoomable fractal camera, never separate operational truths.
Oroboros is the absorber and replicator underneath it: worktree, git,
class/resource, agent/environment, and runtime deltas become CAS/Couch citizens,
ordered change facts, and peer-replicable state. See
`doc/rewire.md` § “North star — the CCEK city and the unified grand blackboard.”

```
┌──────────────────────────────────────────────────────────────────────┐
│  FORGE / KANBAN / BLACKBOARD   (user-facing surfaces)               │
│  - Forge Workspace: light-theme block editor (sidebar + doc + board)│
│  - ForgeDoc block tree (H1/H2/H3, P, TODO, BULLET, QUOTE, CODE)     │
│  - LCNC Kanban experience = user asset, runners, operational sheets│
│  - BoardStoreElement = its one durable card-state owner            │
│  - KanbanModule = Oroboros composition and route mount             │
│  - ForgeBoardFSM legacy UI; KanbanFSM reactor telemetry only       │
│  - CCEK choreography (channels, projections, agents)                │
│  - Gallery / blackboard 2.5D/3D spatial layout                      │
│  - BlackboardSurface projection: `confixDoc(persistedJson)` → `BlackboardSurface.project(...)` → seed rows; the `ForgeAppState` DTO family was removed (commit `1e8fd692`) │
│  - Static HTML/CSS/JS shell consolidated under src/commonMain/resources/web/; `generateForgeAssets` task bakes them into the `ForgeAssets` Kotlin object so `ForgeApp.kt` references the asset by symbol, not by resource lookup │
│  - ManimWM camera: momentum, tilt, 2.5D parallax + 3D orbit         │
├──────────────────────────────────────────────────────────────────────┤
│  NUID / CCEK FANOUT   (authorization + dispatch)                    │
│  - Nuid = Join<Capability, Join<Nonce, Subnet>>                     │
│  - NuidFanoutElement: concentric narrowing, escalation, CAS claim   │
│  - Workgroup: scope + TraitSpace → canHandle(request)               │
├──────────────────────────────────────────────────────────────────────┤
│  LITEBIKE LISTENER   (multiprotocol CCEK listener)                  │
│  - LitebikeListenerElement: protocol-keyed channel slots            │
│  - JvmLitebikeBindAdapter: sole socket bind, bytes → CCEK accept    │
│  - JvmMulticastAdapter: mDNS/SSDP join + SO_REUSEPORT fallback      │
│  - JvmKanbanServer: daemon, no framework, hand-rolled HTTP          │
├──────────────────────────────────────────────────────────────────────┤
│  JOB NEXUS   (durable work orchestration)                           │
│  - JobSupervisorElement — bounded command channel + reactor         │
│  - JobReducer (pure) — idempotency, optimistic revision, lifecycle  │
│  - CasStore (CAS), JobLog (WAL), JobIndex, Checkpoint              │
│  - ReteNetwork — production rule engine (alpha/beta/agenda/refraction)│
│  - JobKanbanProjection / ForgeKanbanJobSink — Kanban as projection  │
├──────────────────────────────────────────────────────────────────────┤
│  COUCH / ISAM / TREEDOC   (content-addressed persistence)           │
│  - CasStore — LinearHashMap<ContentId, ByteArray> (KMP-native)      │
│  - CouchStore (in-memory, pluggable persistence)                    │
│  - TreeDocPipeline — document archive over CAS (git-tree-shaped)    │
│  - DurableAppendLog / WalFrame — frame format with CRC32C           │
│  - JobRepository — recovery from checkpoint + tail replay           │
│  - ConfixDocStore, ViewServer cascade rollups                       │
│  - CowBPlusTree — COW pages in CAS, btrfs-style snapshot/send/recv  │
├──────────────────────────────────────────────────────────────────────┤
│  DAG / RETE   (causal + rule engine)                                │
│  - ReteWorkingMemory, Alpha/Beta memories, Agenda, Refraction       │
│  - BlackboardDagCausalGraph, BlackboardDagFabric                    │
│  - ReteAgent — CCEK bridge                                          │
├──────────────────────────────────────────────────────────────────────┤
│  COLLECTIONS   (index algebra)                                      │
│  - LinearHashMap, FunnelHashMap, ElasticHashIndex, RadixTree        │
│  - MultiIndexK (exact/order/range/prefix) with stable IndexSpecId   │
│  - COW B+Tree (btree/) — deterministic pages in CasStore            │
├──────────────────────────────────────────────────────────────────────┤
│  CONFIX   (schema-driven config oracle)                             │
│  - ConfixDoc / ConfixCell — index-first, reify-later                │
│  - ConfixFacetPlan — compiled from job-nexus.schema.json            │
│  - JSON / YAML / CBOR single parser (Syntax enum)                   │
├──────────────────────────────────────────────────────────────────────┤
│  CHOREOGRAPHY / REACTOR   (structured async)                        │
│  - AsyncContextElement (CREATED→OPEN→ACTIVE→DRAINING→CLOSED)        │
│  - NioSupervisor / LiburingElement / FanoutDispatcherElement        │
│  - FunctionalUringFacade — bounded submissions and completions      │
│  - MuxReactorElement — keymux/modelmux/taxonomy/kanban events       │
│  - ProcessReactorEndpoint — NUID-authorized exec (Capability.Process)│
├──────────────────────────────────────────────────────────────────────┤
│  TRANSPORT / HTX   (version-agnostic HTTP)                          │
│  - HtxMessage blocks (ReqSl·Hdr·EOH·Data·EOT·EOM)                   │
│  - HtxClientReactorElement — channelized client                     │
│  - DHTX_REQ/DHTX_RES for non-HTTP protocols                         │
├──────────────────────────────────────────────────────────────────────┤
│  KERNEL SURFACES   (expect/actual)                                  │
│  - FileImpl, LiburingImpl, FilesImpl, ChannelsImpl                  │
│  - FunctionalUringFacade wraps UserspaceChannelBackend              │
│  - ByteBuffer / ByteRegion / ByteSeries — zero-copy IO path         │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 3. Job Nexus — the durable work spine

### 3.1 Command → Event Pipeline (in `JobSupervisorElement`)

```
JobCommand (Submit/Start/Complete/Fail/Retry/Progress/Block/Cancel/Move/Ack/Retract)
   │
   ├─ 1. Schema validation  (ConfixFacetPlan)
   ├─ 2. Canonical CBOR      (CanonicalCbor.encode → deterministic bytes)
   ├─ 3. CasStore.put        (SHA-256 CID, digest verification on get)
   ├─ 4. JobLog.append       (sequence + payload; monotonic)
   ├─ 5. Durability barrier  (flush/fsync contract)
   ├─ 6. JobReducer.reduce   (idempotencyKey + expectedRevision → JobSnapshot)
   └─ 7. Committed → JobEvent.Accepted/Rejected → channels
```
 
**Invariants**
- `idempotencyKey` deduplication (first wins, later rejected)
- `expectedRevision` optimistic concurrency (stale → Rejected)
- Commands enter **only** through the bounded `Channel<JobCommand>` (SUSPEND on overflow) 
- Failed step leaves **no visible snapshot** and does **not** advance committed sequence

### 3.2 Core Types (`job/IdentityTypes.kt`, `JobSnapshot.kt`)

```kotlin
@Serializable data class JobId(val value: String)
@Serializable data class Revision(val value: Long)
@Serializable data class KanbanColumnId(val value: String)
@Serializable data class Sequence(val value: Long)

data class JobSnapshot(
  val jobId: JobId,
  val revision: Long,
  val causalKey: String,
  val lifecycle: String,              // submitted | ready | active | blocked | failed | closed | moved | acknowledged | retracted
  val dependencies: List<JobId>,
  val attemptCount: Int = 0,
  val attemptId: String = ""
)
```

**Lifecycle derivation** — in `JobReducer.deriveLifecycle`; blocked if any dependency failed; ready if all deps closed.

### 3.3 ReteNetwork (production rule engine)

```
ReteWorkingMemory  ← assert/modify/retract by FactId + version CID
ReteAlphaMemory    ← shared single-condition nodes (predicate sharing)
ReteBetaMemory     ← equality join (leftFacetId = rightFacetId) with token memory
ReteAgenda         ← salience↓, sequence↑, activationId↑ deterministic pop
ReteRefraction     ← one firing per (ruleVersion, sorted supportCIDs)
ReteNetwork        ← owns all above; runs on bounded SendChannel<JobCommand>
```

**Rules currently encoded** (see `ReteNetwork.evaluateRules`):
- all deps `closed` → `Start` command
- any dep `failed`  → `Block` command with support evidence

Actions **never** mutate Kanban/Couch/snapshots directly — they enqueue `JobCommand` via the reactor ingress channel.

### 3.4 Projections (read models)

| Projection | Purpose |
|------------|---------|
| `JobKanbanProjection` | Kanban cards from committed snapshots (`applyCommit` + `rebuild`) |
| `ForgeKanbanJobSink`  | Monotonic sequence gate → projection |
| `CouchHeadProjection` | revision string stored raw; CID-derived `_id`/`_rev` implemented, MVCC |
| `CouchChangesProjection` | Strict monotonic `_changes` stream |
| `CowBPlusTree` | Persistent ordered/range index (pages in CasStore) |
| `JobCheckpoint` | Committed sequence + root CID + schema CID |

---

## 4. Confix — the config oracle

**Single parser** (`Syntax` enum: JSON, CBOR, YAML) → `ConfixIndex` (flat token array + `FlatIndex`) → lazy `reify()`.

```kotlin
typealias ConfixDoc  = Join<ConfixIndex, Series<Byte>>
typealias ConfixCell = Join<RowVec, Series<Byte>>
```

**Navigation** (`ConfixKit.kt`):
```kotlin
doc.value("operation")                 // scalar
doc.docAt("dependencies", 0)?.get("jobId")  // nested
cell.reify()                           // Any? with tag dispatch
doc.navigate(jsPath)                   // typed JsPath (String | Int steps)
```

**ConfixFacetPlan** — compiled from `src/commonMain/resources/confix/job-nexus.schema.json`:
- operation enums, frame families, required fields, primitive/array constraints
- stable facet/index IDs, exact/order/range/prefix index policies
- validation errors include schema/document path

**No second hand-maintained field table** — the schema resource is the contract.

---

## 5. Couch — content store + projections

```
CouchStore (in-memory, pluggable CouchPersistence)
  ├─ put/get/delete  → MutationEvent (Inserted/Updated/Deleted)
  ├─ query()         → Cursor (row = doc, cols = _id + fields)
  ├─ subscribeMutations → MutableSeries observer
  └─ CouchHeadProjection / CouchChangesProjection  (built from committed Job frames)
```

**Head/Changes semantics** — revision string stored raw by the projection; stale revision rejected; delete = tombstone; `_changes` resumes after sequence without gaps. CID-derived `_id`/`_rev` is implemented.

---

## 6. Collections — index algebra

| Component | Purpose |
|-----------|---------|
| `LinearHashMap` | open-addressing base (mixed hash bits, bounded probes) |
| `FunnelHashMap` / `FunnelHashIndex` | Krapivin 2025 funnel hashing (tiered geometry) |
| `ElasticHashIndex` | append-only, deterministic split |
| `RadixTree` / `Trie` | prefix queries, deterministic order |
| `MultiIndexK / MultiIndexContainer` | stable `IndexSpecId`, unique/non-unique, txn add/modify/retract, immutable snapshots |
| `CowBPlusTree` | COW pages in CasStore, deterministic page CID, checkpoint validation + tree hydration + tail replay |

**MultiIndex transition** — lambda-identity keys replaced by explicit `IndexSpecId`; incremental order/range (binary insert), no full-store resort.

---

## 7. Choreography / Reactor — structured async

### 7.1 Element lifecycle

```
CREATED → OPEN → ACTIVE → DRAINING → CLOSED
```

Typed `CoroutineContext.Key` identities resolve elements from the caller's
context. Some elements still inherit `AsyncContextElement` and use the states
above. `FunctionalUringFacade` and `NioSupervisor` implement
`CoroutineContext.Element` directly. Admission and awaited completion are
implementation obligations; the key and superclass do not establish them.

### 7.2 NioSupervisor (root registry)

`NioSupervisor` registers `platformNioProviders()` and resolves them through
`service<T>()`. It opens providers with an async lifecycle and drains them in
reverse registration order, retaining the cleanup outcome for concurrent callers.

### 7.3 Scoped submission and completion

`UringChannels.open(scope, entries)` creates a `FunctionalUringFacade` whose
bounded batch channel and completion worker belong to an owning `SupervisorJob`.
Callers await `batchEnqueue` results, correlated by `userData`, and await `drain`
before releasing the ring. `UringBenchmark.run` executes this path; the torrent
transport and file-volume implementation also submit batches through the facade.

`userspace.nio.channels.ChannelRunner` adapts socket and readiness SQEs into
bounded polling and dispatch stages under a supervisor parented to the caller's
job. `userspace.ChannelRunner` is a separate completion adapter over
`FunctionalUringFacade`. Neither currently has source callers in this repository;
their transport integration is unfinished. Concurrent drain can close the
readiness runner's ring twice, and a failed socket close can skip remaining
descriptors. `SplatChannelRunner` serves the separate motion-model API.

### 7.4 MuxReactorElement (keymux/modelmux/taxonomy/kanban events)

- Owns `ModelApiCache`, `SharedFlow<KanbanEvent>`, `StateFlow<MuxReactorState>`
- Kanban FSM **consumes** `kanbanEvents`; it never owns the stream
- External callers `ingestTaxonomyEvents` / `lookupModel` / `cacheModel` — reactor is the single writer

### 7.5 CCEK → async-reactor CCEK — unfinished adaptation graph

The CCEK object model is a reactive context (`Context → Element → Key →
CoroutineContext`). The async reactor contract it must satisfy, and the
real-world gaps where current code still violates it:

```
                      Async-reactor CCEK constraints (target)
─────────────────────────────────────────────────────────────────────
1. Every blocking IO call has a JVM-level timeout
2. No ProcessBuilder().waitFor() without waitFor(N, TimeUnit)
3. No runBlocking { suspendCall() } inside a coroutine path
4. No CompletableFuture.get() without HttpRequest.timeout(...)
5. file/channel ops suspend through NioSupervisor (never File/Files.* sync)
6. CAS put/get/sync suspend through Mutex (never synchronized{})
7. Per-call reflink + dedup measurement suspend (never ProcessBuilder("du"|"cp"))
8. Volume.read/write/sync are the only LBA surface
9. Coroutine launch owns a SupervisorJob; never raw thread/Thread.start
10. Failure returns carry cause identity; never null sentinel
```

Current CCEK-role elements and their unfinished adaptation:

```
┌──────────────────────────┬────────────────────────────────────────────┬────────────────────────────────────────────┐
│ CCEK role (element)      │ Required reactor shape                     │ Current state (gap)                        │
├──────────────────────────┼────────────────────────────────────────────┼────────────────────────────────────────────┤
│ MuxReactorElement        │ suspend open()/close(); open fan-out         │ Mostly correct; some sync ingest helpers.   │
│ HtxReactorElement        │ suspend route/tls through NioSupervisor     │ Mostly correct; uses blocking waitFor().   │
│ NioSupervisor            │ open providers as suspend async elements    │ ✅ landed (NioCapabilityReport + probe)   │
│ FunctionalUringFacade    │ bounded batches and awaited terminal CQEs  │ Native benchmark exercises this path.      │
│ JvmReactorOperations     │ suspend select/epoll via java.nio           │ Uses blocking select() in coroutine path.  │
│ JvmFileOperations        │ suspend via NioSupervisor (io_uring/posix)  │ Mostly correct after NIO SPI cut.          │
│ LinuxFileOperations      │ suspend via PosixUringIO.fallback           │ ✅ suspend when uring present.            │
│ MmapCasStoreJvm          │ suspend put/get/sync; Mutex-only lock       │ ✅ landed (suspend create() + Mutex).     │
│ BtrfsCasStore            │ suspend reflink + suspend diskUsage         │ GAP: ProcessBuilder("cp") + "du" sync.    │
│ VolumeCasStore           │ suspend put/get/sync; Mutex-only lock       │ ✅ landed (suspend create() + Mutex).     │
│ FlywheelDriver           │ suspend cycle, no waitFor() without timeout │ Mostly correct; drafnThreeWay ok.          │
│ BrainClient              │ HttpRequest.timeout(...) + rotation         │ ✅ 15s timeout + 14 NIM rotation.         │
│ QaLaguna                 │ withTimeoutOrNull(45_000) on conflict body  │ ✅ landed.                                │
│ CycleBody                │ retransformClasses via javaagent            │ ✅ landed (rev marker accepted).          │
│ GapReducerCli            │ external producer, no daemon coroutine       │ ✅ landed.                                │
│ ForgeReactorExample      │ suspend runSyntheticCycle + SharedFlow      │ GAP: main() calls runBlocking; sync IO.   │
│ LcncIngestPipeline       │ suspend ingest; Reactor Mux model           │ GAP: Runtime/System sync helpers.         │
│ ModelCallLeaf            │ suspend onPut + flush                       │ GAP: Runtime/System/IO sync.             │
│ Blake3Hash               │ suspend hash                                  │ ✅ landed.                                │
│ ConfixEnvelopeCodec      │ suspend encode/decode + charset fns          │ GAP: java.lang.Character.                  │
│ ModelResponse            │ suspend persist                              │ GAP: java.*/System.                       │
│ AcpProtocol / Keymux     │ suspend key lease                            │ Correct.                                  │
└──────────────────────────┴────────────────────────────────────────────┴────────────────────────────────────────────┘
```

The reactor is single-source-of-truth, but several still-imperative call
sites slip through `ProcessBuilder.waitFor()`, `synchronized {}`, and
`System.*`/`java.*` references in `commonMain`. Each is a CCEK-role element
that needs to forward through the CCEK context (suspending coroutines)
instead of running on the caller's stack. The merge path is to wrap every
blocking operation in `withContext(Dispatchers.IO)` plus a real
`waitFor/timeout`, then route its result through `NioSupervisor` /
`MuxReactorElement` so the coroutine scheduler owns cancellation.

---

## 8. Surfaces a maintainer will touch

### 8.1 Forge / Kanban / Blackboard (user-facing)

```
Forge Workspace   ← block-based document editor (light theme)
  src/commonMain/resources/web/
    index.html  ← shell (sidebar + document + board + slash menu)
    styles.css  ← light theme, 16px Inter, sidebar #f7f6f3, doc #fff
    script.js   ← block editor: h1/h2/h3/p/todo/bullet/quote/code/divider
                  slash command menu, hover affordances (+/drag handle)
                  localStorage persistence, seed hydration, board view
ForgeApp.kt       ← placeholder substitution: {{STYLES}} {{SEED}} {{SCRIPT}}
                    → ForgeAssets.indexHtml/stylesCss/scriptJs
                    (generateForgeAssets bakes web/ into Kotlin object)
generateForgeAssets ← Gradle task, 5000-byte ByteArray chunks
                      → borg.trikeshed.forge.generated.ForgeAssets

ForgeDoc          ← block tree (H1/H2/H3, P, TODO, BULLET, NUMBERED, QUOTE, CODE, DIVIDER)
ForgeBoardFSM     ← board/card FSM (BoardLoaded, CardMoved, CardCreated, Drag*)
ForgeKanbanIngest ← /tmp/hi markdown → Rete facts + causal nodes + Kanban cards
ForgeGalleryCatalog/Renderer ← widget catalog (sections LAYOUT..CAS, preview tokens)
ForgePersistenceScript.kt ← browser IndexedDB/localStorage/Cache persistence
```

**Shell architecture** — the workspace shell is a pure client-side block
editor (no server at runtime). It hydrates from a baked seed JSON
(`<script id="forge-seed">`) and persists all edits to `localStorage`.
The seed is injected server-side by `ForgeApp.kt` via `{{SEED}}`
placeholder; `./gradlew generateForgePages` bakes the fully-baked HTML into
`docs/index.html` on the JVM (`ForgeBakePages`) for gh-pages deployment, and
with `-PforgePagesStages=jvm,js` also publishes the Kotlin/JS bundle to
`docs/js/TrikeShed.js` (`{{BUNDLES}}` slot; hydrate-only in the browser).

**Block types and slash commands** — typing `/` at the start of a block
opens a slash menu with: Text, Heading 1/2/3, To-do, Bulleted list,
Numbered list, Quote, Code, Divider. Each block has hover affordances
(`+` to add below, `⋮⋮` to drag). Enter on a heading exits to paragraph;
Backspace on empty block deletes and focuses the previous block.

**Board view** — toggle between Document and Board views via the topbar.
The board shows kanban columns (To do / Doing / Done) populated from
seed cards (lcncEntities) or user-created cards. Cards cycle columns
on click. Same items as the document — different projection.

**Sidebar page tree** — left sidebar shows workspace pages with icons,
titles, and active highlighting. "+ Add a page" creates a new page.
Pages persist to `localStorage`.

**Seed hydration** — the baked seed carries `lcncEntities` (→ bulleted
list in the document + cards on the board), `causalNodes` (→ causal
graph), and `gallery` (→ widget catalog). The shell note in the sidebar
bottom shows the seed summary ("13 entities · 13 causal nodes · gallery").

**Host view / sub-VMs** — the common VM API (`borg.trikeshed.vm`: `VmHost` with
interface-default `discontinued(...)` chokepoints, providers per target, `Teleported` ABI)
surfaces in the PWA as the Host view and, when served by `./gradlew runKanbanHttpServerJvm`,
as `/api/vm` (`spawn`, `{id}/eval`, `{id}/revoke`, SSE `events`). Spec = `openapi/forge-host.openapi.yaml`
(parity-tested against the route table).

**Gallery on GitHub Pages** — `./gradlew generateForgePages` (stage `jvm`
is the seed bake; `js`/`wasm` add bundles as `./gradlew forgePagesProbe`
turns green — ratchet list in `gradle/js-target-debt.excludes`). Serve
locally with `./gradlew serveForgePages`. `kotlinUpgradeYarnLock` may be needed if
the yarn lock drifts.

**Blackboard-as-Confix-cursor** — the target architecture. A single JSON
file is the blackboard; `confixDoc(json)` → `Cursor` →
`BlackboardSurface.project(cursor)` → UI renders cursor slices by
path/offset/facet. No parallel DTO truth. `BlackboardSurface` joins
`LcncEntitySurface` + `CausalGraphNodeIndex` into a deterministic
`Cursor` of `BlackboardSurfaceRow`s. Facet drilldown = child cursor
projections from the same doc.

**ManimWM 2.5D/3D surface** — `ForgeBlackboardCamera` carries momentum
(`vx`, `vy`, `vz`), tilt (2.5D parallax), and bounded zoom. The
blackboard is the VFS; cursors are the files; facets are drilldown
views. `ForgeBlackboard3D` adds true 3D orbit with per-section
elevation (gallery above board above page).

### 8.1a NUID / CCEK Fanout (authorization + dispatch)

```
Nuid = Join<Capability, Join<Nonce, Subnet>>
  - Capability: sealed hierarchy (Process/Cas/Wireproto/Sctp/Model/BlackBoard/Custom + wildcard family roots)
  - Subnet: concentric containment (core < process < local < lan.localhost < mesh.worker.* < global.relay)
  - Nonce: RandomBytes + Derived (causal chaining)

NuidFanoutElement
  - CCEK lifecycle (CREATED→OPEN→ACTIVE→DRAINING→CLOSED)
  - Concentric narrowing: filter by scope⊇subnet AND TraitSpace.can(capability), sort by scope.level ascending
  - Escalation: timeout at request level → walk outward up to escalationBudget+1 levels
  - Claim: first WorkgroupSlot.tryTake() matching claimId wins; losers stand down

Workgroup
  - name + scope: Subnet + traits: TraitSpace
  - canHandle(request: Nuid) = traits.can(capability) && (scope contains subnet)
```

### 8.1b Litebike Listener (clean-room Kotlin port — no FFI)

```
LitebikeListenerElement
  - CCEK element; registry keyed by Protocol.id (UByte)
  - register(protocol) → ChannelWorkgroupSlot; slot.consume() suspends for ChannelMessage
  - accept(protocol, bytes) → offers to slot, fires LitebikeFanoutEvent to CCEK subscribers
  - Protocol enum: Http(1) Socks5(2) Tls(3) Dns(4) Json(5) Http2(6) WebSocket(7) Bonjour(8) Upnp(9)
  - IDs 1-7 match litebike taxonomy.rs conceptually; 8-9 are TrikeShed-local extensions

JvmLitebikeBindAdapter
  - The ONLY place that opens AsynchronousServerSocketChannel
  - Reads bytes → ProtocolDetector.detect(head) → listener.accept(protocol, bytes)
  - No HtxReactorElement, no com.sun.net.httpserver, no RfxHttpServerJvm

JvmMulticastAdapter
  - Joins mDNS 224.0.0.251:5353 and SSDP 239.255.255.250:1900 via DatagramChannel
  - SO_REUSEPORT-first fallback for macOS mDNSResponder port conflict
  - Tracks Jobs + MembershipKeys; close() cancels all read loops and drops groups

JvmKanbanServer
  - Daemon entrypoint (--port, --donor)
  - Owns one LitebikeListenerElement; registers Http/Json/Socks5/Tls/Bonjour/Upnp slots
  - HTTP worker consumes httpSlot, hand-parses request line, routes to /api/health|cap|board|submit|donor
  - Bonjour/Upnp consumers parse minimal mDNS/SSDP headers, emit JSON to Json slot
  - No server framework; CCEK fanout all the way down
```

### 8.2 CCEK (choreography for Forge)

```
ArticulatedNode
  signalIn: Channel<ForgeSignal> (AppendBlock/UpdateText/DeleteBlock/MoveCard)
  projections: SharedFlow<ForgeDocument>, <KanbanBoard>, <String>
  agents: Map<String, (ForgeSignal) -> Unit>
  fanOutJob: structured concurrency dispatch
```

Signals flow **into** `ArticulatedNode`; projections flow **out** via `SharedFlow`. No dual-mutable truth.

### 8.1c ProcessReactorEndpoint (NUID-authorized exec)

```
ProcessReactorEndpoint  ← ReactorEndpoint (commonMain)
  - Requires Capability.Process on the NUID; rejects other capabilities
  - Verb "exec" → ProcessOperations.exec(command, args)
  - Response verb: "ok" (stdout) when exit==0, "error" (stderr) otherwise
  - Fulfills T12 (Process worker) — wires ProcessOperations SPI into the reactor algebra
  - Lives in userspace/reactor/process/ (commonMain) — platform exec lives in ProcessOperations actuals
```

The endpoint is a thin Capability.Process dispatcher. It does not own a process pool; it is the reactor surface for one-shot exec. Long-lived processes belong to a future worker element on the same NUID/Capability contract.

### 8.3 HTX / htxc (CLI utility)

```
utils/htxc/          ← composite build (includeBuild("../.."))
  - bin/htxc         ← shell launcher, exact arg forwarding, preserves exit code
  - HtxAria2CliArgs  ← aria2-compatible switches (dir/out/split/max-conn/continue/checksum)
  - HtxAria2Engine   ← range/HEAD + chunked download via HtxClientReactorElement
```

---

## 9. Build & Deploy (what you will run)

```bash
# Environment
export JAVA_HOME=$HOME/.sdkman/candidates/java/25.0.4.1-graal
export PATH="$JAVA_HOME/bin:$PATH"

# Full build + test
./gradlew build --console=plain

# Focused test suites
./gradlew jvmTest --tests "borg.trikeshed.dag.*"
./gradlew jvmTest --tests "borg.trikeshed.job.*"
./gradlew jvmTest --tests "borg.trikeshed.collections.multiindex.*"

# GitHub Pages deploy (docs/ is the hosted directory on master)
./gradlew generateForgePages -PforgePagesStages=jvm,js   # bake docs/ (jvm seed + Kotlin/JS bundle)
./gradlew serveForgePages                                 # preview http://localhost:8765/ (-PforgePort=N)
git add docs && git commit -m "feat: deploy Forge workspace" && git push

# Verify deploy (Pages serves master:/docs directly — dump, commit, push; no CI)
gh api repos/jnorthrup/TrikeShed/pages/builds -X POST && gh api repos/jnorthrup/TrikeShed/pages/builds/latest
```

**Common tasks registered** (`build.gradle.kts`):
- `jmh`, `jmhJoin`, `jmhConfix`, `jmhWal` — JMH benches
- `benchmarkJoin`, `benchmarkSequence`, `benchmarkVector`, `benchmarkMath`, `benchmarkConfix`
- `printForgeGallery` — JVM text grid of catalog + blackboard
- `runForgeJvm` — Compose Desktop shell
- `generateForgePages` — Sync task (stages `jvm`[,`js`,`wasm`] → docs/); `serveForgePages` previews docs/ on the JDK http server; `forgePwa` = both
- `julesArtifact`, `julesSettle`, `julesReviewPatch`, `julesReviewReport` — Jules CAS bridges (`--args="…"`)
- `runOroborosDaemon`, `runFlywheelTui`, `runKanbanHttpServerJvm` — staged-classpath launchers; `-Pjdwp=PORT[,suspend]` attaches a debugger
- `generateForgeAssets` — bakes `src/commonMain/resources/web/{index.html,styles.css,script.js}` into `borg.trikeshed.forge.generated.ForgeAssets` (ByteArray chunk objects, 5000 bytes each) so the Forge HTML/CSS/JS shell ships as a Kotlin-internal asset, not a resource lookup. `commonMain` consumes the generated object; `ForgeApp.kt` / `ForgePersistenceScript.kt` / `index.html` template all reference it via `{{SEED}}`/`{{STYLES}}`/`{{GALLERY}}`/`{{SCRIPT}}` placeholders.

---

## 10. Reading / Recovery Paths (where to look next)

| Need | Files |
|------|-------|
| Algebra cheat sheet | `PRELOAD.md`, `src/README.md`, `lib/Join.kt`, `lib/Series.kt` |
| Job Nexus end-to-end | `JobSupervisorElement.kt`, `JobReducer.kt`, `JobNexusFactory.kt`, `JobNexusBindings.kt` |
| Rete rule engine | `dag/ReteNetwork.kt`, `dag/Rete*.kt`, `dag/BlackboardDag*.kt` |
| Schema → Confix plan | `resources/confix/job-nexus.schema.json`, `ConfixFacetPlan.kt`, `ConfixKit.kt` |
| Couch projections | `couch/CouchStore.kt`, `couch/*Projection.kt`, `couch/ConfixRepositoryView.kt` |
| COW B+Tree | `collections/btree/*`, `JobRepository.kt`, `JobCheckpoint.kt` |
| MultiIndex | `collections/multiindex/*.kt`, `collections/associative/trie/RadixTree.kt` |
| Forge surfaces | `forge/ForgeDoc.kt`, `forge/ForgeBoardFSM.kt`, `forge/ForgeKanbanIngest.kt`, `forge/ForgePersistenceScript.kt` |
| Reactor / choreography | `userspace/reactor/MuxReactorElement.kt`, `context/AsyncContextElement.kt`, `userspace/FunctionalUringFacade.kt` |
| NUID / CCEK fanout | `context/nuid/Nuid.kt`, `context/nuid/NuidFanoutElement.kt` |
| Litebike listener | `litebike/LitebikeListenerElement.kt`, `litebike/ProtocolDetector.kt`, `litebike/taxonomy/Taxonomy.kt`, `jvmMain/litebike/JvmLitebikeBindAdapter.kt`, `jvmMain/litebike/JvmMulticastAdapter.kt`, `jvmMain/litebike/JvmKanbanServer.kt` |
| Blackboard-as-cursor | `blackboard/BlackboardSurface.kt`, `parse/confix/Confix.kt`, `parse/confix/ConfixKit.kt` |
| ManimWM RTS camera | `forge/blackboard/ForgeBlackboardCamera.kt`, `forge/blackboard/ForgeBlackboardInteraction.kt`, `manimwm/` |
| Transport / HTX | `htx/Htx*.kt`, `cli/htx/HtxAria2*.kt` |
| Process reactor | `userspace/reactor/process/ProcessReactorEndpoint.kt`, `userspace/nio/channels/spi/ProcessOperations.kt` |
| Gallery / Pages | `forge/gallery/*.kt`, `ForgeApp.kt`, `resources/web/`, `build.gradle.kts` (`generateForgeAssets`, `generateForgePages`) |

---

## 11. Common Pitfalls (don't relearn these)

| Pitfall | Symptom | Fix |
|---------|---------|-----|
| `Series[T]` vs `MatchGroupCollection.get` collision | `Unresolved reference … receiver type mismatch` | `import borg.trikeshed.lib.get` + `import borg.trikeshed.lib.size` (or `.b(i)` for raw) |
| CursorDriven empty-page placeholder child | `first()` returns empty TEXT block | filter by `kind` (`HEADING_1`, `BULLET`) or add `pageHeading(state)` helper |
| Compose Desktop import fragility | 70-80 import lines; patch breaks | prefer `git checkout <file>` + tiny patches, or `write_file` full rewrite |
| Coroutine polling in `runBlocking` + `Dispatchers.Default` | Only 1 of N events fires | Replace with `Channel<T>` — `trySend` / `for (item in channel)` |
| Dual-truth (Kotlin state + JS mutation) | Silent fork | One runtime authoritative (JVM); other mirrors via reactor/event channel |
| Orphaned submodule (gitmode 160000, no .gitmodules) | CI checkout fails silently | `git rm --cached <path>` |
| `build.gradle.kts` checkout from ref | Local commits lost | Never `git checkout <ref> -- build.gradle.kts` |
| `rm -rf` untracked `??` dirs | Sibling Jules jobs destroyed | Never — they are active work, not stubs |
| macOS mDNS bind with only `SO_REUSEADDR` | `EADDRINUSE` on port 5353 | Try `SO_REUSEPORT` first (runCatching), fall back to `SO_REUSEADDR` |
| `Random.Default` / `nextBits` in commonMain | Native compile failure | Use `Random(0L)` + `nextInt(0, 256)` — KMP-safe |
| `System.currentTimeMillis()` in commonMain | Deprecated / KMP-unsafe | Use `kotlinx.datetime.Clock.System.now().toEpochMilliseconds()` |
| `@Volatile` / `synchronized` in commonMain | KMP compile failure | Use `Mutex` + `withLock` — kotlinx-coroutines is KMP-safe |
| `Charsets.US_ASCII` in commonMain | JVM-only constant | Use `CharArray(n) { bytes[i].toInt().toChar() }.concatToString()` |
| `for (msg in channel)` on `Channel<T>` | Ambiguous iterator / compile error | Use `while (true) { val msg = slot.consume() }` or `channel.consumeEach { }` |
| `runCatching { subscriber.javaClass.methods... }` in commonMain | `javaClass` unresolved on JS/Wasm | Use explicit interface (`LitebikeFanoutEventSink`), no reflection |
| `toSortedMap()` on `groupBy` result | JVM-only stdlib | Use `.keys.sorted()` explicitly |
| `String(bytes, 0, len, charset)` in commonMain | JVM-only constructor | Decode via `CharArray` + `concatToString()` |
| `HtxReactorElement` used as server listener | Exchange-only, does not bind | Use `LitebikeListenerElement` + `JvmLitebikeBindAdapter` — Htx is client-side only |
| `com.sun.net.httpserver` for Kanban server | Framework dependency, not CCEK | Hand-rolled HTTP worker on `LitebikeListenerElement` slot — zero framework |
| Parallel DTO truth (`ForgeAppState` vs Confix doc) | Dual-truth seam, silent fork | Single JSON file → `confixDoc()` → `Cursor` → `BlackboardSurface.project()` — one canonical source |

---

## 12. Contribution Contract (how work lands)

1. **Vertical slice** — failing contract test → minimal production wiring → adjacent/full verification.
2. **Exclusive file ownership** — Jules tasks declare owned paths + forbidden paths; no overlap.
3. **No libs/ references** — root-only, composite builds consume via `includeBuild("../..")`.
4. **No FFI / no Rust linkage** — litebike is conceptual inspiration only; ports are clean-room Kotlin with TrikeShed-local conventions.
5. **Pre-commit** — `git diff --check`, verify no `kotlinx-serialization-json/cbor` dependencies or frontend usage on any target (including JVM), run focused tests.
6. **Evidence** — real test output, generated artifact proof (HTML/WASM), branch + PR with exact commands.

---

## 13. Quick Start Checklist for Day 1

```bash
# 1. Toolchain
sdk install java 25.0.4.1-graal
sdk use java 25.0.4.1-graal
./gradlew --version   # Gradle 9.6.1

# 2. Read the algebra
cat PRELOAD.md
cat src/README.md

# 3. Run a focused test
./gradlew jvmTest --tests "borg.trikeshed.lib.JoinTest" --console=plain

# 4. Inspect the Job Nexus spine
cat src/commonMain/kotlin/borg/trikeshed/job/JobSupervisorElement.kt
cat src/commonMain/kotlin/borg/trikeshed/job/JobReducer.kt
cat src/commonMain/kotlin/borg/trikeshed/job/JobNexusFactory.kt

# 5. Browse the gallery (local)
./gradlew forgePwa            # bakes docs/ then serves http://localhost:8765/
```

---

## Goals and ideals (original notes)

I've also written ideas that describe (some) goals and ideals of the library:  

* [x] strongly immmutable Join aka Pair,Twin,Series aka Array,Series2, Cursors, are all typealiases of Join
    * extending the language through index and other operators happens as a sideeffect of testing new expression
      economies.

    - `myseries[4,3,2,1]` will provide a mapped Series in any order specified, even dupes. similar range indexes are
      available for other types
    - `myseries<T>[(T)->Boolean]` is a shorthand filter expression
    - "banana".toSeries() / 'n' would split series into `s_['ba','a','a']`
    - combine(Series...), and join(Cursor...) will concatenate and widen respectively with underlying binary-search
      index remapping on y,x axis respectively where Cursors are concerned.
    - a handful of nonstandard symbols are used to hint the code for a quick read
        * _l,_a,_s,_m util objects provide e.g. `_l[1,2,3]` for a kotlin List; s_[] is a Series
            * CharSeries is a Series<Char> with ByteBuffer token manipulation methods
            * a LongSeries<T> exists to enable 64 bit indexing e.g. file-IO random access virtualization and other large
              contiguous or sparse things
            * some symbol liberties:
                - `(myseries as Series<T>)` __α__ `{it:T-> foo(it)}` is an infix, lazy .map analog of a series
                - `(myseries as Series<T>).`\`▶\` visually noticable forward-iterator accessor denoting kotlin stdlib
                  collections/functional facade for a given purpose, typically filters, maps, or folds
                - left identity anchors, respectively __\`↺\`__ e.g. "columnname".\`↺\` to functionalize a constant or
                  other
                  value in situations where sometimes a lambda might be generative but constant can be distinctly picked
                  out
                  from in the code

* [x] Cursor lazy and memory-resident Dataframes lending strongly typed columns, with names, splittability,
  combinability, transforms,

* [x] ISAM Columnar Dataframes Storage @see http://github.com/jnorthrup/columnar
    - now with a native port. the jvm rewrite of columnar is also a full rewrite, streamlined and simplified.
    - the kotlin-native ISAM uses linux mmap with readonly memory, compatible with macOS posix until liburing integration.
    - the columnar project has a lot more bells and whistles and is battle hardened
    - the default construction of an IsamVolume are tested to be correct in a single threaded environment
        - [x] the JVM version employs a lock-seek-read-unlock strategy
        - [x] the native version uses [linux] `mmap` with readonly memory.
            - [x] in practice this is compatible with macOS posix until you look into liburing integration, so the uring
              attempt was made a separate linux-only class from the IsamVolume
            - [x] the posix code holds up well under mingw however the mmap calls are significantly different so this
              may warrant a seperate lock-seek-read-unlock strategy for windows, or someone with ambition to port the
              mmap calls (resolved by MingwIsamOperations using seek-read-write)

* [x] Duck-typing CSV-Cursor which includes varchar
  width sizing and narrowing numerical of types and float/integer detection on imported columns. supports
  explicit ISAM transcription on initial scan. heap stores only index to first records for CSV cursors.


* [x] JSON indexer/reifier/path-selector written for simplicity and speed. This is not a serdes library.
    * ~300 lines at time of writing this. no external deps. no reflection.
    * JsonParser.index(Series<Char>) will return a JsElement with CharSeries segments of the top level element.  
      each segment is the complete json object, array, or value including the open/close brackets to recreate
      the json and act as discriminators for the type of the segment.
        * optional depth list param will record how deep each segment is during the single-level scan of the input
        * optional field cutoff param will parse only the first n fields of the top level element. A very specific
          tabular use case drives this and the unparsed elements all come back in one abandoned segment with undefined
          behavior
    * JsonParser.reify(Series<Char>) parse and return the expression as nested maps and arrays and values
        * Js Arrays return as Series<Any?>, Js Objects return as Map<String,Any?> ; all Js Values return as Any?
        * for better or worse, non-string ParseDoubleOrNull not only does a cheap withotu string allocation costs but is
          also the source of parsed nulls when the Double parser falls through.
    * JsonParser.jsPath(Series<Char>,JsPath) ~~ghetto jq~~ will traverse the index to the depth of the path provided.
        * JsPathElement is an Either<String,Int> created by List<*>::toJsPath() extension function
        * optional reified param will return the value at the path reified as a kotlin type else just a segment JsIndex
        * String keys will abort on Arrays but Int keys will fetch the nth index from either a json object or Array


* [x] linux-biased Posix IO utils exist for kotlin-common, jvm, and native (linux only)
    * [x] IO-Uring has been brought in and many tests ported, but not applied knowledgably as yet nor updated to keep
      current with liburing.


* [x]  a handful of missing kotlin-common collections are scattered about, these would be about as warrantable as the
  unit
  tests you might find for them.

---

*End of concept map. When you land a change, update the relevant section above — this doc is the maintenance lineage.*


# build

Toolchain via SDKMAN (preferred when its broker — JFrog — is behaving; it has
had outages, and the Oracle-direct fallback below covers those):

```sh

set -e
export SDKMAN_DIR="$HOME/.sdkman"

if [[ ! -s "$SDKMAN_DIR/bin/sdkman-init.sh" ]]; then
curl -s "https://get.sdkman.io?ci=true" | bash
fi

set +u
source "$SDKMAN_DIR/bin/sdkman-init.sh"

sdk install java 25.0.4.1-graal || echo 'feed down? use the Oracle-direct path in the next section'
sdk install kotlin 2.4.20
sdk install gradle 9.7.0
hash -r
set -u

```

### Linux without SDKMAN (SDKMAN's GraalVM feed has gone dead before)

Download Oracle GraalVM straight from Oracle and lay it into the same
`JAVA_HOME` the build expects. glibc distros (Debian/Ubuntu/Fedora):

```sh
set -e
mkdir -p "$HOME/.sdkman/candidates/java"
curl -fL https://download.oracle.com/graalvm/25/latest/graalvm-jdk-25_linux-aarch64_bin.tar.gz -o /tmp/graalvm.tgz
# x86_64 instead: https://download.oracle.com/graalvm/25/latest/graalvm-jdk-25_linux-x64_bin.tar.gz
mkdir -p "$HOME/.sdkman/candidates/java/25.0.4.1-graal"
tar xzf /tmp/graalvm.tgz -C "$HOME/.sdkman/candidates/java/25.0.4.1-graal" --strip-components=1
ln -sfn 25.0.4.1-graal "$HOME/.sdkman/candidates/java/current"

export JAVA_HOME="$HOME/.sdkman/candidates/java/25.0.4.1-graal"
export PATH="$JAVA_HOME/bin:$PATH"
java -version   # Oracle GraalVM 25.0.4+7.1
```

musl distros (Alpine): the Oracle binaries are glibc-linked and will NOT run
under plain musl or gcompat. Extract a glibc sidecar and shim the JVM tools
through its loader — this is the verified recipe:

```sh
set -e
apk add --no-cache binutils curl

# 1. GraalVM, same layout as above
curl -fL https://download.oracle.com/graalvm/25/latest/graalvm-jdk-25_linux-aarch64_bin.tar.gz -o /tmp/graalvm.tgz
mkdir -p "$HOME/.sdkman/candidates/java/25.0.4.1-graal"
tar xzf /tmp/graalvm.tgz -C "$HOME/.sdkman/candidates/java/25.0.4.1-graal" --strip-components=1
ln -sfn 25.0.4.1-graal "$HOME/.sdkman/candidates/java/current"

# 2. glibc sidecar (Debian libc6, extracted to /opt/glibc — no base-image swap)
curl -fL https://deb.debian.org/debian/pool/main/g/glibc/libc6_2.43-5_arm64.deb -o /tmp/glibc.deb
# x86_64: .../libc6_2.43-5_amd64.deb
mkdir -p /tmp/libc /opt/glibc
cd /tmp && ar x /tmp/glibc.deb && tar xf data.tar.xz -C /tmp/libc
cp -a /tmp/libc/usr/lib/aarch64-linux-gnu/* /opt/glibc/
cp -a /tmp/libc/usr/lib/ld-linux-aarch64.so.1 /opt/glibc/   # x86_64: ld-linux-x86-64.so.2

# 3. Shim every JVM launcher through the glibc loader
G="$HOME/.sdkman/candidates/java/25.0.4.1-graal/bin"
LD=/opt/glibc/ld-linux-aarch64.so.1   # x86_64: /opt/glibc/ld-linux-x86-64.so.2
for b in java javac jar keytool; do
  [ -f "$G/$b" ] && [ ! -f "$G/$b.real" ] && mv "$G/$b" "$G/$b.real"
  printf '#!/bin/sh\nexec %s --library-path /opt/glibc %s/%s.real "$@"\n' "$LD" "$G" "$b" > "$G/$b"
  chmod +x "$G/$b"
done

# 4. Persist and verify
export JAVA_HOME="$HOME/.sdkman/candidates/java/25.0.4.1-graal"
export PATH="$JAVA_HOME/bin:$PATH"
java -version   # Oracle GraalVM 25.0.4+7.1 — verified working on Alpine aarch64
```

kotlin/gradle SDKMAN installs are unaffected by the feed outage; when SDKMAN
itself is unreachable, pin them by hand the same way or use each project's
official installer — the build only needs `JAVA_HOME` (gradlew) plus the
versions in `gradle/libs.versions.toml` (kotlin comes from the build, not the
toolchain).

## Build, in anger

For anyone who doesn't want to know Kotlin lore, doesn't care that `Join<A,B>` is
standing in for half the type system above, and just needs this thing to compile,
test, and run today:

```sh
git clone https://github.com/jnorthrup/TrikeShed && cd TrikeShed

export JAVA_HOME="$HOME/.sdkman/candidates/java/25.0.4.1-graal"
export PATH="$JAVA_HOME/bin:$PATH"

# The gate. Not `./gradlew build` — build also drags in JS/WASM targets, some of
# which sit behind a debt ratchet (gradle/js-target-debt.excludes) and are not
# the thing that tells you the tree is sound. This is:
./gradlew jvmMainClasses --console=plain

# One real, fast test, to confirm the toolchain (not just the compiler) works:
./gradlew jvmTest --tests "borg.trikeshed.lib.JoinTest" --console=plain

# Something you can look at without reading a line of Kotlin — bakes and serves
# the static console/dashboard on http://localhost:8765/:
./gradlew forgePwa

# The actual daemon (Couch-shaped store + Graal console + VM host), foreground:
./gradlew runOroborosDaemon --console=plain
```

There is no framework here — no Spring, no DI container, no annotation
processors, no generated code you can't read in the same file it's used. It's
Gradle + the Kotlin/JVM compiler + hand-rolled HTTP and NIO, full stop. If
`jvmMainClasses` is green, the JVM half of the tree compiles; that is the only
claim "the build works" is making. `-PfocusedTransportSlice=true` is a debt
switch, not a speed-up — it *disables* exclusions on trees that are currently
red, so leave it off unless you're specifically chasing that debt.

If you're here because someone handed you this repo and said "just get it
running," the four commands above are the whole onboarding: gate, one test,
static preview, live daemon. Everything else in this document is for when you
want to know *why* it's shaped this way — start with [§1 Kernel
Algebra](#1-kernel-algebra-the-mental-model) and `PRELOAD.md`.

<img width="1168" height="784" alt="image" src="https://github.com/user-attachments/assets/8af71944-b802-4a2d-883d-7e25208cbc5f" />
