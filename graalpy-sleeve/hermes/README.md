# Hermes → GraalPy sleeve

This directory shadows modules from the live `~/.hermes/hermes-agent` checkout without copying or
forking that checkout. A path has normal Python module identity:

- `pydantic/__init__.py` shadows module `pydantic`
- `agent/transport.py` shadows module `agent.transport`

`./gradlew portHermesPython` rebuilds a trimmed-string ontology spine every run. Its semantic zoom is:

1. `L0`: ready / blocked / deferred
2. `L1`: native blocker or upstream / sleeve origin
3. `L2`: top-level Python package
4. `L3`: full module path

A sleeve module may replace a banned module only when it exists here and its own required-import graph
is clean. GraalPy still runs with host access, IO, process creation, environment access, polyglot access,
and native access disabled. Its only filesystem is a supervisor-owned `UserspaceBtrfs` subvolume;
language-internal resources are admitted separately, while host files and sockets remain denied.
Missing APIs fail closed; do not add inert compatibility stubs.

## The polyglot twin

A sandbox bound is not the same thing as a missing module, and the two fail differently. When
`allowCreateThread(false)` refuses a `threading.Thread.start()`, GraalPy raises a **host**
`IllegalStateException` that no Python `except` can catch and that fails the isolate closed
(`GuestFailure.DEAD`) — one library's background worker takes the whole VM with it. The twin is what
turns that into something the guest can reason about, in one of two shapes:

- **withheld** — the capability cannot exist here, so the twin raises what CPython raises when the OS
  refuses (`RuntimeError: can't start new thread`). Callers degrade instead of dying.
- **ported** — the capability has a faithful single-threaded meaning, so the twin implements it.
  Withholding alone is not enough where a caller *swallows* the refusal:
  `logging.handlers.QueueListener.start()` does, the boot then "succeeds", and every log record piles
  up in a queue nobody drains. The ported listener hands each record straight to its handlers.

`trikeshed_guest_prelude.py` carries both and is imported at the import waist, before the entry module —
stdlib `threading` copies `_thread.start_joinable_thread` into a module global at import, so a twin
installed after that import is never seen.

Curated waists currently include:

| module | shape | what it carries |
| --- | --- | --- |
| `trikeshed_guest_prelude` | withheld + ported | `_thread` spawn verbs; `logging.handlers.QueueListener` |
| `socket` | withheld + ported | constants and byte-order helpers are real; every verb that reaches the wire fails closed |
| `ssl` | withheld | the exception hierarchy and constants asyncio binds at import; no crypto |
| `contextvars` | ported | `ContextVar.name`, which GraalPy 25.3 omits from an immutable, unsubclassable builtin |
| `yaml` | ported | TrikeShed YAML/Confix parser through a host delegate |
| `dotenv` | ported | pure GraalPy over the Btrfs VFS |

Directories with no `__init__.py` resolve as PEP 420 namespace packages (`plugins/platforms`,
`plugins/browser`, `scripts`, `evals/compaction` …); the importer synthesises the spec, since the guest
has no filesystem for `PathFinder` to walk.

`hermes_cli.main` boots and `hermes_cli.oneshot` runs a real turn on this waist. The turn then fails
closed on `requests`/`httpx`, which are not staged into the guest and would reach banned `socket`
anyway — network providers stay unavailable until userspace.nio delegates replace them.

Daily outputs:

- `build/reports/hermes-python-port.json` — full inventory, ontology spine, four zoom levels, daily delta
- `build/reports/hermes-graalpy-sleeve-queue.json` — top blocker roots ranked by impacted modules
