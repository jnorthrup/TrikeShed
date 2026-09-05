"""The one custom skill: how to read this Kotlin codebase for type and concurrency facts.

None of the built-in skills (pdf / spreadsheet / docx) apply — the input is
source code. What the agent needs instead is (a) the scanners, and (b) the
handful of load-bearing facts about this codebase that are not guessable from
the source in front of it.
"""

from pathlib import Path

from predict_rlm import Skill

_MODULES = Path(__file__).resolve().parent.parent / "modules"

INSTRUCTIONS = """How to read this Kotlin codebase for type and concurrency facts.

## Scan, don't parse
There is no Kotlin parser in the sandbox and you do not need one. Every pattern
that matters is lexically regular, and `kotlin_scan` mounts a scanner for each.
Use them to LOCATE, then read the surrounding lines and use predict() to
interpret. Never name a type from a port name — cite a line that produces the
value. `kotlin_scan.scan_all(text, path)` runs every scanner over one file.

## The scanners
- `context_demands(text, path)` — CoroutineContext element reads, with severity
  already classified (`throws` / `silent-degrade` / `optional`) and the verbatim
  error string captured for the hard ones.
- `supervision(text, path)` — SupervisorJob / supervisorScope / catch(Throwable)
  sites, with `mechanism` and a starting `discount`. Trust the mechanism, revise
  the discount once you understand what the site actually supervises.
- `casts(text, path)` — `as?` / `as` sites with the `?:` fallback and a derived
  `on_cast_failure`. The fallback is the finding: it is what silently happens
  when a kind-legal wire delivers the wrong Kotlin type.
- `contracts(text, path)` — `LcncPortContract(...)` literals, with kind-map keys
  already de-suffixed.
- `runner_registry(text, path)` — `"node.type" to LcncNodeRunner { … }` entries.
- `ccek_surface(text, path)` — every PUBLIC member of every type in a file, with
  its owner and root type; locals inside function bodies are excluded.
- `ccek_coverage(surface, seams, everywhere)` — per member: `reached` (an LCNC
  runner file imports the root type AND reads/calls the member), `unreached`,
  `orphan` (no file outside its own imports the root), `plumbing`. Run it with
  seams = every `lcnc/*.kt` and everywhere = the whole tree. Reachability is the
  FACT; whether an unreached member should be a lego is a RULING — propose one
  in `CcekMember.lego` only when the member is a capability (a verb or a read a
  program would want), never for a channel factory or an alias.

## Load-bearing facts about this codebase
These are established; use them as ground truth and spend your effort elsewhere.

- Node I/O is `Map<String, Any?>` end to end. Kinds NEVER reach the executor —
  they constrain which cables may be drawn, never what travels down them.
- The kind universe is exactly five symbols: json, text, id, trigger, num (plus
  "" for generic, and "*" as the wire spelling of generic). `json` is used for
  the overwhelming majority of ports, which is why it carries no information.
- Input gathering keys by the wire's LITERAL toPort, including the "?" suffix.
  That is why runners are full of `inputs["x"] ?: inputs["x?"]`. A required port
  drawn in its "?" spelling reads as unfed and the node is SKIPPED SILENTLY —
  a name-level failure that looks exactly like a type-level one.
- A MANY-cardinality port is `T` when one wire is attached and `List<T>` when
  two are. The same port has two shapes depending on fan-in.
- `Series<T>` is `Join<Int, (Int) -> T>`. It is structurally indistinguishable
  at runtime from any other `Join<Int, Function1>`; do not claim to tell them
  apart. Note also that no node runner emits a Series, Join or Twin — the
  algebra is flattened to Map/List/String/Boolean/Int before reaching a port.
- A scope's coroutine context is exactly the elements composed into it with `+`.
  If an element is not named at the construction site, it is not present, and
  any node reaching a `throws`-severity demand for it will fail at run time.
- `SupervisorJob()` with no argument is a DETACHED ROOT. It reads identically to
  `SupervisorJob(parent)` and behaves oppositely — nothing above can cancel it.
- Sequential execution means no isolation. A supervisor above a sequential walk
  does not make the nodes inside that walk independent of each other.

## Reuse the repo's own type vocabulary
Where a type needs naming, prefer the tokens this codebase already has over
inventing a lattice: `TypeMemento` / `IOMemento` already distinguish
object / array / scalar and carry codecs, `TypeEvidence` infers a memento from
observed text, and `ColumnMeta` is a `Join`-shaped recursive schema. Naming an
existing memento is more useful to the reader than a fresh abstraction.

## Evidence discipline
Every claim carries a `file:line`. When evidence is thin, lower `confidence`
rather than guessing — this report's whole value is that its findings can be
checked, and one confident fabrication costs more trust than ten honest
uncertainties.
"""

kotlin_source = Skill(
    name="kotlin-source",
    instructions=INSTRUCTIONS,
    # networkx is pure-Python and ships in Pyodide's package set; used only for
    # runner -> throw-site reachability.
    packages=["networkx"],
    modules={"kotlin_scan": str(_MODULES / "kotlin_scan.py")},
)

__all__ = ["kotlin_source", "INSTRUCTIONS"]
