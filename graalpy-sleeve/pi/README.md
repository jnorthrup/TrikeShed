# Pi → GraalPy sleeve

**Status: RED — donor root not pinned.**

This directory shadows modules from a Pi coding-agent checkout the way `graalpy-sleeve/hermes` shadows
`~/.hermes/hermes-agent`: by module identity, never by forking the donor checkout in place.
`pydantic/__init__.py` shadows `pydantic`; `agent/transport.py` shadows `agent.transport`.

Nothing can be trapped yet, because no checkout is named. `AgentSleeveRegistry.PI.donorRoot` is `null`,
which is what makes the sleeve RED, and `SleeveTddRedKanban` emits a `pin the pi donor root` card that
every other card for this sleeve depends on.

## What the first boot will meet

The guest runs under the same four bounds as Hermes — `allowCreateThread(false)`, no host IO, no process
creation, no native access, with a supervisor-owned `UserspaceBtrfs` subvolume as the only filesystem.
Those four are seeded as UNRESOLVED traps so the first run has somewhere to record what it actually hits.

Two of them are **host-uncatchable**: thread and process spawn arrive as host exceptions no guest `except`
can see, the isolate is classified `DEAD`, and every trap behind them stays hidden. Resolve those first or
the inventory below them is fiction.

## The rule

A trap is resolved in exactly one of two shapes, and choosing wrong is a real failure mode:

- **withheld** — the capability cannot exist here, so the twin raises what the donor raises when the OS
  refuses. Callers degrade; the isolate lives.
- **ported** — the capability has a faithful meaning under the bound, so the twin implements it.
  Withholding is not enough wherever a caller *swallows* the refusal. Hermes measured this:
  `logging.handlers.QueueListener` swallowed a withheld thread spawn, boot "succeeded", and every log
  record piled up in a queue nobody drained.

Missing APIs fail closed. Do not add inert compatibility stubs.
