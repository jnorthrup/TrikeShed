---
title: "Gap Ledger"
layout: default
permalink: /gaps/
order: 6
audience: "product owner and diligence reviewer"
pack: true
---

# Gap ledger

The funding work should close the gap between an extensible engineering system and a repeatable sales application. The items below are review findings and proposed acceptance work, not estimates of effort.

| Priority | Remaining work | Acceptance evidence |
|---|---|---|
| Product definition | Select one sales segment, sponsor, material set, and recurring question | Written pilot case with expected answers and a named reviewer |
| No-code front door | Connect intake, useful views, and the chosen workflow into one understandable application | A salesperson imports approved material and completes the job without editing code |
| Ingest fidelity | Distinguish text extraction from structured spreadsheet, OCR, email, and connector support | Representative corpus; retained originals; expected values and failure cases checked |
| Managed curation | Establish production registration, installed modules, chosen model, and evidence admission through the real caller | The release runs source → extraction → curation → visible sheets with inspectable support |
| Portable boot | Deliver launcher, Java/runtime needs, manifest, resources, and required guest modules | Clean-machine installation without an engineering checkout or Gradle build |
| Offline use | Define which functions work from retained material and which need a service | The sales application opens and performs agreed local tasks without connectivity |
| Peer environment replication | Join replicated data with coherent program/module/configuration/state release and activation | Independently writable peers exchange a full environment, exercise known conflicts, restart/activate, and recover interrupted updates |
| Durable state | Integrate and deploy recovery of document heads, revisions, deletions, checkpoints, program state, views, and worlds | Stop/restart and restore tests against the actual chosen backing stores |
| Company deployment | Choose access model, laptop data scope, model destinations, and support process | Repeatable install, backup/restore, and authorized peer setup |
| Performance | Measure actual preparation/query time and resource use | Reproducible corpus and hardware report, including cold and warm runs |
| Btrfs format compatibility | Resolve enhanced-format semantics against upstream Btrfs expectations without duplicate indexing | Direct current seed/private-send checks pass upstream tools, or an explicitly separate conversion is documented as conversion |

## Specific boundaries found in code

Build-plane storage and local hydration are implemented, but the manifest’s remote provisioning and a complete independently runnable package are not demonstrated. The inspected daemon’s `CouchStoreFactory.casBacked` uses a memory projection around durable blobs; retained blobs alone do not establish recovery of heads, revisions, deletions, or checkpoints. A separate Stage 1 durable Couch/CAS implementation exists but needs integration into this daemon. The curation adapter needs a supplying host and installed managed libraries; its contract’s presence in the palette is insufficient. [E01]({{ site.baseurl }}/registry/#e01) [E02]({{ site.baseurl }}/registry/#e02) [E04]({{ site.baseurl }}/registry/#e04)
