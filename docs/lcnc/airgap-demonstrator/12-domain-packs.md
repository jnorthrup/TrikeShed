# Fiction news history and IPFS domain packs

## Shared machinery and separate worlds

Each domain pack declares source forms, context rules, question schemas, allowed engines, review criteria, output templates and production effects. It uses the same artifact, event, control and visualization contracts. It does not share a default world graph with another pack.

The legal pack asks what follows under a selected agreement and evidence frame. A fiction pack asks what is consistent with a selected edition and branch. A news pack asks what is reported, supported, disputed and temporally current. A memory pack asks what a particular actor or episode recorded. Those are different truth conditions even when all use RDF quads.

## Fiction and a Dune-oriented panel

Use a user-supplied or appropriately available corpus, its edition, a proposed scene/chapter and transcript annotations from a panel. The plan does not require reproducing a commercial novel in the repository. The public demo can use an original small fictional world with analogous continuity demands; the same interfaces accept the user's Dune materials when supplied.

The fiction frame records canon selection, chronology, character identities, knowledge available to each character, unresolved interpretation and alternate-universe assumptions. A proposed chapter is a candidate branch. Its invented events do not enter the canon graph unless the user adopts them into a separate authored continuity.

Panel roles include continuity, chronology, character knowledge, thematic/editorial review and source verification. “Dune expert” is a role label; a simulated model seat must be identified as simulated, while an actual person's transcript retains that person's attribution. Agreement among simulated experts is not independent evidence of canon.

Stateful constraints can include location/time consistency, possession, identity, alive/dead status within a branch, and whether a character could know an event. Use interval and relation constraints before model prose critique. A contradiction may be intentional; the editor can mark it as a deliberate branch assumption with a retained decision.

## Fiction walkthrough

1. Select an edition/canon manifest and a proposed original scene.
2. Extract candidate entities and events with exact paragraph anchors.
3. Retrieve relevant canon passages and panel transcript segments.
4. Run chronology and character-knowledge constraints.
5. Ask optional model seats for critique grounded in those candidates.
6. Join findings into supported conflict, plausible variation, unresolved interpretation and editorial preference.
7. Revise the scene in a new branch; compare changed constraints and comments.
8. Publish a local chapter packet with provenance and intentionally accepted deviations.

The reusable production might route every new scene mentioning a location/time pair to the relevant chronology check. It should not fossilize an aesthetic preference into a universal rule.

## Transcript and video memories

Retain media identity, transcript version, segment start/end, speaker attribution and confidence in alignment. A transcript sentence can be exact relative to the transcript while inaccurately representing the audio. Preserve this distinction. Imported captions, automatic transcription and human correction are separate derivative layers.

Breadcrumbs point to segments and the questions they informed. Clicking a memory opens the local transcript and, when available, the corresponding media time. Missing media remains missing rather than causing an online fetch. Summaries carry source segment IDs and disclose omitted context.

If two experts repeat a claim from the same video, source dependence is shared. If a speaker corrects an earlier statement, current recall includes the correction relationship while historical replay retains the original statement.

## News analysis

Use a collection of retained articles, statements, transcripts and primary documents with publication time, event time, correction time and acquisition time. The analysis cutoff is explicit. Syndicated copies and repeated quotations form source families so their volume does not become artificial corroboration.

Separate reported claim, independently verified observation, editorial inference and forecast. A news panel can contain claim extraction, source-family deduplication, chronology, counterevidence search, causal hypothesis review and drafting. A causal narrative is a hypothesis unless a declared method supports stronger inference.

The output is a claim/evidence map plus a draft analysis. Each factual sentence has source links and time scope. Unresolved disagreement survives into the draft packet. A late correction creates a new analysis version and invalidates affected summaries and productions.

For the first fixture, use an original synthetic set of six reports about a service outage: two copied reports, one primary status update, one witness account, one correction and one speculative commentary. The expected result recognizes four source families at most, distinguishes event from publication time, and does not count the two copies as independent corroboration.

## Personal agent and borrowed history

History sources declare owner, speaker/actor, episode, acquisition, allowed audience and permitted use. User history can supply preferences and prior decisions. Agent history can supply attempted methods and outcomes. Borrowed history can supply analogies or leads. None should silently override the current user's request or become an authenticated fact about the user's present situation.

A recall result includes why it was selected, exact episode links, source type and currentness. Long-term summaries are versioned. An obsolete decision remains historical and can be marked superseded. A remembered failure can shape a routing policy only after its context and outcome are correctly represented.

The first history fixture includes a repeated failed parser approach, a later correction and a successful bounded method. The demonstrator should recall the correction, avoid re-counting the repeated summary as evidence and show the causal path from prior failure to current method selection.

## IPFS collection

Treat IPFS as content addressing and transport, not an inference engine or authority source. Import a bounded CAR or verified local block collection and index its selected content. A root CID, path and codec identify the requested material. The internal artifact store can retain a verified copy with a mapping to the IPFS identity. [IPLD CAR specification](https://ipld.io/specs/transport/car/carv1/)

The offline resolver returns available, missing block, unsupported codec or invalid digest. It never falls back to a public gateway in the airgap profile. Verify closure for the declared traversal, because a CAR may contain arbitrary blocks rather than every reachable object. Mutable names require a separately captured resolution record and time; a historic resolution is not a current network lookup.

Indexing an IPFS document does not make it trustworthy or grant rights to publish it. Source authority, permissions and review are ordinary evidence fields. The first fixture deliberately omits one child block so the UI can show incomplete availability while still exposing available siblings.

## Domain portability acceptance

Run legal, fiction and news fixtures through the same graph controls and event renderer. Change only domain pack data and procedures. Verify that legal hypotheses do not appear as fiction canon, that fictional events do not support news claims, and that borrowed histories remain attributed. The reusable technical achievement is the shared mechanics with preserved semantic boundaries.
