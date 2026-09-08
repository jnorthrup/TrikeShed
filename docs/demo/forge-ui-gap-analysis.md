# Forge UI gap analysis

> **Historical survey:** ownership and route findings on this page predate the
> WAL-backed `BoardStoreElement` and default `KanbanModule`. Use the
> [Kanban Board Guide](guide-kanban-board.md) for the live HTTP surface and the
> [Marketability + MCP Kanban Audit](marketability-kanban-mcp-audit.md) for
> reconciled board/MCP status. The ranked items below remain provenance, not a
> current execution queue.

Goal: Realize a unified, integrated Forge UI with live data binding, widget rendering, and coherent cross-platform organization.

Root gap: Forge has rich data models (30+ gallery widgets, blackboard camera, kanban signals, view proofs) and platform window managers (8 targets), but no unified rendering pipeline connecting them. Two duplicate ForgeWindowManager interfaces exist. The widget catalog defines previewTokens that nothing renders. The blackboard has 3D camera/force layout data but no canvas drawing. The shell generates HTML but has no live data binding to kanban state.

Sub-gap tree:
  No unified ForgeWindowManager interface
    ├─ forge/window/ForgeWindowManager.kt (25 lines, launch/bind/inject/dispatch/snapshot)
    └─ forge/shell/spi/ForgeWindowManager.kt (14 lines, CCEK Element, bind/inject/dispatch/snapshot)
  No widget rendering pipeline
    ├─ ForgeGalleryCatalog defines 30+ widgets with previewTokens
    ├─ ForgeGalleryRenderer renders catalog as HTML cards (not live widgets)
    ├─ GalleryRenderer (JS) renders dynamic card types (text/image/kanban/code)
    └─ ForgeGalleryPrinter renders text grid for JVM terminal
  No live data binding
    ├─ ForgeApp.renderHtml generates static seed JSON
    ├─ ForgeKanbanConduit projects signals but has no UI sink
    └─ ForgePersistenceJs exists but not wired to UI updates
  Blackboard has no canvas rendering
    ├─ ForceLayout computes positions but nothing draws them
    ├─ ForgeBlackboardCamera has 2D/3D pose but no render loop
    └─ LineCasGraph/LineCasRtsView provide data but no SVG/Canvas output
  No Forge server HTTP integration on JVM
    ├─ KanbanHttpServerJvm exists
    ├─ NodeHttpForwarder exists for JS
    └─ No HTTP endpoint serves Forge UI to browser

1. Merge duplicate ForgeWindowManager interfaces into one CCEK-aware interface — debt: two incompatible interfaces at forge/window/ForgeWindowManager.kt:3 and forge/shell/spi/ForgeWindowManager.kt:5 with different signatures (String vs ScriptSnippet, suspend vs non-suspend) — ROI: 8.0 (impact 5 / effort S=0.5)
2. Wire ForgeKanbanConduit signal output to ForgeApp HTML seed — debt: ForgeKanbanConduit.kt:41 accepts signals but ForgeApp.kt:34 renders static seed with no live update path — ROI: 5.0 (impact 5 / effort M=1.0)
3. Implement ForgeGalleryWidget preview renderer for browser DOM — debt: ForgeGalleryCatalog.kt:56 defines 30+ previewTokens but ForgeGalleryRenderer.kt:25 only renders catalog cards, not live widget previews — ROI: 5.0 (impact 5 / effort M=1.0)
4. Add blackboard SVG renderer consuming ForceLayout positions — debt: ForceLayout.kt:17 computes node positions but nothing renders them to the #blackboard-surface div in ForgeApp.kt:156 — ROI: 5.0 (impact 5 / effort M=1.0)
5. Wire KanbanHttpServerJvm to serve ForgeApp.renderHtml — debt: KanbanHttpServerJvm.kt exists but ForgeApp.renderHtml at ForgeApp.kt:34 has no HTTP endpoint serving it to browsers — ROI: 4.0 (impact 4 / effort M=1.0)
6. Implement ForgeBlackboardInteraction drag/zoom on canvas — debt: ForgeBlackboardInteraction.kt exists but has no connection to the DOM event loop in the shell — ROI: 4.0 (impact 4 / effort M=1.0)
7. Add ForgePersistenceJs live sync to ForgeKanbanConduit — debt: ForgePersistenceJs.kt exists but ForgeKanbanConduit.kt:36 onEvent has no JS-side persistence writeback — ROI: 4.0 (impact 4 / effort M=1.0)
8. Implement GalleryRenderer integration with ForgeGalleryCatalog — debt: GalleryRenderer.kt:9 renders dynamic cards but ForgeGalleryCatalog.kt:54 has catalog data that GalleryRenderer never consumes — ROI: 3.0 (impact 3 / effort M=1.0)
9. Add ForgeShellConfig theme switching (dark/light) — debt: ShellConfig.kt exists but ForgeApp.kt:118 hardcodes dark theme (#090D13) with no toggle — ROI: 3.0 (impact 3 / effort S=0.5)
10. Wire ForgeViewProofProjection to blackboard section — debt: ForgeViewProofProjection.kt:17 produces verified receipts but ForgeBlackboardView has no section for view proof display — ROI: 3.0 (impact 3 / effort M=1.0)
11. Implement ForgeDoc workspace document model — debt: forge/doc/WorkDrain.kt exists but there is no ForgeDoc data model for workspace documents (title, content, attachments) — ROI: 3.0 (impact 3 / effort M=1.0)
12. Add keyboard navigation to Forge blackboard — debt: KeystrokeTraceTest.kt exists testing keystrokes but ForgeBlackboardInteraction has no keyboard event handlers — ROI: 2.0 (impact 2 / effort M=1.0)
13. Implement ForgeGalleryPrinter live preview mode — debt: ForgeGalleryPrinter.kt:16 renders static text but has no interactive/scrollable terminal UI — ROI: 2.0 (impact 2 / effort L=2.0)
14. Add Forge blackboard section for CAS blob inspection — debt: ForgeGalleryCatalog.kt:225 defines cas.blob/cas.dedup/cas.attachment widgets but no blackboard section displays CAS content — ROI: 2.0 (impact 2 / effort M=1.0)
15. Wire ForgeComposeFactory to JVM window with Compose multiplatform — debt: ForgeComposeFactory.kt:7 delegates to forge.shell.main() but has no Compose desktop integration — ROI: 1.5 (impact 3 / effort L=2.0)
