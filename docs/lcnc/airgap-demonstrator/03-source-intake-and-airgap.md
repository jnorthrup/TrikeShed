# Source intake denaturing and airgap deployment

## Source lanes

| Source | Retained unit | Required anchors | Initial operation |
|---|---|---|---|
| PDF | Exact bytes, extraction and optional page images | Page, text range, layout region when available | Inventory and explicit bounded extraction |
| HTML agreement | Capture bytes, response metadata, selected resources, safe view | Capture identity, DOM-derived block ID, text range | Denature and retain structural outline |
| Questions or flowchart | Versioned question graph | Question IDs, authored assumptions, branch labels | Validate graph and unresolved references |
| Evidence dossier | Individual exhibits plus collection manifest | Exhibit identity, event time, source span | Index without asserting truth |
| Transcript or video memory | Media reference, transcript and timing map | Segment/time range, speaker assertion, transcript version | Search transcript with media provenance |
| User or agent history | Episode records and source permissions | Actor, turn/episode, time, source reference | Scoped recall with attribution |
| IPFS index | Root CIDs, CAR/block closure and manifest | CID, codec, path and verified blocks | Local lookup with missing-block report |
| Fiction or news collection | Source editions and scenario/cutoff | Edition, chapter/paragraph or publication/event times | Build domain-specific contexts |

An import creates source records and availability facts. It does not start all downstream curation automatically. Existing upload routes may already trigger local parsing; the implementation must inventory and expose those behaviours so the wizard's cost preview is accurate.

## Meaning of denaturing

Preserve the original acquisition unchanged. Produce a separate inert view suitable for human reading and source-span selection. For HTML, remove executable scripts, event handlers, forms, embedded frames, plugin objects, automatic navigation, external stylesheet/resource loads and active URL schemes from the display representation. Escape source text before inserting it into the application DOM. Preserve headings, paragraphs, lists, tables, footnotes and links as inert references.

Denaturing is not anonymization and not a claim that a file is harmless. It is a transformation with a policy version, explicit removals and a fidelity report. Keep omitted or transformed material countable. A clause inside a removed interactive accordion must still be captured as document content if available; otherwise coverage is partial.

PDF display and extraction also require separate treatment. A retained PDF may contain attachments, actions, annotations and malformed structures. Run extraction in a constrained worker. An image-based page view is another derivative with its own digest; it does not prove OCR correctness. Tika explicitly distinguishes extraction from sanitized output, which supports maintaining a separate render boundary. [Apache Tika security model](https://tika.apache.org/security-model.html)

## Archive format decision

Use a small native capture manifest for the first demonstrator, retaining original response bytes and explicitly selected resources. Support importing WARC/WACZ through an adapter rather than designing another archival ecosystem. WARC stores resource records and capture metadata; WACZ packages WARC data, indexes and collection context. Neither format by itself gives the application's safe-view or authority guarantees. [WARC 1.1](https://iipc.github.io/warc-specifications/specifications/warc-format/warc-1.1/), [WACZ 1.1.1](https://specs.webrecorder.net/wacz/1.1.1/)

The capture manifest includes requested URL, final URL, response status, acquisition time, displayed effective date if present, content type, encoding, original digest, resources captured, resources omitted and acquisition tool version. Record the agreement's named jurisdiction and product. Do not substitute capture time for agreement effective time.

The official [Coinbase United States agreement](https://www.coinbase.com/legal/user_agreement/united_states) is a concrete candidate for the leftmost pane. The implementation must capture and pin the actual version used. This planning task inspected the source page but did not create an evidentiary capture bundle or decide which agreement governed any real user's situation.

## Anchor transformations

Maintain a chain from original bytes to decoded text, structural blocks, normalized parser input and displayed selections. Every transformation declares its coordinate system. Browser/Kotlin UTF-16 ranges and UTF-8 byte offsets are not interchangeable. Store half-open ranges and reject ranges splitting surrogate pairs or invalid Unicode sequences.

For HTML, anchor a quote using safe-view block identity plus exact text and its source-text range. DOM selectors alone are fragile across sanitization and recapture. For PDF, preserve original and extracted-text identities, page and available bounding boxes. A quote may be exact against extracted text while layout or OCR remains uncertain; the UI must say which level is verified.

Normalization for search can collapse whitespace, fold case or expand variants, but returns a mapping to exact source text. Never write normalized text back as the original quotation. If a mapping cannot be made exact, mark the candidate unanchored and keep it out of source-backed publication.

## Airgap profiles

| Profile | Allowed connectivity | Intended use | Evidence required |
|---|---|---|---|
| Connected acquisition | Explicit destinations through existing policy | Capture and dependency preparation | Acquisition manifest and transfer inventory |
| Application-local | Model providers disabled; host may still have network | Development and local algorithm comparison | Clear UI label; no physical-airgap claim |
| Egress-blocked runtime | Host/workers denied outbound network, loopback explicitly bounded | Demonstrator acceptance | TCP/UDP/DNS probes, process inventory, policy snapshot |
| Physically disconnected deployment | No external network path; controlled transfer medium | Operational offline use | Installation and run record on actual deployment hardware |

The existing Airgap control concerns model egress. The deployment profile supplies broader isolation. Include the browser in the boundary: remote fonts, scripts, analytics, service workers and resource URLs can violate an otherwise offline application design. All required UI assets must be local.

## Bundle mechanics

A sealed bundle lists binaries, parser models, ontologies, tokenizer files, optional model weights, browser assets, fixtures, schemas and licenses. Each entry has length, digest, role and required/optional status. Record target OS/architecture and runtime versions. Build/provision the bundle in the connected environment, verify it before transfer, then perform a no-network installation test in a clean environment with caches absent.

An omitted optional model results in an unavailable lane with an explanation. An omitted mandatory schema or source block fails bundle readiness. Dependencies must not be repaired by runtime download. Nix source pins and package closure retention are separate: a pin does not mean the needed executable closure is already installed offline.

For imported archives, bound compressed and expanded bytes, entry count, nesting depth, filenames and duplicate paths. Reject traversal outside the import root, unsupported links and malformed manifests. Verify a CAR's referenced blocks before claiming complete offline availability. A valid root CID does not establish that every requested child block was transferred.

## Worker isolation and resource ownership

Run potentially expensive or untrusted extraction and native reasoning behind a host-owned process boundary with memory, CPU, wall-time, file and output limits. Use a read-only source mount and a narrow writable output directory. Network denial and filesystem restrictions apply to descendants. A language runtime sandbox does not replace OS resource limits.

A process deadline produces an execution failure receipt. If output is partial, retain it only as incomplete diagnostic material. Do not parse truncated solver JSON as a successful answer. Closing the browser request does not prove the worker stopped; the host's owning Job and process reaping receipt establish completion.

## Import transaction

1. Admit a bounded import request and retain its manifest.
2. Write original artifacts to staging, verify hashes and close file ownership.
3. Commit a source receipt referencing durable bytes.
4. Extract or denature explicitly, producing derivative receipts.
5. Validate anchors and publish index availability for that exact revision.
6. Make the source selectable in LCNC; leave semantic review unapproved.

Failure after step 3 leaves a retained original with extraction unavailable. Failure before step 3 leaves no selectable source. Recovery cleans abandoned staging according to an explicit retention policy; it never reports an incomplete import as ready.

## Acceptance evidence

The demonstrator must open its agreement and PDF, search, inspect, execute the deterministic fixture and replay results with outbound network denied. A deliberately missing model, missing archive block, malformed HTML resource and stale extraction each produce an honest refusal or partial-coverage state. Source selection after recapture must not reuse a prior agreement's anchors. A content-free egress log and bundle inventory accompany the demo recording.
