# ohmypi → GraalJS sleeve

**Status: RED — donor root not pinned, and GRAAL_JS has no sleeve infrastructure at all.**

Two distinct reasons this is red, and they are not the same work:

1. No ohmypi checkout is named, so nothing can be run and nothing can be trapped.
   `AgentSleeveRegistry.OHMYPI.donorRoot` is `null`.
2. Unlike GraalPy, the JS facet has no import waist yet. `HermesSingleLanguageTddRedTest`'s
   `jsSingleLanguageIsRedUntilSleeveExists` has been asserting this since before this directory existed —
   there is no JS equivalent of `HermesPythonPort.importInVm`, no inventory walk, and no guest prelude.

Item 2 is the larger job and blocks item 1: a sleeve needs somewhere to install a twin before it is worth
pinning a donor.

## Why the JS waist is not the Python waist

The Python prelude works because stdlib `threading` copies `_thread.start_joinable_thread` into a module
global *at import*, so a twin installed after that import is never seen — the waist has to run before the
entry module. A JS sleeve has no equivalent import hook by default; module identity is resolved by the
loader, and what plays the role of `trikeshed_guest_prelude.py` still has to be decided.

**Wants a ruling:** whether the JS waist is a loader hook, a bundled shim layer, or a
`Context.Builder` mount — and whether ohmypi is hosted as JS at all, or is a shell over the Python sleeve.

## The rule

Same as every sleeve: each trapped incompatibility becomes a source fork under this directory, a red test
that fails before the fork and passes after, and a card. Resolve as **withheld** or **ported**; never as an
inert stub.
