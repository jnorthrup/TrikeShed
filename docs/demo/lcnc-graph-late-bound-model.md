# LCNC wrappers as a late-bound graph

Status: implemented 2026-09-01 on `board/add-verb-and-mcp-handshake`, uncommitted.
Every name below exists in the tree. Every number below was measured on the
daemon that booted from this tree at 13:58 (pid 80672, scratch home under
`$TMPDIR/oroboros-home`).

## The claim

A node type is a **wrapper**: a contract (the shape a port sees) over a
behaviour (what runs). Until today the two were joined by a string key at
boot and nothing else: 120 contracts in one compiled table, runners in eleven
registry providers, five kinds compared by equality. Three things now resolve
**late** — at request or run time, from data — instead of at compile time:

1. **Cable types** are exact and inferred. A port names the exact type its
   runner moves; a ring port that declared nothing takes the type of the cable
   plugged into it; a pass-through's `T` is fixed by its first cable. No
   subtyping, no wildcards (`LcncKinds`, `LcncTypeCheck.Resolver`).
2. **Bindings** — how and by what each type is bound — come from one pass
   over the assembled registry per request, with the runner's class name as
   provenance. That is the one reflective act; no annotations
   (`LcncWrappers.bindings`).
3. **Composites** — a stored program whose top ring declares `scope.in` /
   `scope.out` is a wrapper. Its contract is derived from those formals, it
   joins the vocabulary as data, and a node whose *type* names it binds
   through `subprogramLoader` when the walk reaches it (`LcncComposites`,
   `LcncVocabulary`, `LcncRunner.runRing`). The loader now reads the presets
   and then `panels/<name>` attachments, so the user's own constructions are
   the corpus.

## The notation

CABLES ARE NEVER UNTYPED. A cable carries exactly one type, the type of what
flows through it, fixed by its source port, and the sink must be that type.
The surface does not have to show the type; it must obey it. There is no
subtyping between kinds: a wire whose two ends differ is not a cable.

```
json text id num trigger      # the five Confix slots: used only where a runner really moves that Confix value
List<TurnFact>                # the exact CCEK type, wherever the runner says one (beliefs.review.facts)
Any                           # a sink whose runner declares Any (display echoes anything) — exact, not generic
T                             # a type variable on a pass-through: coalesce<T>(a?: T, b: T): T
*                             # a ring port that declared nothing and has no cable yet — unresolved, never a wildcard

beliefs.review  shape  List<TurnFact> = {verb, ok, context, object}
                              # kindShapes on the contract that introduces the type:
                              # a json.value literal whose rows carry these keys IS a List<TurnFact>

:n1#tick  lcnc:feeds  :n2#trigger .     # a port is a resource; a wire is one triple
(feeds preset-curator n0 value n3 facts)  # the same wire as a tuple
```

Inference, not wildcards. A ring parameter that declared nothing takes the
type of the first cable plugged into it, and every cable inside the ring obeys
that type. A `scope.in`'s value is the ring parameter; a `scope.out`'s value
takes what is plugged into it; a yield carries what fed its `scope.out`. A
type variable is fixed per node by the first cable into any of its `T` ports.
Where nothing can be resolved, nothing is claimed, which is not the same as
accepting anything. (`LcncTypeCheck.Resolver`; `cableTypes` gives every
cable's exact type in wire order.)

What that found, in the shipped contracts, the first time it ran: six ports
declared `json` whose runners move Strings or a type variable —
`council.seat.prompt`, `.content`, `.labeled`; `text.fold.parts`, `.text`;
`ruling.parse.text` (in and out); `council.record.transcript`; `coalesce` all
three ports; and `display.x`, whose runner echoes anything and is `Any`. Each
was a cable the old five-bucket rule had let through.

## On the blackboard

Everything LCNC is an entry on the daemon's one blackboard
(`ConfixBlackboard`), not a store beside it (`LcncBlackboard`):

```
lcnc/vocabulary          contracts + composites, kinds, acceptance, refinements, bindings
lcnc/program/<name>      { name, document, cables: [{from, to, type}], violations }
```

One writer, `LcncPublisher`, and one precedence: a preset owns its name, the
user's `panels/<name>` attachments follow. THE BOARD IS THE AUTHORITY. A
source seeds an entry and overwrites it only when the source's own cid moves
(`sourceCid` on the entry); an entry edited on the board keeps its
`sourceCid` and is obeyed as edited; an entry with no source at all is obeyed
too, and one that arrived raw through `/blackboard/assert` is reconciled on
first load so the board never holds an untyped cable. Every put is guarded by
a canonical comparison, so `/blackboard/facts` carries a delta only when
something moved.

The kanban module publishes everything at open; a panel save
(`POST /api/panels/<name>`) publishes its entry and then refreshes the whole
board (a panel with formal ports is a new composite), returns the entry's
violations, and refuses a preset's name with 409. The run seam loads through
the publisher and obeys the entry's violations; only an inline document is
checked fresh. `GET /api/panels/<name>` falls back to the board's document, so
a board-only program opens in the canvas. The canvas subscribes to
`/blackboard/facts` and, on a `lcnc/vocabulary` delta, re-hydrates and
rebuilds its palette; its `scope.in`/`scope.out` bindings defer to the daemon's
resolver, and Store reports the cables the daemon refused. `/api/lcnc/facts`
serves the tuples verbatim.

## Measured

The verification test is `scripts/blackboard-gate.sh`, in the shape of the
preset gate: it boots a scratch daemon on 8896 the way a newcomer boots one,
never touches 8888, and checks every claim below payload by payload —
fourteen daemon checks, then six in the rendered canvas through headless
Chrome when node and playwright-core are present. It passed on 2026-09-01 at
20:1x from this tree. Before that, five independent verification lanes ran
against the daemon booted at 19:18 (pid 36040) and a critic looked for what
they had not proved; the gate carries the critic's four structural points.

```
scripts/blackboard-gate.sh
  PASS lcnc/vocabulary on the board: 125 contracts, same count as /api/lcnc/contracts
  PASS lcnc/program/<name> for all 18 presets; cables typed 461, unresolved 6, violations 0
  PASS the save produced deltas: lcnc/program/gate-twice seq 21, then lcnc/vocabulary seq 22
  PASS POST /api/panels/preset-curator → 409 name_is_a_preset
  PASS POST /api/lcnc/run gate-bug → 400 type_check_failed (the entry's violations are obeyed)
  PASS a board-only program runs, its raw entry reconciled on load, and opens via /api/panels
  PASS an entry edited on the board is obeyed: preset-scope-inner returns "edited-on-the-board"
  PASS canvas: refusesJsonIntoTurnFacts, acceptsTurnFactsIntoAny, scopeInDefers, paletteRebuiltOnDelta
blackboard gate: PASS
```

| what | result |
|---|---|
| on the board | `lcnc/vocabulary` (125 → 128 contracts as lanes saved composites) and `lcnc/program/<name>` for all 18 presets, every entry with document, typed cables and violations |
| cables on the 18 presets | 467, of which 461 typed and 6 unresolved (`scope.in`/`scope.out` values with no declared kind and no cable); 0 violations |
| a save's deltas | `POST /api/panels/verify-twice` → two SSE facts 65 ms apart: `lcnc/program/verify-twice` then `lcnc/vocabulary`; the contracts route then lists `verify-twice` bound as composite |
| a preset's name | `POST /api/panels/preset-curator` → 409 `name_is_a_preset` |
| the curator no-op wire, saved then run | save returns the violation; `POST /api/lcnc/run` → `400 type_check_failed`: `beliefs.introspect.field emits json, beliefs.review.facts wants List<TurnFact>` |
| a program that exists only on the board | asserted via `POST /blackboard/assert`, no preset, no attachment; `POST /api/lcnc/run` → `outputs.d.x == "from-the-board"` |
| a user composite by type | `verify-outer` → `{"c":{"out":"hi"}}` |
| the rendered canvas | `kindsCompatible('json','List<TurnFact>') === false`; `('List<TurnFact>','Any') === true`; `scope.in` value defers (`*`); a second tab's composite save rebuilt the first tab's palette in 596 ms; Store reported the refused cable |
| tuples served | 2,746 (`/api/lcnc/facts`), 129 bindings, 128 `nodeType` |
| tests | 77 across 9 classes, all pass; `commonTest` **does** run under `jvmTest` (the 2026-09-01 handoff said otherwise); `LcncPresetsGateTest` hangs under `jvmTest` and was left out |

What the critic said was still unproved, and what changed because of it: the
loader let a source clobber a board edit (now `sourceCid`, see above); the run
seam recomputed instead of obeying the entry (now it obeys); a raw assert
could leave untyped cables on the board (now reconciled on load); the canvas
could not open a board-only program (now the panel route falls back to the
board). Still true and stated: typing is enforced before a run, not at run
time; twelve of the eighteen presets were type-checked but not executed by the
lanes; `preset-pairs` answered but its model reply shows the pairs never
reached the prompt, which is a preset defect, not a typing one.

## Reading the two handoffs

`/tmp/hi` (state + next steps) is mostly right and was used as the guide.
Checked against the tree: no `@Lcnc*` annotation existed; `storedProgramLoader`
read presets only, so a user-authored ring was unreachable by name; the
daemon on 8888 was serving a kind hierarchy that existed in no file and no
commit — the phase-2 work had been `git restore`d but `build/` and the
running process kept it. Restarted from source before any verification.

`/tmp/hi1` (the annotation plan) has one premise that does not hold: runners
are lambdas keyed by strings in maps built at boot. A lambda has no
declaration site to annotate, so "annotate the runners" means re-homing 120
lambdas into classes and scattering the one vocabulary across fifteen files.
The class name already says which file bound the type — that is the
provenance blame needs, and it is one `javaClass.name` per registry entry.
What survived from the plan: reflection once, the user-defined corpus, and
serving provenance beside the contract instead of a separate dump.

A second writer landed the plan literally during this session
(`LcncAnn.kt`, `LcncReflectionSeed.kt`, `/api/lcnc/seed-dump`), then went
quiet at 13:55. It duplicated the `(binding …)` provenance, its exemplar
`PanelVoteSeedRunner` threw if ever executed, and its `storedPrograms` read
the `lcnc/` attachment prefix while panels are saved under `panels/`, so that
count was always 0. Curated out on Jim's instruction at 19:2x: the three files
and the 71-line route are set aside, uncommitted, under the session job
directory (`abandoned-seed/`), not deleted.

## The glut

Jim, mid-session: "we have a glut of code to support what seems like a graph
problem associated to less code." Measured:

| file | HEAD | now | what it is |
|---|---|---|---|
| LcncContracts.kt | 1098 | 1116 | a graph written as Kotlin constructor calls |
| LcncTypeCheck.kt | 198 | 210 | loops that ask the graph "does this wire type" |
| LcncMating.kt | 260 | 262 | loops that ask "what mates here", ranked |
| LcncRdf.kt | 146 | 169 | the graph, projected — used by nothing until today |
| LcncKinds.kt | 0 | 149 | closure over subClassOf, as loops |
| LcncWrappers.kt | 0 | 159 | binding + composite edges, as loops |
| LcncAnn.kt + LcncReflectionSeed.kt | 0 | 146 | a second provenance pass |
| KanbanModule route region | ~25 | 131 | three JSON shapes of the same graph |

About 2,200 lines to hold and query a graph of 1,250 triples. Each function
is a query the graph already answers:

| function | the query |
|---|---|
| `LcncKinds.accepts(a, b)` | `a rdfs:subClassOf* b` |
| `LcncMating.compatibleTypes(port)` | `?t lcnc:inKind_?p ?k . kind(port) rdfs:subClassOf* ?k` |
| `LcncMating.autoWire` | the same, restricted to two nodes, `COUNT = 1` |
| `LcncMating.rankedCandidates` | `COUNT(?w) WHERE ?src lcnc:feeds ?dst` over the corpus, grouped by target type |
| treeshake open sockets | `?p a lcnc:InPort ; lcnc:required true . FILTER NOT EXISTS { ?x lcnc:feeds ?p }` (already written in LcncRdf's comment) |
| `LcncComposites.hasEffect` | `?prog lcnc:contains+ ?n . ?n a ?t . ?t rdfs:subClassOf lcnc:Effect` |
| bindings / blame | `?t lcnc:boundBy ?class` |

## In tuples

Jim, after the table above: no evidence we are in tuples further than when
tuples were demanded. Correct — the model above answered its questions with
loops over data classes. It now answers them from tuples.

The substrate is the one the daemon already runs on: `KifKnowledgeBase`
(`kif/Kif.kt`) — `assert`, `query(pattern)` returning variable bindings,
`subclass` closure — the bank the SUMO spine, `nal.mint` and `state.freeze`
write to. `LcncFacts` tells the vocabulary into it as relations and asks
everything back as patterns:

```
(nodeType T) (label T "title") (source T) (sink T) (wide T) (effect T)
(input T p?) (inKind T p K) (output T p) (outKind T p K)
(cardinality T p MANY) (functions T p) (function T p "fn")
(param T name) (paramDefault T name "v") (paramOption T name "o") …
(kind K) (subclass K P)                 ; the dotted-name notation, told once
(introduces T K) (shape K key)
(binding T how "provenance")
(node G n T) (ring G n parent) (feeds G n p m q)
```

| question | the pattern |
|---|---|
| does this wire type | `(subclass K L)`, closed — `LcncFacts.accepts` |
| what mates here | `(inKind ?T ?p ?K)` filtered by accepts — `compatibleInputs` |
| auto-wire two nodes | `(outKind from ?p ?K)` × `(inKind to ?q ?L)` — `autoWire` |
| mate-menu evidence | `(feeds ?g ?n port ?m ?q)` ⋈ `(node ?g ?n S)` ⋈ `(node ?g ?m ?T)`, counted — `feedsInto` |
| is this program an effect | `(node g ?n ?T)` ∧ `(effect ?T)` — `programHasEffect` |
| who binds this type | `(binding T ?how ?by)` — `bindingOf` |
| kinds, hierarchy, acceptance, shapes, literals | one pattern each |

`LcncTypeCheck.check`, `LcncMating`, `LcncComposites`, `LcncRdf.ontology`
and the `/api/lcnc/contracts` route consult the facts; none of them loop the
table for a kind question any more. `GET /api/lcnc/facts` serves the tuples
verbatim as a `.kif` file. Measured on the daemon booted from this tree at
14:39 (pid 89952): 2,705 tuples — 125 `nodeType`, 141 `inKind`, 192
`outKind`, 218 `param`, 126 `binding`, 339 `node`, 467 `feeds`, 2
`subclass`, 4 `shape` — and `/api/lcnc/contracts`, every field of it a
query over them, answers in 0.20 s (the bank is a linear scan; indexing is
its job, not LCNC's). The stored curator wire is still refused at the run
seam with the same detail, the user composite still runs by type, and the
rendered canvas still refuses the cable.

One thing the restart exposed, not caused: panels saved through
`/api/panels` did not survive it. The store is `CouchStoreFactory.casBacked`
— the bytes are in the CAS, the `panels/<name>` path index is not durable in
this home. The user-defined corpus is therefore durable exactly as far as
that index is; the three `smoke-*` panels had to be re-saved to re-run the
proofs above. Test-pinned (`LcncFactsTest`): every one of the
120 compiled contracts round-trips through `toKifFile` → `parse` →
`contract(type)` **field for field**, the type checker runs on a vocabulary
read back from text, and mating is answered from a three-type vocabulary
authored as text with no Kotlin contract at all.

What the numbers say, honestly: the loops are gone (`LcncKinds` 149 → 53,
`LcncMating` 262 → 236) but `LcncFacts` is 261 lines, about 110 of them the
serializer that mirrors every contract field into tuples and back. Net, this
session is still up. The serializer exists so the last step can delete the
big number: the 1,116-line Kotlin table becomes
`src/commonMain/resources/lcnc/vocabulary.kif`, and `LcncContracts.all()`
becomes `parse` plus the picklists the enums tell at load (`VmFacet`,
`VmTrust` — the DRY the 2026-09-01 commit established, which a static file
would otherwise regress). That step is blocked on one missing piece —
commonMain has no expect/actual resource reader — and is the card on the
board, not started here because it touches every target's source set.
