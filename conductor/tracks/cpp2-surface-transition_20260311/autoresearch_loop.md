# Reloadable Autoresearch Loop

Read this file fresh each turn. Do not treat memory of prior runs as authority over the current repo state.

## Purpose

Run a low-ceremony bounded research loop for the cpp2 surface transition track.

This file is a reload note, not a replacement for:

- `/Users/jim/work/TrikeShed/conductor/tracks/cpp2-surface-transition_20260311/plan.md`
- `/Users/jim/work/TrikeShed/conductor/tracks/cpp2-surface-transition_20260311/expanded_cpp2_spec.md`

Those files remain the authority. This note only gives the execution loop.

## Activation Gate

Before acting, re-read:

- `/Users/jim/work/TrikeShed/conductor/tracks/cpp2-surface-transition_20260311/plan.md`
- `/Users/jim/work/TrikeShed/conductor/tracks/cpp2-surface-transition_20260311/expanded_cpp2_spec.md`
- `/Users/jim/work/TrikeShed/conductor/grok_share_bGVnYWN5_21edd44f-9e25-434b-9bcb-2d036feee2dc.md`

If any of those files changed since the last turn, prefer the file contents over remembered summaries.

## Durable Constraints

- TrikeShed Kotlin text is the reference surface.
- cpp2 is the adapting target.
- Front-end sugar is textual and cheap.
- Normalize early into a small canonical AST.
- Keep semantic objects first and dense lowered views second.
- Do not claim sibling-repo completion without direct inspection there.

## Bounded Corpus

- `/Users/jim/work/TrikeShed/src/commonMain/kotlin/borg/trikeshed/lib/Series.kt`
- `/Users/jim/work/TrikeShed/src/commonMain/kotlin/borg/trikeshed/lib/Series2.kt`
- `/Users/jim/work/TrikeShed/src/commonMain/kotlin/borg/trikeshed/cursor/Cursor.kt`
- `/Users/jim/work/TrikeShed/src/commonMain/kotlin/borg/trikeshed/manifold/Manifold.kt`
- `/Users/jim/work/TrikeShed/conductor/tracks/cpp2-surface-transition_20260311/plan.md`
- `/Users/jim/work/TrikeShed/conductor/tracks/cpp2-surface-transition_20260311/expanded_cpp2_spec.md`
- `/Users/jim/work/TrikeShed/conductor/grok_share_bGVnYWN5_21edd44f-9e25-434b-9bcb-2d036feee2dc.md`

Do not widen the corpus unless the current slice proves it is necessary.

## Mutable Surface

Default mutable surface for this track:

- `/Users/jim/work/TrikeShed/conductor/tracks/cpp2-surface-transition_20260311/expanded_cpp2_spec.md`

Secondary mutable surface, only when truth needs correction:

- `/Users/jim/work/TrikeShed/conductor/tracks/cpp2-surface-transition_20260311/plan.md`

Do not edit sibling repos from this loop unless the turn explicitly switches to direct verification there.

## Slice Rule

Choose one slice only.

Good slices:

- one grammar addition
- one normalization rule
- one canonical node mapping
- one bounded Kotlin text form
- one explicit contradiction between transcript and local spec

Bad slices:

- "finish cpp2"
- "port all of TrikeShed"
- "verify cppfort generally"

## Loop

1. Re-read the activation files.
2. Name one bounded slice.
3. Check the current local spec against the bounded corpus.
4. Make the smallest coherent edit that improves the spec or track truth.
5. Verify that the edit still preserves the durable constraints.
6. Stop and summarize the exact slice result.

## Acceptance Gates

Accept a slice only if all are true:

- semantic and dense layers remain distinct
- vocabulary stays small enough to normalize early
- Kotlin remains the reference surface
- no speculative cppfort completion claim was introduced
- the change is grounded in the bounded corpus

## Preferred Output Shape

When closing a slice, report:

- slice name
- files changed
- exact corpus read
- what became clearer or more precise
- what remains unverified

## Prompt Form

Use this note by explicitly telling the agent:

```text
Read conductor/tracks/cpp2-surface-transition_20260311/autoresearch_loop.md and act under it for one bounded slice only.
```

## Stop Conditions

Stop instead of pushing further when:

- the current slice is complete
- the next step would require widening the corpus
- a sibling-repo claim would need direct verification in that repo
- the evidence is speculative rather than local
