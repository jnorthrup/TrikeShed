# lcnc-depth

An RLM that models LCNC patch-cable connections **below** the five-string kind
layer, and reports the violations the current checker is structurally unable to
see.

## Why

`LcncTypeCheck.acceptedBy` (`LcncTypeCheck.kt:159-163`) is exact string equality
over a five-symbol vocabulary — `json`, `text`, `id`, `trigger`, `num` — plus a
`generic` wildcard. That vocabulary is **nominal over `Any?`**:

- `LcncNodeRunner` is `suspend (LcncNode, Map<String,Any?>) -> Map<String,Any?>`
  and the executor never reads a kind (`grep outputKinds` in `LcncRunner.kt`
  returns nothing).
- `json` is declared for a `String`, a `Boolean`, an `Int`, a `Map` **and** a
  `List`; `trigger` is declared for a `Long`. Of 320 kind declarations, 208 are
  `json`.
- So a kind-legal wire routinely lands a `Map` in an `as? String`, which yields
  `""` — and a council seat rules on an empty record with no error anywhere.

Two further layers are invisible to it. `CCEK.reactorScope` carries
`MuxReactorElement` but **not** `HtxElement`, so a node reaching one of the nine
`HtxKey` throw sites fails at run time after its `started` receipt is already
written. And supervision is not what it reads as: `ArticulatedNode`'s fan-out
isolates siblings with `catch (Throwable)` over a plain `launch`, not a
`SupervisorJob`, while two scopes use bare `SupervisorJob()` — detached roots a
parent cancel never reaches.

## What it produces

Stage 1 (`DepthModeler`) derives a `DepthModel` from the Kotlin sources:

| layer | field | what it records |
|---|---|---|
| K — Kotlin | `kind_bindings`, `port_types` | the real Kotlin type on each cable, the consumer's cast, and what silently happens when it fails |
| C — CCEK | `context_demands`, `scope_provisions` | which `CoroutineContext.Element` a node needs, and whether the host scope actually provides it |
| S — supervision | `supervision` | the isolation MECHANISM and the coordination `discount` it genuinely buys |

Stage 2 (`DepthAdjudicator`) judges programs against that model and returns a
`DepthReport` of `Violation`s — each carrying
`invisible_to_current_checker`, which is the field that matters.

## Use

```python
from predict_rlm import File
from lcnc_depth import DepthModeler, DepthAdjudicator

model = await DepthModeler().acall(sources=[File(path=p) for p in kotlin_files])
report = await DepthAdjudicator().acall(
    model=model, programs=[File(path=preset)], host_scope="ccek-assembly"
)
```

The model is stable until the Kotlin changes — derive it once, cache it, and run
the cheap stage per-edit. `LcncDepth` runs both when you have no cached model.

## Static scan — usable today, no LLM

The half that needs no comprehension runs standalone and is CI-safe:

```bash
python -m lcnc_depth.scan_repo /path/to/TrikeShed/src            # report
python -m lcnc_depth.scan_repo … --json                          # raw findings
python -m lcnc_depth.scan_repo … --fail-on-suspicious            # gate (exit 1)
```

Current findings on this tree:

| | |
|---|---|
| vocabulary | 119 contracts, 320 kind declarations — **`json` is 65% of them** |
| context | 13 hard demands; **12 unsatisfiable under the CCEK assembly scope** (8 × `HtxKey`, 3 × `FileOperations.Key`, 1 × `ParseScopeKey`) |
| supervision | 10 suspicious parentless `SupervisorJob()` of 17; 94 catch-based isolation sites |
| casts | 1035 sites, **682 degrading silently** |

The 12 unsatisfiable demands are the headline: those nodes cannot run under the
scope `/api/lcnc/run` gives them, and nothing checks before the walk starts.
Parentless sites are classified by position, so a `default-parameter` or the
`else` of `if (parent == null)` is not reported as a defect.

## CCEK decomposition — which of the engine a program can reach

The plane is a small set of classes (`CCEK`, `ArticulatedNode`, `UserContext`,
`CausalReteTable`, …). Two scanners answer, member by member, whether a program
can reach each one:

- `ccek_surface(text, path)` — every PUBLIC declaration in a file with its
  owner and root type. Declarations count only at the owner's body depth (a
  `val` inside a function is a local), constructor properties count, anything
  `private`/`protected`/`internal` does not, `*ForTest` is a test seam.
- `ccek_coverage(surface, seams, everywhere)` — per member: **reached** when an
  LCNC runner file brings the root type into scope (an import from the plane's
  package, a star import, a qualified use, or the same package) AND reads or
  calls the member; **unreached**; **orphan** when no file outside its own
  imports the root at all; **plumbing** for test seams, nested `Key` objects and
  stdlib overrides. Import discipline is what keeps kotlinx's `SupervisorJob(`
  from being credited to the CCEK interface of the same name.

Reachability is the fact. Whether an unreached member SHOULD be a lego is a
ruling, and rulings live in `scan_repo.CCEK_RULINGS` with their reason —
substrate (channel factories, the boot binding), alias (`stop()` is `cancel()`),
carried (a sealed case the verb node constructs), orphan vocabulary
(`Seat.kt`, `SupervisorJob.kt`, doc/ccek-consistency-pass.md §4) — and one
that came from reading the donor: `ArticulatedNode.start()` cannot revive a
drained node because `signalIn` is closed for good, so exposing it would promise
a restart the engine does not perform. Unreached + unruled = **GAP**.

```bash
python -m lcnc_depth.scan_repo /path/to/TrikeShed/src --fail-on-ccek-gap   # gate (exit 1 on a gap)
```

The first run on 2026-09-05 found 12 gaps: `UserContext.{activate, deactivate,
loadPolyglotFacts, queryPolyglot, predictModel, tableTest, createGraphicalFlow,
spreadsheetVeneer, adaptParadigm}` and `ArticulatedNode.{isActive,
childScopeCount, markdownProjectionCount}`, plus `requireCcekScope` ruled a
lego. Thirteen `ccek.*` node types followed (`CcekNodes.kt`: vitals, choreograph,
activate, lineage, query, polyglot.load, polyglot.query, predict, table.test,
flow, veneer, paradigm, validate); the scan then read 228 members, 91 reached
(from 51), 0 gaps, and `preset-shake` grew from 121 palette types / 754
sockets to 134 / 858, every socket still closed by Shake.

## The scanners stand alone

`lcnc_depth/modules/kotlin_scan.py` is stdlib-only and is mounted into the RLM
sandbox, where `dspy` does not exist. Package exports are therefore lazy — you
can import and test the scanners without the agent stack:

```python
from lcnc_depth.modules import kotlin_scan
kotlin_scan.scan_all(source_text, "LcncContracts.kt")
```

Validated against the real tree, where it independently reproduces the numbers a
separate code exploration arrived at: 119 contracts, the exact five-kind
universe, kind frequency `json 208 / text 41 / trigger 36 / id 33 / num 2`, 13
cardinality declarations (8 MANY, 5 ONE), and the parentless `SupervisorJob()`
sites in `CCEK.kt`. It then went further than that exploration did: `CCEK.kt:321`
is a *default parameter*, so it is a root only when the caller supplies no scope
— benign — while `CCEK.kt:431` is inline and genuinely detached.

## Tests

```bash
PYTHONPATH=. python3 -m pytest tests/test_smoke.py -q
```

No network, no API key, no Pyodide. 17 pass; the three that exercise the DSPy
surface skip cleanly when `predict_rlm` is absent.
