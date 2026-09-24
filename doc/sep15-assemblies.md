# The Sep 15 build — canon description

Commit `f34c9c3b3` ("Refactor codebase structure", 2026-09-15, 145 files, +3,023/−20,725)
was the script excision and the assembly migration: the enforcement of the
house rule (no scripts) and the re-founding of the browser tier on gradle
factions of Kotlin/JS assemblies. Four pillars:

## 1. Per-app JS compilations replace the monolith

`build.gradle.kts` stopped emitting a single executable from `jsMain`. The JS
target now holds a `main` compilation plus one app compilation per faction —
`forge`, `documents`, `spacegraph`, plus `viewserver` — each
`associateWith(jsMainCompilation)`, each `binaries.executable(application)`,
each with its own webpack build (`mainOutputFileName = "<app>.js"`). Selection
is a gradle property: `-PkotlinJsApps=documents,spacegraph`, validated against
the known faction list. Karma dropped the custom electron launcher for plain
`useChromeHeadless()`; `yarn.lock` shed its Electron baggage.

## 2. `stageKotlinJs` — JS starts as Kotlin

A `Sync` task collects each selected app's production webpack output into
`build/generated/kotlinJs/resources/web/kotlin/<app>/<app>.js`, and
`jvmProcessResources` folds it into the JVM's resources. The JVM daemon serves
browser JavaScript that was Kotlin at every step upstream; the same source runs
on the JVM (GraalVM only) and in every assembly.

## 3. Pages are thin shells over the assemblies

- `documents.html` → `./kotlin/documents/documents.js`
- `panels.html` → `./kotlin/spacegraph/spacegraph.js`
- `shell/index.html` → `./kotlin/forge/forge.js`

`graal.html` (−1,643 lines), `kanban.html` (−715), `futon.html`,
`headhunter.html`, `hermes-xterm.html`, `index.html` all slimmed the same way:
inline script out, behavior arrives as compiled assemblies.

## 4. Behavior moved into Kotlin or was adjudicated contraband

New Kotlin in the same commit: `DocumentCurationTasks.kt`, `HeadhunterCuration.kt`,
`DocumentCuratorElement.kt`, `RouteParityGate` rework, corenlp `MANIFEST.tsv`.
Deleted: every hand-written `web/*.js` (~7,800 lines), `shell/app.js`, the
electron launcher. Behavior with a Kotlin home was ported; behavior without one
was removed pending a Kotlin-first port. The blackboard's spatial medium fell
in the second bucket — that is the gap the `board` assembly must close, not a
design verdict on spatial logic.

## The gap and the standing rule

A future `board` faction (src/jsBoard) carries the spatial medium back: pure
view semantics in commonMain (see `borg.trikeshed.landscape.LandscapeNavigation`),
the DOM/SVG adapter as the only jsBoard-resident code, and a thin
`harness.html` shell. The reverted `b9531f327` had the right structure and the
wrong renderer (card grid + modal); the medium, not the structure, was the
offense.
