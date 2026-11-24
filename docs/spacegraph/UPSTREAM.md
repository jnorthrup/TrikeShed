# SpaceGraphJS port ledger

Upstream: [autonull/spacegraphjs7](https://github.com/autonull/spacegraphjs7).
Pin: `125b369` (2026-11-23, "plan"). Previous pin: `ead8c84` (2026-11-07, "cleanup").

The port began on 2026-09-07 (`f71991b59`, "Add SpaceGraph shadow projection to LCNC panels"). Counted by
command from the previous pin `ead8c84` to `main` `125b369` (259 commits, `git log --oneline ead8c84..main | wc -l`):
20 touch the contract files below (`git log --oneline ead8c84..main -- src/types packages/renderer/src packages/adapter/src/diff.ts | wc -l`),
90 have a subject starting `feat`. The per-commit verdicts are in `commits.tsv` and `features.tsv` in the scratch goal
directory (`/Users/jim/.hermes/profiles/src-trikeshed/cache/scratch/goals/spacegraph/`).

## Mapping

| Port (TrikeShed) | Upstream at the pin | State |
|---|---|---|
| `src/commonMain/kotlin/narchy/spacegraph/RenderModel.kt` | `packages/renderer/src/index.ts` (`RenderNode`, `RenderEdge`, `RenderModel`, `RenderModelPatch`, `NodeStatus`), `packages/renderer/src/patch.ts` (`mergeRenderModelPatch`), `packages/adapter/src/diff.ts` (`diffRenderModels`) | Current at `125b369`. `RenderNode` and `RenderEdge` carry `emphasis`, and `RenderNode` carries `widgets` and `widgetSlots` (ported in `711b868`). `RenderEdge` carries `data`. The diff counts any differing field as a change; since `25dda4b` upstream's fingerprint covers `visible`, `interactive`, `parent`, `data` and edge fields too, so both sides report the same changes. |
| `narchy/spacegraph/Graph.kt` (`NodeKind`, `EdgeKind`, `NodeSpec`, `EdgeSpec`, `GraphSpec`, `GraphCommand`) | `src/types/spec.ts`, `src/nodes`, `src/edges` (full-engine path) | Catalogs match: 43 `NodeKind` entries for upstream's concrete nodes (`Object3DNode`, `BaseContentNode` and `BaseInstancedNode` are abstract; `MermaidNode` is registered from `src/core/TypeRegistry.ts`, not `src/nodes`) and all 10 edges. The spec diverges: upstream `type` is an open string where `kind` is a closed enum; upstream has `parameters` and no `parent`; the camera is `{position, target}` against `GraphCamera(center, zoom, mode)`; `GraphCommand` removes by id where `SpecUpdate` only patches. |
| `narchy/spacegraph/Geometry.kt`, `SceneProjection.kt`, `SpatialView.kt`, `FrameJson.kt`, `CirclePacking.kt` | THREE.js scene, camera and layout code | Reimplemented in Kotlin, not tracked line by line. |
| `narchy/spacegraph/navigation/Descent.kt` | `src/navigation/descent.ts` | Ported at `125b369` (round three, row 4). Tests: `test/descent.test.ts`, 5 cases in `narchy/spacegraph/navigation/DescentTest.kt`. |
| `narchy/spacegraph/navigation/Follow.kt` | `packages/navigation/src/follow.ts` | Ported at `125b369` (round three, row 4). Deviation: takes `hasViewport` and newest node id, not the renderer viewport and `GraphNode` objects. Tests: `packages/navigation/src/follow.test.ts`, 13 cases in `FollowTest.kt`. |
| `narchy/spacegraph/layout/ScopeLayout.kt`, `ScopeSimulation.kt`, `ScopePolicy.kt`, `FieldProjection.kt`, `Scope.kt` | `src/layout/scope.ts`, `scope-layout.ts`, `scope-simulation.ts`, `scope-policy.ts`, `field-projection.ts` | Ported at `125b369` (round three, row 1). Deviations are in the file headers: plain interfaces, `ScopeSpawn` enum, `kotlin.math` in place of `Math.*`. No upstream tests import `scope-simulation`, `scope-policy` or `field-projection`; they are unchecked beyond the gate compile. Upstream `src/layout/scope.ts` has no test of its own: `packages/kernel/src/scope.test.ts` tests a different `deriveScope` in the kernel package. |
| `narchy/spacegraph/navigation/DeepLink.kt` | `packages/navigation/src/deep-link.ts` | Ported at `125b369` (round three, row 4). Tests: `packages/navigation/src/deep-link.test.ts`, 12 cases in `DeepLinkTest.kt`. |
| `narchy/spacegraph/zoom/Zoom.kt` | `src/core/zoom.ts` | Ported at `125b369` (round three, row 2). No upstream test; unchecked beyond the gate compile. |
| `narchy/spacegraph/zoom/ZoomBands.kt` | `src/zoom/ZoomBands.ts` | Ported at `125b369` (round three, row 2). Tests: `test/zoom-bands.test.ts`, 8 cases in `ZoomBandsTest.kt`. Deviation: `ConfigurationError` is a local class; timestamp from `kotlinx.datetime`. |
| `narchy/spacegraph/zoom/ScaleDomain.kt` | `src/zoom/ScaleDomain.ts` | Ported at `125b369` (round three, row 2). No upstream test; unchecked beyond the gate compile. |
| `narchy/spacegraph/representations/Rung.kt` | `src/core/rung.ts` | Ported at `125b369` (round three, row 6). No upstream test; unchecked beyond the gate compile. |
| `narchy/spacegraph/representations/LadderBase.kt` | `src/core/ladder.ts` | Ported at `125b369` (round three, row 6). No upstream test; unchecked beyond the gate compile. Deviation: `LadderBase` takes the hysteresis as a nullable Double. |
| `narchy/spacegraph/representations/RepresentationLadder.kt` | `src/representations/ladder.ts` | Ported at `125b369` (round three, row 6). No upstream test; unchecked beyond the gate compile. Deviation: `LadderNode` extends `LadderEntry`; `build` is a lambda. |
| `narchy/spacegraph/representations/Playback.kt` | `src/representations/playback.ts` | Ported at `125b369` (round three, row 6). No upstream test; unchecked beyond the gate compile. Deviation: ease is a string key into the curve map. |
| `narchy/spacegraph/representations/EdgeLadder.kt` | `src/edges/ladder.ts` | Ported at `125b369` (round three, row 6). No upstream test; unchecked beyond the gate compile. Deviation: `EdgeRungStyle` is a data class of nullable fields. |

## Not ported

- `368b9d0` (`feat(scopes)`): the opt-in animated recursive scope engine (`ScopesPlugin`). A new Kotlin subsystem: lead.
- Renderer-side commits (`e47fe6d`, `cd27d94`, `f449190`, `e248f39`, `534c28c`, `bdeb13c`, `9c83535`, `07e9bad`, `fba09bd`): skipped, see `commits.tsv`.
- The remaining `feat` commits are app or pi UI work (`features.tsv`): no contract field, or a new design (lead).

- Renderer side of the contract: `GraphRenderer`, `RendererCapabilities`, `RendererViewport`, `FitOptions`,
  `RenderTheme`, `RendererEventMap`. These belong to whichever contract renderer draws the model; the host needs
  only the event payloads when a route accepts UI events back.
- `packages/kernel` (dependency-free graph substrate: hyperedges, versioned kernels, append-log sessions, LWW
  workspaces, `KernelCodec`), `packages/adapter` (`ModelAdapter`, `adapterConformance`), layouts, plugins,
  vision and input.

## Upstream documentation drift

`README.md` (§ Renderer contract) and `docs/host-integration.md` still tell hosts to call
`negotiateContract(version)` against `CONTRACT_VERSION`. Upstream removed both in `1c72fd5` (2026-10-25,
"Remove contract version negotiation"), and `docs/capabilities.md` says there is no `CONTRACT_VERSION`. The port
follows the code. At `125b369` the constant `LIBRARY_CONTRACT_VERSION = '1.4.0'` is still exported from
`packages/config/src/library.ts`, a name the port does not carry.

## Moving the pin

```
git -C <spacegraphjs7> log --oneline <pin>..main -- src/types packages/renderer/src packages/adapter/src/diff.ts
```

Port or skip each commit, record the new pin and date here, and re-run the conformance check: write a model and
a patch with `RenderModel.json()`/`RenderModelPatch.json()`, type-check them against
`packages/renderer/src/index.ts` with `tsc`, and apply the patch with upstream `mergeRenderModelPatch`.
