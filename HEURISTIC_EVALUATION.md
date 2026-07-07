# TrikeShed Forge UI — Heuristic Evaluation Contract

**Source**: Nielsen's 10 Usability Heuristics mapped to Forge implementation  
**Scope**: `src/commonMain/kotlin/borg/trikeshed/forge/ForgeApp.kt` + `ForgePersistence.kt` (JS runtime)  
**Target**: Dark-themed (#090d13) three-pane SVG surface — rail/editor/graph-pane + board-pane with causal graph, fractal zoom (0.55–2.8x), CCEK-driven projections

---

## 1. Visibility of System Status
*System keeps users informed through appropriate feedback within reasonable time.*

| Assertion | Implementation | Status | Evidence |
|---|---|---|---|
| **HEU-001** Reactor status strip renders `lastEventKind`, `lastEventTimestampMs`, `cacheStoredCount` | `ForgeAppReactorState` (L61-69) + `renderReactor()` (L1372-1408) | ✅ SATISFIED | `statusChip('items'...)`, `statusChip('last', state.reactor.lastEventKind)` |
| **HEU-002** KanbanEvent stream visible as activity trail (recent signals, taxonomy nodes) | `ForgeAppReactorState.recentSignals`, `recentTaxonomyNodes` + `status-trail` pills | ✅ SATISFIED | `formatReactorEvent()` renders `[forge] CardMoved · Implement API gateway → In Progress @ 14:32:11` |
| **HEU-003** Cache hydration source & timestamp shown (localStorage / indexeddb / cache-storage) | `state.cache.hydrationSource`, `hydrationTimestampMs` | ✅ SATISFIED | `status-note`: `Local activity only · hydrated from indexeddb · snapshot 14:30:02 · last save 14:31:15` |
| **HEU-004** Graph pane shows live node/link counts | `graph-stat-nodes`, `graph-stat-links` + `updateGraphStats()` | ✅ SATISFIED | Updated on `seedGraphFromCausalData()` + `renderGraph()` |
| **HEU-005** Spatial zoom level displayed with semantic depth label | `zoomLabel.textContent = spatialDepthLabel(zoom)` | ✅ SATISFIED | Labels: `workspace shell` (zoom<0.8), `lane geometry` (<1.25), `card topology` (<1.85), `fractal checklist detail` (≥1.95) |

---

## 2. Match Between System and the Real World
*System speaks user language, not internal jargon.*

| Assertion | Implementation | Status | Evidence |
|---|---|---|---|
| **HEU-006** Use-case names are user-facing patterns (not CCEK/MuxReactorElement) | `defaultForgeUseCases()` (L96-125): `brief-board`, `research-dossier`, `release-room`, `mesh-ops` | ✅ SATISFIED | Display names: "Project brief + board", "Research dossier", "Release room", "Mesh / reactor ops" |
| **HEU-007** No internal jargon in user-visible labels (CCEK, MuxReactorElement, FacetedSignal) | HTML/JS uses only: "Use cases", "Work items", "RTS / Causal Graph", "RTS / fractal field", "Kanban board" | ✅ SATISFIED | Verified: no "CCEK", "MuxReactorElement", "FacetedSignal" in rendered text |
| **HEU-008** PageNotes provide contextual guidance per use-case | `ForgeAppUseCase.pageNotes` (L56) + rendered in editor | ✅ SATISFIED | e.g., "Capture the brief at page scope, spin execution items into the board, and keep every checklist line attached to the same card." |
| **HEU-009** Board columns use real-world names (Backlog, In Progress, Review, Done) | `ForgeBoardFSM.loadDefault()` columns | ✅ SATISFIED | `KanbanColumn(backlog, "Backlog")`, `inprog, "In Progress"`, `review, "Review"`, `done, "Done"` |
| **HEU-010** Priority uses familiar terms (critical, high, medium, low) | `CardPriority` enum + `renderEditor` select options | ✅ SATISFIED | No numeric codes exposed |

---

## 3. User Control and Freedom
*Clearly marked "emergency exit" to leave unwanted state.*

| Assertion | Implementation | Status | Evidence |
|---|---|---|---|
| **HEU-011** Reset workspace button clears all local state | `#reset-workspace` click → `clearPersistedWorkspace()` + reseed | ✅ SATISFIED | L259-266: removes localStorage, IndexedDB, cache; reseeds from `seed` |
| **HEU-012** Focus-mode toggle: "Whole board" (zoom 0.82) | `#focus-board` → `focusMode='board', zoom=0.82, centerBoard()` | ✅ SATISFIED | L267-273 |
| **HEU-013** Focus-mode toggle: "Selected card" (zoom ≥1.9) | `#focus-selected` → `focusMode='selected', zoom=max(current,1.9), focusSelected()` | ✅ SATISFIED | L274-280 |
| **HEU-014** Zoom slider as escape hatch (0.55–2.8) | `#zoom-slider` input → `clamp(value, 0.55, 2.8)` | ✅ SATISFIED | L281-286 |
| **HEU-015** Keyboard shortcuts for power users (accelerators) | **MISSING** — no keyboard event listeners for zoom, focus, reset | ❌ VIOLATION | No `keydown` handlers for `+/-` zoom, `b` board focus, `s` selected focus, `r` reset |

---

## 4. Consistency and Standards
*Users don't wonder if different words/actions mean the same thing.*

| Assertion | Implementation | Status | Evidence |
|---|---|---|---|
| **HEU-016** Dark theme tokens consistent across all panes | `:root` CSS vars: `--bg:#090d13`, `--pane:#111824`, `--ink:#dbe7f3`, `--blue:#7aa2f7`, `--cyan:#7dcfff`, `--green:#9ece6a`, `--amber:#e0af68`, `--red:#f7768e` | ✅ SATISFIED | L491-503 used in `.panel`, `.rail`, `.editor`, `.graph-pane`, `.board-pane`, `.spatial-shell`, `.status-strip` |
| **HEU-017** Panel/section-block/card pattern reused across rail, editor, graph-pane, board-pane | `.panel` + `.section-block` + `.usecase-card`/`.nav-card`/`.board-card`/`.item-card`/`.dialog-card` | ✅ SATISFIED | Shared `border:1px solid var(--line)`, `border-radius:16px/18px/20px`, `box-shadow:var(--shadow)` |
| **HEU-018** Button styles consistent (primary/ghost/status) | `.btn`, `.btn.primary`, `.ghost-btn`, `.status-btn` | ✅ SATISFIED | Shared base: `border:1px solid var(--line2)`, `border-radius:12px`, `min-height:38px` |
| **HEU-019** Status chip pattern reused (reactor strip + graph status) | `.status-chip` with `.dot` + `.label`/`.value` | ✅ SATISFIED | Reactor: items/signals/stored/last/cache; Graph: "Force sim active", "Graph: N nodes, M links" |
| **HEU-020** Input/textarea/select styling unified | `.title-input`, `.notes-input`, `.check-input`, `.page-notes`, `.dialog-field input/textarea/select` | ✅ SATISFIED | Shared `border:1px solid var(--line2)`, `border-radius:14px`, `background:rgba(8,12,18,.88)` |

---

## 5. Error Prevention
*Design prevents problems rather than just messaging them.*

| Assertion | Implementation | Status | Evidence |
|---|---|---|---|
| **HEU-021** CCEK `applySignal` idempotency via WAL + snapshot rotation | `ForgePersistence.kt`: `appendWal()`, `recordMutation` → WAL append → rotation at 100 entries | ✅ SATISFIED | `normalizeMutationRecord` includes full snapshot; `persistWorkspaceSnapshot` writes checkpoint |
| **HEU-022** Kanban projection 3-column schema guardrails (backlog/inprogress/done) | `ForgeBoardFSM.loadDefault()` enforces canonical columns; `toKanbanBoard()` falls back to canonical | ✅ SATISFIED | L174-196 + `ForgeDoc.toKanbanBoard()` L249-342 |
| **HEU-023** Provenance chains for undo via `BlackboardContext.provenance` | `BlackboardContext` carries `source`, `timestamp`, `transformations` list | ✅ SATISFIED | `BlackboardSurface.kt` L91-97, `provenance()` factory |
| **HEU-024** Card move guarded by column existence | `moveItem()` checks `target` column exists before move | ✅ SATISFIED | L843-858: `columns[index + step]` guard |
| **HEU-025** Delete confirmation / undo affordance | **PARTIAL** — delete is immediate (`deleteItem` L834-841) with no undo toast or confirmation | ⚠️ PARTIAL | Only `recordReactor('SignalFacetReduced', 'Deleted...')`; no "Undo" snackbar or WAL rollback UI |

---

## 6. Recognition Rather Than Recall
*Minimize memory load by making objects/actions/options visible.*

| Assertion | Implementation | Status | Evidence |
|---|---|---|---|
| **HEU-026** Sidebar rail always shows use-cases + work items | `#usecase-root` + `#nav-root` persistent in `.rail` | ✅ SATISFIED | L398-422: `renderUseCases()` + `renderNav()` on every state change |
| **HEU-027** Checklist detail attached to cards (visible in editor + spatial) | `ForgeAppItem.checklist` → `renderEditor` checklist section + `renderSpatial` intimate zoom overlay | ✅ SATISFIED | L1174-1226 (editor) + L1709-1726 (spatial intimate) |
| **HEU-028** Causal graph co-visible with board (graph-pane) | `.graph-pane` with force-directed SVG + seed demo | ✅ SATISFIED | L426-449: `graph-spatial-shell` + `graph-spatial-root` |
| **HEU-029** Selected item highlighted across all three panes | `state.selectedItemId` drives `.active` class on nav-card, board-card, editor dialog, spatial card | ✅ SATISFIED | `renderNav` L965, `renderBoard` L1329, `renderEditor` L1042, `renderSpatial` L1648 |
| **HEU-030** Column names visible in board + spatial lane headers | `column.name` in board head + spatial `text` at lane top | ✅ SATISFIED | `renderBoard` L1310-1312, `renderSpatial` L1623-1626 |

---

## 7. Flexibility and Efficiency of Use
*Accelerators for experts, unseen by novices.*

| Assertion | Implementation | Status | Evidence |
|---|---|---|---|
| **HEU-031** Zoom as expert accelerator (wheel + slider + fit/center) | Wheel on `.spatial-shell` (L1731-1738), slider (L281-286), Fit/Center buttons | ✅ SATISFIED | `bindSpatialGestures()` + zoom slider + `#focus-board`/`#focus-selected` |
| **HEU-032** Spatial state model (zoom/offset/focusMode) persisted | `ForgeSpatialState` (L72-77) + saved in `saveState()` | ✅ SATISFIED | `spatial: {zoom, offsetX, offsetY, focusMode}` round-trips via localStorage |
| **HEU-033** CCEK `subscribeAgent` for programmatic control | **NOT IN FORGE UI** — this is a reactor/agent API, not user-facing | N/A | N/A (backend capability) |
| **HEU-034** Keyboard shortcuts (accelerators) | **MISSING** — same as HEU-015 | ❌ VIOLATION | No `keydown` handlers; expert users must use mouse for all actions |
| **HEU-035** Drag-and-drop card move (spatial + board) | `DragStarted/DragOver/DragDropped` in `ForgeBoardFSM` + spatial drag camera | ✅ SATISFIED | `bindSpatialGestures()` (L1729-1775) + board move buttons |

---

## 8. Aesthetic and Minimalist Design
*No irrelevant/rarely-needed info; progressive disclosure.*

| Assertion | Implementation | Status | Evidence |
|---|---|---|---|
| **HEU-036** Spatial zoom hides detail until requested (4 depth tiers) | `renderSpatial` L1600-1603: `far` (zoom<0.9), `mid` (<1.45), `near` (<1.95), `intimate` (≥1.95) | ✅ SATISFIED | Far: dots only; Mid: card rects + title; Near: status/priority; Intimate: notes + checklist orbit |
| **HEU-037** Status-chip minimal labels (abbreviated, icon-only where possible) | `.status-chip` with `.label` (uppercase, 10px) + `.value` | ✅ SATISFIED | `items: 6`, `signals: 12`, `stored: 3`, `last: CardMoved`, `cache: indexeddb` |
| **HEU-038** Progressive disclosure via zoom level | Same as HEU-036 — detail reveals at intimate zoom | ✅ SATISFIED | Checklist orbit nodes only at `intimate` zoom (L1709-1726) |
| **HEU-039** Graph pane auto-seeds on load (no empty state) | `if (graphSeedBtn && seed.causalNodes...) { seedGraphFromCausalData(); fitGraph(); }` | ✅ SATISFIED | L324-327 |
| **HEU-040** Empty states have helpful copy (not blank) | `.empty` class: "No work items yet. Start with New work item.", "No items in this column", "No checklist lines yet..." | ✅ SATISFIED | `renderNav` L955-959, `renderBoard` L1321-1325, `renderEditor` checklist L1219-1223 |

---

## 9. Help Users Recognize, Diagnose, and Recover from Errors
*Error messages in plain language, indicate problem, suggest solution.*

| Assertion | Implementation | Status | Evidence |
|---|---|---|---|
| **HEU-041** Provenance error trails via `BlackboardContext.provenance` | `provenance(source, timestamp, transformations)` attached to blackboard surface | ✅ SATISFIED | `BlackboardSurface.kt` L91-97, `provenance()` in cursor |
| **HEU-042** Cache miss/hit diagnostics surfaced as readable status | `state.reactor.cacheHits`, `cacheMisses`, `cacheStored`, `cacheEvicted` → status chips | ✅ SATISFIED | `renderReactor` L1378-1380: `statusChip('signals', signalFacetCount)`, `statusChip('stored', cacheStoredCount)` |
| **HEU-043** IndexedDB fallback warning shown in status strip | `state.cache.warning` + `persistenceMode: 'LocalStorageOnly'` | ✅ SATISFIED | `status-note` L1406: `· IndexedDB unavailable; falling back to localStorage` |
| **HEU-044** PWA registration failure logged + surfaced | `navigator.serviceWorker.register().catch(...)` → `console.warn` + `recordReactor` | ✅ SATISFIED | L790-792: warns but doesn't block; reactor shows `CacheStored: PWA shell...` |
| **HEU-045** Plain-language error for attachment read failure | `readAttachmentBlob` catches → `resolve(null)`; UI shows no error toast | ⚠️ PARTIAL | Fails silently; user sees no feedback if attachment can't open |

---

## 10. Help and Documentation
*System usable without docs; help embedded where needed.*

| Assertion | Implementation | Status | Evidence |
|---|---|---|---|
| **HEU-046** Use-case descriptions as embedded help | `ForgeAppUseCase.summary` + `pageNotes` rendered in rail + editor | ✅ SATISFIED | L96-125: `summary` + `pageNotes` per use-case |
| **HEU-047** PageNotes as contextual guidance per workspace | `state.pageNotes` editable in editor + spatial caption | ✅ SATISFIED | L1004-1016: `pageNotes` textarea; L1888-1893: `spatialDepthLabel` |
| **HEU-048** Seed checklists as embedded tutorials | `seedChecklist()` (L137-154) provides pre-filled checklist items for known cards | ✅ SATISFIED | "Setup CI pipeline" → ["Pick build commands", "Capture failing output", "Promote green path"] |
| **HEU-049** Graph pane has built-in legend/tooltip for node types | **MISSING** — nodes colored by type but no legend explaining green=source, blue=transform, cyan=decision, red=sink | ❌ VIOLATION | `nodeTypeColor()` (L1943-1949) assigns colors but no UI legend |
| **HEU-050** Keyboard shortcut help / cheat sheet | **MISSING** — no help overlay, no `?` key, no shortcut hints in tooltips | ❌ VIOLATION | N/A |

---

## Gap Summary

| ID | Heuristic | Severity | Gap Description |
|---|---|---|---|
| **GAP-001** | #3 User Control | HIGH | No keyboard shortcuts for zoom ( +/- ), focus modes ( b / s ), reset ( r ), add item ( n ), delete ( Backspace ) |
| **GAP-002** | #3 User Control | HIGH | No "Undo" affordance after destructive actions (delete card, delete checklist, reset workspace) |
| **GAP-003** | #5 Error Prevention | MEDIUM | Delete is immediate with no confirmation or undo toast; WAL exists but no rollback UI |
| **GAP-004** | #7 Flexibility | HIGH | No keyboard accelerators at all — expert users forced to use mouse for every action |
| **GAP-005** | #9 Error Recovery | MEDIUM | Attachment read failures fail silently; no user-facing error toast |
| **GAP-006** | #10 Help | MEDIUM | Graph pane node colors have semantic meaning (source/transform/decision/sink) but no legend |
| **GAP-007** | #10 Help | MEDIUM | No keyboard shortcut help overlay, no `?` key binding, no tooltip hints for accelerators |

---

## Verification Commands

```bash
# Build and serve Forge UI for manual verification
./gradlew jsBrowserProductionWebpack
# Then open docs/index.html in browser

# Run Forge-related tests
./gradlew jvmTest --tests 'borg.trikeshed.forge.ForgeDocTest'
./gradlew jvmTest --tests 'borg.trikeshed.kanban.ForgeBoardFSMTest'
./gradlew jvmTest --tests 'borg.trikeshed.forge.ForgePersistenceDurabilityTest'
```