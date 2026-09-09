# Agent sleeves — the ethos, and the three we maintain

An **agent sleeve** runs somebody else's coding agent as a guest inside a supervised polyglot isolate, and
turns everything that breaks into tracked work. The donor checkout is never edited: a sleeve directory
shadows donor modules by module identity, so `graalpy-sleeve/hermes/socket.py` shadows `socket` and the
donor stays pristine.

## What a sleeve traps

The bounds are the same for every guest: `allowCreateThread(false)`, no host IO, no process creation, no
native access, and a supervisor-owned `UserspaceBtrfs` subvolume as the only filesystem. Those bounds are
what generate traps.

The distinction that matters, and the one the whole design turns on, is **not** "does it work" but **can
the guest see the failure**:

| | what happens | why it matters |
| --- | --- | --- |
| **guest-catchable** | an `AttributeError`, an `ImportError`, a `RuntimeError` | the caller observes it and degrades — possibly *silently*, which is its own hazard |
| **host-uncatchable** | GraalPy raises a host `IllegalStateException`; `InProcessIsolate.classify` maps it to `GuestFailure.DEAD` and closes the context | no guest `except BaseException` sees it. One library's background worker downs the VM, and **every trap behind it is never observed** |

Uncatchable traps are ranked first for that last reason. An inventory taken while an uncatchable trap is
live is fiction — it stops at the first one.

## How a trap is resolved

Exactly two shapes, and picking wrong is a measured failure mode rather than a matter of taste:

- **withheld** — the capability cannot exist here, so the twin raises what the donor raises when the OS
  refuses (`RuntimeError: can't start new thread`). Callers degrade; the isolate lives.
- **ported** — the capability has a faithful meaning under the bound, so the twin implements it.

Withholding alone is not enough wherever a caller *swallows* the refusal. Hermes measured exactly this:
`logging.handlers.QueueListener.start()` swallowed the withheld thread spawn, boot then "succeeded", and
every log record piled up in a `SimpleQueue` nobody drained — depth grew, the file handler never fired. The
ported listener drains to the same handlers under the same `respect_handler_level` rule, on the only thread
there is.

Missing APIs fail closed. Inert compatibility stubs are worse than the break, because they convert a loud
failure into a silent wrong answer.

## Trap → fork → red test → card

Every unresolved trap owes three artifacts, and `SleeveTddRedKanban` refuses to treat it as work without
them:

1. **a source fork** under the sleeve directory, shadowing the donor module by module identity;
2. **a red test** that fails before the fork exists and passes after;
3. **a card**, born in `todo` — never in `ready`.

Cards land in `todo`, never `ready`. The board's columns are triage/todo/ready/running/blocked/review/done/
archived, and the claim loop dispatches `ready` to a model through Hermes and parks the result in `review` —
so a trap nobody has watched fail would otherwise be handed to a model on the strength of a description
alone. Promoting a card to `ready` is the act of saying "I have seen this break", and the spec is what the
plane judge closes against once it is. Uncatchable traps carry board priority 1, catchable ones 2. Card ids
are derived from the trap id, so regenerating never duplicates.

Each card carries an RFC 2119 spec whose MUSTs are checkable rather than aspirational — a file that exists,
a test that flips, a shape that was chosen — plus `OUT-OF-SCOPE: editing the donor checkout` and
`OUT-OF-SCOPE: loosening the isolate bounds to make the trap disappear`. That second one matters: the
cheapest way to make a trap "pass" is to hand the guest the capability back, which deletes the whole point.

An unpinned sleeve gets one extra card — *pin the donor root* — and every other card for that sleeve
depends on it. You cannot reproduce a break in a checkout you have not named.

## The three sleeves

| sleeve | facet | donor root | state |
| --- | --- | --- | --- |
| `hermes` | GRAAL_PYTHON | `~/.hermes/hermes-agent` | RED — 5 traps resolved, 2 open |
| `pi` | GRAAL_PYTHON | **unpinned** | RED — nothing can be trapped yet |
| `ohmypi` | GRAAL_JS | **unpinned** | RED — and the JS facet has no waist at all |

**hermes** is the one with measured history. After the prelude landed, imports went 1075 → 1255 and isolate
kills 1 → 0; `hermes_cli.main` imports and the console reaches `state=ready`. Its two open traps are the
HTTP client waist (138 module misses; `httpx` 73, `requests` 32) and `sqlite3` (23 modules). Full triage in
`doc/triage/graalpy-sleeve-2026-09-07.md`.

**pi** and **ohmypi** are declared, not yet run. That is deliberate rather than an omission — the manifest
exists so the first boot has somewhere to record what it hits, and the RED verdict names the reason
("donor root not pinned") instead of reporting a trap count it cannot know.

### Open rulings

- **pi**: where is the Pi coding-agent checkout, and is it hosted on GRAAL_PYTHON?
- **ohmypi**: what plays the role of `trikeshed_guest_prelude.py` on the JS facet — a loader hook, a
  bundled shim, or a `Context.Builder` mount? And is ohmypi JS-hosted at all, or a shell over the Python
  sleeve?
- **hermes/http-client**: which host seam backs the guest `requests`/`httpx` waist — userspace.nio or
  ModelMux — and what carries credentials across it?

## Code

- `borg.trikeshed.sleeve.AgentSleeve` — `TrapShape`, `TrapCatchability`, `PolyglotTrap`, `AgentSleeveManifest`
- `borg.trikeshed.sleeve.AgentSleeveRegistry` — the three manifests and the fleet roll-up
- `borg.trikeshed.sleeve.SleeveTddRedKanban` — traps → cards, with `submissions()` in `kanban.submit` shape
- `SleeveWire` — `/api/sleeves`, `/api/sleeves/cards`, `/api/sleeves/{id}`
- `AgentSleeveTddRedTest` — asserts each sleeve is red for the reason it is actually red
