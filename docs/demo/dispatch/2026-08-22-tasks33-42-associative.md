# Tasks 33–42 — associative engine, grid front page, canvas context (2026-08-22)

## Standing rules (not inferable from the tree)
- Gate: `./gradlew jvmMainClasses --console=plain`. JS: `./gradlew compileKotlinJs --console=plain` must stay green for any commonMain change. Focused tests: `./gradlew jvmTest --tests '*.Name' --console=plain` — never `-PfocusedTransportSlice`.
- commonMain: no `System.currentTimeMillis()` (use seq / `0L` / `expect`), no NIO/Selector/SocketChannel/io_uring imports, overrides don't repeat interface default params.
- `gradle/js-target-debt.excludes` is a ratchet: never add entries.
- No git/gh write commands. Leave changes in the working tree. Don't revert Jim's edits; targeted `Edit` only on files he's touched.
- The oroboros daemon runs off `build/classes` and **dies on every gradle compile**; hermes relaunches it. Expected; don't report as regression; don't start/stop/kill it.
- Style: dense substrate code on `lib` `Series`/`Join`/`Trie`, `LineCas`, `ContentId`; no new frameworks or libraries (no javax.mail, no litegraph, no OpenAPI/JSON-Schema libs). Couch-analogue names.
- Clean-room: no code, JSON formats, identifiers, CSS, or assets from ComfyUI/litegraph, n8n, Notion. Our document is `ForgeDoc`; our wire is a block.
- Report per task: files touched, commands run, pass/fail output verbatim (last ~30 lines), decisions made, anything left undone and why.

## Existing organs these tasks connect (do not rewrite)
- `nlp/lemma/FunnelLemmatizer.kt`, `LemmaDictionary.kt` (commonMain, 9 tests) — context-resolved lemmatizer, frozen suffix rules.
- `cas/LineCas.kt` — content id + bidirectional neighbour stamp + graded match (`LINKED/PARTIAL_*/CONTENT_ONLY`), `spineCid`, `LineCasIndex`.
- `cas/FunnelResidualMerge.kt` — `buildMasterBaseline`, `residualsOf`, `topologyOf`, `gradeClusters` (`INHERITED/NOVEL/INHERITED_CROSS/RELOCATED`), O(|union residual|). Only a test caller today.
- `narsese/` — `TruthCoord` (f,c packed; `expectation()`), `EvidenceCoord` (w+,w− packed), `NalCopula`, `NarseseBag` (`reviseInto`, `recallByExpectation`, `recallNear`), `DerivationReceipt.deduction` (the only rule), `ForgeKgIngest.decomposeKg`.
- `kanban/IngestRoute.kt`, `media/Office.kt`, `JvmTikaIngestAdapter` — ingest to text.
- `lcnc/editor/DatabaseView.kt`, `PropertyEditor.kt`, `LcncJsBridge.kt` — Notion-shaped grid + row/column ops.
- `forge/gallery/ForgeGalleryCatalog.kt` (41 widgets) — becomes the palette for the canvas only.
- `utils/rfxhttp/CouchHttpSurface.kt`, `CouchRequestFactory.kt`, `ViewQuery.kt` — the store surface; `_design/*` views with proof cids.
- `script.js` — `svgEl`, `applyCamera`, `inspectNode`, `buildGraph` (existing SVG).
- Tasks 28–32 (canvas: ports, topo-eval w/ content-hash cache, `_design/lcnc`, SVG cables, inspector) stand unchanged, scoped to the `/graph/{pageId}` route.

---

### 33. MIME in commonMain
`nlp/mail/Eml.kt`: RFC 5322 headers (folding; From/To/Subject/Date/Message-ID), nested multipart boundaries, base64 + quoted-printable, charset→UTF-8 for the common set; yields `Series<Join<Headers, Series<Part>>>`, `Part = (contentType, filename?, bytes)`. Attachments route through `ingestRoute`. mbox = split on `^From ` lines.
Accept: jvmTest with three fixture `.eml` (plain; multipart with docx+pdf; nested multipart/alternative) → correct attachment count/names/bytes; a 100-message mbox splits in <100 ms.

### 34. TokenSpine — the missing link (do first)
`nlp/lemma/TokenSpine.kt`: `fun tokenSpine(text: String, lemmatizer: FunnelLemmatizer): LineSpine` — tokenise, lemmatise (context-resolved), each lemma a `LineNode` with `LineCas` neighbour stamps over the lemma sequence; optional `shingle(k=2)`. `spineCid` = document fingerprint. Wire into ingest so every ingested doc carries `spineCid` and its spine (attachment via task 11 if landed, else a doc field).
Accept: commonTest: "Managed Kubernetes clusters" vs "manages a kubernetes cluster" → `LINKED` on manage/kubernetes/cluster; spineCid stable across whitespace; ingest of a docx yields a spine.

### 35. Residual matcher
`recruit/MatchKernel.kt`: `score(candidate: LineSpine, listing: LineSpine, baseline: MasterBaseline): MatchReceipt`; `baseline = FunnelResidualMerge.buildMasterBaseline(corpus-common spine)` learned from the user's own corpus (boilerplate), not a stopword list. `shared = INHERITED_CROSS`, `gaps = NOVEL in listing`, `extras = NOVEL in candidate`, `coverage = |shared|/(|shared|+|gaps|)`. `MatchReceipt(candidateCid, listingCid, baselineCid, shared, gaps, extras, coverage, cid)`.
Accept: synthetic 20 resumes × 5 listings; planted best match ranks first per listing; `gaps` lists exactly the planted missing terms.

### 36. NAL evidence; feedback is the learner
`recruit/MatchEvidence.kt`: `MatchReceipt → SemanticSignal(candidate→listing, INHERITANCE, EvidenceCoord(w+=|shared|, w−=|gaps|))`; skills as middle terms via `_design/skills` (lemma→skill) so `candidate→skill, skill→listing ⊢ candidate→listing` mints a `DerivationReceipt`; bag keyed `(statement, context)` (context = listing or user); feedback `shortlisted/rejected/hired` revises with fixed evidence weights; rank = `expectation()`.
Accept: commonTest: a rejection lowers rank in that context only; a hire in context A doesn't move context B; every revision has a cid.

### 37. Bias as visible wires
`bias.mustHave(term)`, `bias.location(radius)`, `bias.seniority(range)`, `bias.recency(days)` — each a doc with a cid whose evaluation is an `EvidenceCoord` delta revised into the match; every ranking receipt lists applied bias cids in order. No hidden weights on the path.
Accept: ranking with/without a bias differs and the receipt names it; removing the bias doc restores the prior ranking exactly (replay).

### 38. Sources, engine, sink as catalog widgets (needs 28)
Widgets with `Port`s: `source.mailbox` (drop .eml/mbox/dir → 33), `source.resume` (docx/pdf/image → ingest → 34), `source.joblink` (URL → HTX fetch via egress allowlist → ingestRoute → 34), `match.residual` (35), `score.nal` (36), `bias.*` (37), `sink.shortlist` (one doc per match carrying all receipt cids; renders as a kanban column; `file.append` CSV). Port types: `spine`, `receipt`, `evidence`, `doc`.
Accept: graph mailbox→resume→match→score→shortlist evaluates end-to-end via task 29 on fixtures; the shortlist doc's receipts chain back to the `.eml` cid.

### 39. `_design/recruit` (needs 3 seq log; 9 router if landed, else literal seed)
Views: `candidates`, `listings`, `matches` (key `[listingId, −expectation]`), `evidence` (by match: receipts + bias cids), `gaps` (by listing: most common uncovered terms across candidates). All with proof cids; runs in the browser peer.
Accept: test through `CouchHttpSurface` returns ranked matches and the gaps view for fixture data.

### 40. Phone-class bench (last)
1,000 synthetic resumes × 50 listings: spine build, baseline, match, rank — wall time + peak allocation on JVM and under JS (Node). Targets in the test kdoc (e.g. <2 s JVM, <8 s JS, <256 MB), thresholds generous, numbers printed.
Accept: bench green on both targets; numbers in the report.

### 41. Front-page grid over views
`DatabaseView` bound to a `_design` view name + `ViewQuery`; columns from the view's row shape; `LcncJsBridge` filter/sort → view params (`startkey/endkey/descending/limit`); add-row = envelope put; `_changes` (task 4) refreshes, else refresh after each bridge call. Front page lists available views as tabs (`candidates`, `listings`, `matches`, `receipts`, `arms`, `pointcut sites` as they exist).
Accept: grid shows `matches` ranked; sorting a column changes the view params, not a client-side sort; adding a row round-trips through the store.

### 42. Museum demotion; clean contexts
Remove `{{GALLERY}}` from the sidebar (`src/commonMain/resources/web/index.html:58-60`, `ForgeApp.kt:284 galleryHtml` slot); keep `ForgeGalleryRenderer` as the palette for `/graph/{pageId}` only. Move the existing force/concept SVG explorers to `/explore`. Front door = task 41 grid. Rebake: `./gradlew generateForgePages -PforgePagesStages=jvm,js`.
Accept: `docs/index.html` has no `sidebar-gallery`; `/graph` route renders the palette; `/explore` renders the old graphs; bake exit 0.

---

Order: **34** → (33 ∥ 35) → 36 → 37 → 38 (needs 28) → 39 → 40. **41** and **42** are independent of the engine and can start immediately; 42 after 28–32 exist for the `/graph` route. Disposables: none.
