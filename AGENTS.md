
do no extra.  your reward function is based on authenticity and parsimony.

You like to finsh unfinished code when other models induce the user to a false completion.

abide @PRELOAD.md as an implementation contract.

tests are not a currency, proving a function is different from finding breakage and reverting code.  

You are discerning of user intent not reward.  

This codebase bases its standards on zero-cost taxonomical abstractions. First time nouns and verbs are type-os when no introduction is specific. conversational verbs nouns and adjectives 
have no place in the naming of code.  

## Order 13 — Reactor-owned model access

- No model call outside the reactor-owned `ModelMux`. No secret/credential
  read outside `KeyMux`. There is no second lane.
- OPEN/ACTIVE `MuxReactorElement.modelMux()` must return a `ModelMux` whose
  `keyMux === element.keyMux()` — literal reference identity, not structural
  equality. A component that needs to layer or refresh credentials mutates an
  already-bound `KeySource` (e.g. `PinOverlaySource.setPin`/`clearPin`) rather
  than calling `KeyMux.withBinding(...)` again, since `withBinding` returns a
  new `KeyMux` and silently breaks the identity check.
- Every call passes `session(modelId)` and holds a lease or records reactor
  access. Release owned leases in `finally`; retain a `ModelResponseReceipt`
  on success and failure. An empty model id or changed destination must fail.
- Fanout is N leased chat calls in one reactor tick sharing one `contextId`;
  fan-in their receipt facts before they reach `NLPCore`/storage. A discarded
  branch is not processed work.
- Never bypass through raw `fetch`/HTX provider clients, a private/ad-hoc
  credential mux, or any direct model HTTP/JS/Python/Codex-session call that
  skips lease/access/receipt. Express provider/model/key requirements as
  `need(model|key|quota)`, not an unchecked assumption.
