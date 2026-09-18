---
name: trikeshed-portable-datalake
description: Doctrine and ground truth for TrikeShed work toward the portable datalake product. Use when touching TrikeShed browser assemblies, the blackboard, LCNC surfaces, the document curator, GraalVM polyglot hosting, or when reviewing agent-authored commits. Covers the Sep 15 assemblies canon, the port-before-delete discipline, the reverted renderer, provenance architecture, and meteorite defense.
---

# TrikeShed portable datalake — session doctrine

## Product north star

A salesman's laptop: portable datalake, hermetic code context, offline-capable,
non-hallucinating ingest and NLP curation, with a marketable-quality RTS-viewable
blackboard and LCNC (no-code) front door. Acceptance is the fundsneeded gap
ledger (`doc/portable-datalake-handoff/gaps-ledger.md`), not anyone's opinion of done.

## The Sep 15 assemblies canon (commit f34c9c3b3)

- **No authored JS. No scripts.** Browser JavaScript exists only as webpack
  output of Kotlin/JS compilations, staged by `stageKotlinJs` into
  `web/kotlin/<app>/<app>.js` and served by the JVM daemon. `*.js` must never
  appear in the source tree. Gradle is the only runner.
- **Factions, not a monolith**: per-app JS compilations `forge`, `documents`,
  `spacegraph` (+`viewserver`), selected by `-PkotlinJsApps`. Each page is a
  thin shell loading `./kotlin/<app>/<app>.js`.
- **JVM is GraalVM only; be prolific with polyglot.** CoreNLP and camel run as
  resident sub-VM polyglot guests in-JVM (see `graal/subvm`, `CoreNlpRuntime`).
- Canonical description: `doc/sep15-assemblies.md`.

## Port-before-delete (the 7,800-line excision)

`f34c9c3b3` deleted ~7,800 lines of hand-written JS. Behavior must be **ported
from git history (last full copy: `feb5af4db`) into commonMain before it is
changed or re-implemented**. Ported so far:

- `borg.trikeshed.landscape.LandscapeNavigation` — camera zoom semantics,
  callout placement, de Casteljau curve clipping, bookmark hash codec
  (from `landscape-navigation.js`)
- `borg.trikeshed.blackboard.BlackboardFeed` — revision-linked SSE acceptance
  rule (from `harness.js`)
- `borg.trikeshed.landscape.LandscapeActivity` — seven-state evidence taxonomy
  for programs, nodes, facts, shared references (from `landscape.js`)
- `borg.trikeshed.provenance.ProvenanceTrace` — bidirectional
  CAS-footing ↔ production-transition walk

Still to port: `graal-terrain.js` category table, `harness-arguments.js`
binding editor, `patch-shake.js` guard, `graal-file-viewer.js` policy,
`harness.js` view-state machine. The DOM/SVG side of any of these lives only in
a js* adapter; decisions stay in commonMain.

## Curator and provenance (integrated 2026-09-18, commits 426017b16/3587de335)

- `DocumentToolReceipt`: stage/tool/implementation/status + exact CAS
  `inputCids`/`outputCids` + `receiptCid` (its own canonical bytes). Retained
  on success, failure, and skip alike.
- `DocumentCurationFacts`: one curation record projects into the Rete
  `documents` partition; every fact carries its board key back-pointer.
- `ProvenanceTrace`: BACKWARD from an outcome → raw byte footings; FORWARD
  from bytes → every derived outcome. Cycle-guarded, depth/budget capped.
  This is the "non-hallucinating" claim engine: every semantic output opens to
  the exact bytes and productions under it.
- Order 13: **every model call goes through the reactor-owned `ModelMux`** —
  no raw provider clients, no ad-hoc credential lanes.

## The b9531f327 lesson

Commit b9531f327 ("Gate document curation…") was reverted in full because its
renderer replaced the spatial medium with a card grid + modal dialog — but its
curator half was legitimate and was re-integrated separately. Lesson: **an agent
can smuggle a medium decision inside an unrelated commit.** Review any UI commit
as a media decision, not a feature. The spatial blackboard page must be a `blackboard`
assembly faction (src/jsBlackboard), not a CSS grid.

## Meteorite defense (Anthropic/agent commit hygiene)

- **Commit messages must canonically describe their content.** "Refactor
  codebase structure" as a message for a 145-file architecture migration is the
  canonical failure; the fix was `doc/sep15-assemblies.md`.
- **Never wholesale-revert on test failure.** Tests are not currency; read
  intent first, revert the offense, keep the legitimate half.
- **Bot branches re-seed purged files**: `.jules/*.md`, `.Jules/palette.md` are
  automation droppings that ride unrelated bolt/palette/sentinel commits. Excise
  them whenever they land (see commit 5f64a141a).
- The branch is a busy multi-session place; rebase before assuming tree state.

## Naming and code contract (abridged from PRELOAD.md / AGENTS.md)

- Zero-cost taxonomical abstractions first: enums, typealiases, value classes.
  No conversational nouns/verbs in names. Public by default.
- Join/Series/Cursor algebra is the house style; don't demote Series to List
  without mutation.
- `TODO()` is the sanctioned gap marker; never hide debt with excluded compiles.
- Do no extra. Finish unfinished code; never induce a false completion.
