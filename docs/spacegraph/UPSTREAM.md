# SpaceGraphJS port ledger

Upstream: [autonull/spacegraphjs7](https://github.com/autonull/spacegraphjs7).
Pin: `ead8c84` (2026-11-07, "cleanup").

The port began on 2026-09-07 (`f71991b59`, "Add SpaceGraph shadow projection to LCNC panels"). Upstream has
185 commits since then: 2 in September, 121 in October and 63 in November through the pin. Of those, 34 touch
the contract files below (`src/types`, `packages/renderer/src`, `packages/adapter/src/diff.ts`).

## Mapping

| Port (TrikeShed) | Upstream at the pin | State |
|---|---|---|
| `src/commonMain/kotlin/narchy/spacegraph/RenderModel.kt` | `packages/renderer/src/index.ts` (`RenderNode`, `RenderEdge`, `RenderModel`, `RenderModelPatch`, `NodeStatus`), `packages/renderer/src/patch.ts` (`mergeRenderModelPatch`), `packages/adapter/src/diff.ts` (`diffRenderModels`) | Current at the pin. The diff counts any differing field as a change; upstream's fingerprint leaves out a node's `visible`, `interactive`, `parent` and `data`. |
| `narchy/spacegraph/Graph.kt` (`NodeKind`, `EdgeKind`, `NodeSpec`, `EdgeSpec`, `GraphSpec`, `GraphCommand`) | `src/types/spec.ts`, `src/nodes`, `src/edges` (full-engine path) | Catalogs match: 43 `NodeKind` entries for upstream's concrete nodes (`Object3DNode`, `BaseContentNode` and `BaseInstancedNode` are abstract; `MermaidNode` is registered from `src/core/TypeRegistry.ts`, not `src/nodes`) and all 10 edges. The spec diverges: upstream `type` is an open string where `kind` is a closed enum; upstream has `parameters` and no `parent`; the camera is `{position, target}` against `GraphCamera(center, zoom, mode)`; `GraphCommand` removes by id where `SpecUpdate` only patches. |
| `narchy/spacegraph/Geometry.kt`, `SceneProjection.kt`, `SpatialView.kt`, `FrameJson.kt`, `CirclePacking.kt` | THREE.js scene, camera and layout code | Reimplemented in Kotlin, not tracked line by line. |

## Not ported

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
follows the code.

## Moving the pin

```
git -C <spacegraphjs7> log --oneline <pin>..main -- src/types packages/renderer/src packages/adapter/src/diff.ts
```

Port or skip each commit, record the new pin and date here, and re-run the conformance check: write a model and
a patch with `RenderModel.json()`/`RenderModelPatch.json()`, type-check them against
`packages/renderer/src/index.ts` with `tsc`, and apply the patch with upstream `mergeRenderModelPatch`.
