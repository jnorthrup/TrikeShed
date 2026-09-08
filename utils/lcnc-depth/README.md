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

Two further layers need separate evidence: context construction and supervision.
A hard context read throws if its key is absent when that read executes. A list
of such reads cannot establish which palette runner reaches them, which host
scope it inherits, or what a provider wrapper installs. The historical
hardcoded assembly-key comparison below establishes none of those facts.

## What it produces

Stage 1 (`DepthModeler`) derives a `DepthModel` from the Kotlin sources:

| layer | field | what it records |
|---|---|---|
| K — Kotlin | `kind_bindings`, `port_types` | the real Kotlin type on each cable, the consumer's cast, and what silently happens when it fails |
| C — context | `context_demands`, `scope_provisions` | model-derived requirements and provisions, which need source or runtime validation |
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

The static scanners run standalone using the standard library. From the repo
root on this machine:

```bash
PYTHONPATH=utils/lcnc-depth /opt/homebrew/bin/python3.14 -m lcnc_depth.scan_repo src
PYTHONPATH=utils/lcnc-depth /opt/homebrew/bin/python3.14 -m lcnc_depth.scan_repo src --json
PYTHONPATH=utils/lcnc-depth /opt/homebrew/bin/python3.14 -m lcnc_depth.scan_repo src --fail-on-palette-key-gap
```

No model invocation, dependency installation, or Gradle build is needed. Python
3.9 is incompatible. `--include-tests` includes test sources; the palette audit
otherwise excludes both test filenames and test source sets such as `commonTest`.

### Palette-to-key correspondence

`palette_key_audit` is separate from both historical gates. Its authority is
`borg.trikeshed.lcnc.LcncContracts.all()`, not every constructor in the tree and
not a node-name prefix. `ccek` is a package/acronym; it is not a key identity.

The September 7 source snapshot contains **148 palette entries**: 143 exact
invocation enum identities, `scope` using `LcncScopeFrame.Key`, and four explicit
exceptions: `scope.in` and `scope.out` operate on the enclosing frame; `note` and
`program.ref` are presentation. Composite metadata describes stored programs
using frames; composites are not silently added to the compiled palette count.
The previous 150-constructor result included two constructors outside `all()`.
The data-class declaration was already excluded. Dynamic constructor arguments
are now retained correctly instead of being overwritten by an unknown named
argument such as `isEffect`.

The audit records:

- Every literal entry of `all()`, resolving string constants across packages,
  qualified constants such as `ProjectNodes.READ` and `PromptNodes.GET`, and
  constant concatenation such as `SubVm.LEGO_PREFIX + "tika"`. Duplicate,
  computed, missing, or unsupported palette expressions remain visible.
- Declared singleton key objects, companion keys and aliases, and enum entries
  across **all scanned packages**. Inherited key declarations are followed,
  including the generic service wrapper. A class implementing `Key` is not
  itself a singleton. Symbol names in this report identify source declarations;
  they do not implement runtime key equality.
- `LcncPortContract.context` and explicit branches in
  `LcncContextContract.of(type, composite)`. A fallback annotation never supplies
  missing correspondence. Structural exceptions retain their reason and key.
- A bounded invocation source chain: `LcncNodeRunner.run` selects a key by exact
  type, invokes `key.construct(...).execute()`, and `LcncNodeElement.execute`
  calls `runner.execute(...)` inside `withContext(supervisor + this)`. The key
  factory and element's typed key property must also be present. Merely adding
  an enum, or mentioning these calls in another method or a comment, does not
  satisfy the checks. Scope construction and structural handling have separate
  executor evidence; presentation exemptions require explicitly empty ports.
- Constructor calls, context-call candidates, element key properties, context
  reads, service `.require()` calls, literal runner bodies, `LcncServiceBinding`
  declarations, and `boundLcnc` defaults. Nested provider bindings are retained.
  A provider variable whose key cannot be resolved remains explicitly unresolved.

Each finding carries a source path, line, and snippet. JSON contains `palette`,
`keys`, `element_bindings`, `construction_sites`, `installation_sites`,
`demand_sites`, `context_metadata`, `executor`, `structural_paths`,
`service_metadata`, `runner_sites`, and one audit `rows` entry per palette entry.
`gaps` lists problematic palette rows; `summary.gaps` also includes vocabulary
issues, duplicate types, extra/unresolved invocation keys, unsupported metadata,
and source-read errors. The CLI exits 1 for these under
`--fail-on-palette-key-gap`, or 2 for an invalid source root.

**Invocation correspondence is not service fulfillment.** Even a report with
zero correspondence gaps retains unresolved transitive service requirements for
every invocation row. `observed-source-chain` describes lexical source evidence,
not a Kotlin build or runtime result. Reads inside a literal runner or provider
body are associated only with that body; all other reads remain inventory.
There is no inferred transitive call graph, scope inheritance proof, lifecycle
verification, or claim that a registered runner is available on every host.

The scanner handles explicit declaration forms, imports/aliases, literal
arguments, and the current metadata/dispatch API. Arbitrary Kotlin expressions,
reflection, overload resolution, typealiases, generated sources absent from the
input root, and external dependency declarations are not fully resolved. Context
calls with opaque arguments are candidates, not proof of installed keys.

### Historical scans

`contracts` and `kind_frequency` describe constructor sites across the input
tree, not the palette. `hard_demands` is the historical lexical read scanner.
`hard_demands_outside_assumed_assembly` replaces the misleading JSON field
`unsatisfiable_under_ccek_assembly`; `assembly_assumption` explicitly marks the
hardcoded list as unproven. `--fail-on-suspicious` keeps the historical gate
decision: suspicious supervision plus reads outside that list. It does not
establish missing runtime services. Parentless `SupervisorJob()` positions and
cast scans retain their historical classifications.

## CCEK decomposition — which of the engine a program can reach

The historical package surface is a set of classes (`CCEK`, `ArticulatedNode`, `UserContext`,
`CausalReteTable`, …). Two scanners answer, member by member, whether a program
lexically references each one:

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

This is lexical member reachability, not palette/key correspondence or a
transitive call graph. Whether an unreached member SHOULD be a lego is a
ruling, and rulings live in `scan_repo.CCEK_RULINGS` with their reason —
substrate (channel factories, the boot binding), alias (`stop()` is `cancel()`),
carried (a sealed case the verb node constructs), orphan vocabulary
(`Seat.kt`, `SupervisorJob.kt`, doc/ccek-consistency-pass.md §4) — and one
that came from reading the donor: `ArticulatedNode.start()` cannot revive a
drained node because `signalIn` is closed for good, so exposing it would promise
a restart the engine does not perform. Unreached + unruled = **GAP**.

```bash
PYTHONPATH=utils/lcnc-depth /opt/homebrew/bin/python3.14 -m lcnc_depth.scan_repo src --fail-on-ccek-gap
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

The companion module `lcnc_depth/modules/palette_audit.py` is also stdlib-only;
`audit({source_path: source_text, ...})` returns the new audit independently of
the historical scanners. Neither module invokes the agent stack.

## Tests

```bash
cd /Users/jim/work/TrikeShed/utils/lcnc-depth
PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 /opt/homebrew/bin/python3.14 -m pytest tests -q
```

No network, no API key, no Pyodide. Regression fixtures cover inaccurate counts,
constant references, singleton identity, broken invocation paths, structural
exceptions, provider bindings, missing mappings, and independent CLI gates.
The three DSPy-surface tests skip when `predict_rlm` is absent.
