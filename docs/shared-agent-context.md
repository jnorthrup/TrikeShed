# Shared agent context

ModelMux agents share evidence, not conversations. A revision of evidence is an immutable, content-addressed set of
documents. Each task asks a question of one pinned revision and receives a small, deterministic projection of it, with
the exact place every line came from. Revisions can travel between machines as IPFS blocks; nothing about that is
required, and nothing leaves a machine unless an operator's policy says so.

This document states representations, algorithms, configuration, limits and what has and has not been verified.
Source: `src/commonMain/kotlin/borg/trikeshed/agent/modelmux/` and `.../cas/`. Everything below is commonMain except the
JVM stdio runner and the Kubo process boundary.

## 1. Identities

| name | definition | used for |
|---|---|---|
| content id | `sha256:<hex>` of a document's exact bytes (`ContentId`) | execution, citation, peer fetch |
| raw CID | CIDv1, codec `0x55` (raw), sha2-256 multihash of the same bytes | the peer exchange (Kubo); `toIpnsCid(0x55)` |
| spine id | `LineCas.spineCid(LineCas.spine(text))`: lines trimmed, blank lines dropped | discovery; detects a peer that normalizes differently |
| manifest id | sha256 of the manifest's single canonical encoding | the revision |
| ordinal | index of a line in the spine; `LineCasSourceRanges` maps it to the raw line number and UTF-16 range | provenance |

The spine drops formatting, so it is never the identity of anything that is executed or cited; the content id is. An
ordinal is not a raw index: wherever one is used as the other, `LineCasSourceRanges` is the translation.

### Manifest, wire form version 1

UTF-8, one `\n`-terminated line per item, in exactly this order:

    trikeshed-context-manifest 1
    scope <scope>
    policy <policyRevision>
    author <author>
    parent <sha256:...>              zero or more, ascending, unique
    doc <content> <spine> <name>     zero or more, ascending by name, unique names

`decode` parses, rebuilds, re-encodes and requires byte equality, so a revision has exactly one encoding and one
identity. Limits: 1,048,576 bytes, 4,096 documents, 64 parents, names up to 1,024 characters (a relative `/`-separated
label: no leading `/`, no `\`, no empty, `.` or `..` segment), scope, policy and author up to 256 characters.

A manifest pins evidence; it holds none. A new revision is a new manifest whose `parent` lines name what it came from.
Nothing is overwritten.

A pinned document must be valid UTF-8 text: decoding and re-encoding returns exactly its bytes. Every spine, ordinal and
excerpt range is computed over the decoded text, so for any other bytes a "line range" would name something the content
id does not. Building a manifest over such a document fails, and acquiring a manifest a peer built by hand that pins one
is refused with `NOT_TEXT` before the document is exposed.

## 2. Operations

### acquire (`SharedContext.acquire(manifest, scope)`)

1. The scope must be served by the reader's policy (`ContextReadPolicy.serves`) before anything is read.
2. The manifest block is read (local store first; a peer only in PEER mode), verified against its id, decoded strictly.
3. The manifest's own scope must equal the requested scope: a manifest cannot widen what the reader serves.
4. Every document block is read the same way, with per-block and total byte budgets and a block-count budget checked
   before the fetch that would exceed them. Identical content is fetched once.
5. Each document's spine id must equal the manifest's. It is recomputed from the verified bytes, or taken from the memo
   of an earlier computation for the same content id; either way the comparison is made per manifest entry.
6. The result is a whole snapshot or a typed refusal. There is no partial snapshot.

Reads never go through the synchronous `CasStore`; the peer exchange is a suspend interface
(`ContextBlockExchange.fetch/publish/pin`), so no ordinary store read can start a network request. Fetched bytes are
verified by the caller against the id that was asked for before they are admitted to the store.

### project (`BreadcrumbProjector.project(snapshot, question, budget)`)

A pure function of (snapshot, question, budget, policy `lexical-1`). No model, no randomness, no network.

- Tokens: lowercased word pieces; identifiers split at `_`, case changes and letter/digit edges; one-letter noise and a
  fixed stopword list dropped.
- Corpus statistics are taken over DISTINCT line contents, so a line copied into ten documents counts once and cannot
  change anyone's popularity. Identical lines are shown once.
- Score of a line = sum over query terms present of `weight(term) * tf*(1.2+1) / (tf + 1.2*(1 - 0.75 + 0.75*len/avgLen))`,
  with `weight(term) = idf(term) * (1 + 0.05 * min(len(term), 12))` and `idf = ln(1 + (n - df + 0.5)/(df + 0.5))`.
  A line that opens with an action verb gets a 10% boost.
- Protected constraints travel with their match: a neighbouring line (within `neighborLines`) that carries a negation, an
  error condition or a numeric limit is selected with it if it fits, and counted in `droppedConstraints` if not. When
  anything was dropped or omitted, `requiresExpansion` is true: the projection is then not a faithful summary.
- Budget `ProjectionBudget(maxChars = 2000, maxLines = 24, neighborLines = 1)`. `maxChars` bounds the RENDERED
  projection exactly, headers included. Lines are whole original lines, never clipped.
- Every excerpt carries its document name, document content id, raw line numbers and UTF-16 range, and
  `text == original.substring(range.start, range.end)`.

This is the "word cloud" of the design discussion made precise: term popularity (BM25/idf), word length, and verbs, with
protected constraints. It is not LDA and is not a calibrated probability.

### facets (`ProjectionFacets`)

Everything the projector derives from a snapshot is a pure function of immutable, content-addressed bytes, so it is
memoized. Losing or evicting any of it costs time and never changes an answer.

| entry | key | holds |
|---|---|---|
| document facet | the document's exact content id | LineCas line structure: spine ordinal to original range, and each line's LineCas content id |
| spine id | content id | the normalized spine id, so a verified document is not normalized and hashed again on every acquire |
| line features | the line's LineCas content id | tokens (terms interned), whether the line opens with an action verb, whether it carries a protected constraint |
| snapshot index | the ordered content ids of the snapshot's documents | the distinct lines, term postings (a posting's size is the term's document frequency), every position of each distinct line, the mean line length |

A question reads only the postings of its own terms, so its work is proportional to the lines that contain them, not to
the corpus. Scores, order, `omittedCandidates` and `droppedConstraints` are bit for bit those of the plain algorithm that
scans every line. That algorithm is kept in the tests (`ReferenceProjector`) as an oracle and compared with the indexed
one on generated corpora, cold, warm and evicting. A key is built by one caller while concurrent callers of the same key
wait for it. An entry heavier than its bound is built for the request and not retained. The spine memo is consulted before
the per-entry comparison with the manifest and is keyed by digest-verified content, so it cannot make a wrong spine pass.
Facets are bound to one policy revision and the projector refuses a mismatch. `SharedContext.facets` is the instance the
element, the stdio tools and the reel use, so every projection over one context shares them.

### oversight (System1)

`BreadcrumbOversight` asks a finite relevance model, the existing 4-bit MLX Jeva, one yes/no question per excerpt and
reads back a probability. Thresholds are configuration. It annotates; it never rewrites evidence.
`UNAVAILABLE` is not approval; a required review that cannot run refuses. Excerpts over the length bound stay unreviewed,
and a bounded number are reviewed (never silently sampled). An excerpt that carries a protected neighbour constraint is
never pruned by a relevance score alone.

### coalescing (`SharedContextElement`)

One owner per root, a kernel with two seats (fetch, work). Identical in-flight fetches share one transfer; failures are
never remembered; an owner's cancellation reaches waiters as `SharedWorkAbandoned`, never as their own cancellation.
Projections are keyed by (manifest, scope, question, budget, policy, review configuration); a different revision is a
different key, so a projection cannot outlive its source. Successes are remembered in a bounded LRU; `UNAVAILABLE`
reviews are not. Once `drain()` begins no request is admitted, and everything admitted settles first.

### publish (`SharedContextPublication`)

The operator's `PublicationPolicy` decides what leaves; content cannot grant itself export, name its scope as approved or
vouch for its ancestors. LOCAL (the default) sends nothing. PRIVATE_PEERS needs the scope approved. PUBLIC additionally
needs `publicApproved`. Every refusal happens before any block is sent. Then: plan the closure from local verified bytes
(budgets enforced), send each distinct block once, pin it, read it back from the node and compare digests, and only then
announce a head. A node that accepted a block but cannot serve it has not made it available, so no head is announced over
it.

### heads (`AuthorHeads`)

One head per author, under that author's signed IPNS name, never merged. A record must verify (signature, expiry),
carry an `/ipfs/` value that is a raw sha2-256 CID, and have a higher sequence than the held head. An older record, or a
different value at the same sequence, is refused and the held head stays. A task pins a manifest and hears of a newer
head only when it asks (`updateFor`); it never follows silently.

### stdio server (`scripts/mcp-stdio`, `McpStdioMain`)

Newline-delimited JSON-RPC on stdin/stdout. Under an MCP client, under tcpd/inetd/socat (the socket is stdin/stdout), or
as a one-shot wrapper. What is registered IS the permission set: with no flags the server lists and validates and nothing
else. Flags: `--scope`, `--store DIR`, `--allow-write`, `--oversight mlx`, `--peer-kubo-repo DIR [--peer-kubo-api ADDR]
[--peer-kubo-bin PATH] [--peer-timeout-s N]`, `--model-lane`, `--journal-dir`, `--allow-exec`, `--max-in-flight`,
`--max-line-bytes`. Credentials stay behind `ModelPort` and KeyMux; the process environment is not inherited by mission
children.

## 3. Configuration and limits

| setting | default | where |
|---|---|---|
| `ContextReadPolicy.mode` | LOCAL | nothing is fetched from anyone |
| `maxBlockBytes` / `maxBlocks` / `maxTotalBytes` | 1,048,576 / 128 / 8,388,608 | per acquisition |
| `allowedScopes` | empty | serves nothing; `prefix/*` grants every scope below the prefix; a bare `*` grants nothing |
| `PublicationPolicy.audience` | LOCAL | nothing leaves |
| `maxBlocks` / `maxBlockBytes` / `maxTotalBytes` / `maxLineageDepth` | 8,192 / 1,048,576 / 67,108,864 / 8 | per publication |
| Kubo block ceiling | 2,097,152 | larger blocks are never read or written |
| Kubo timeout | 30 s | `--timeout` on every command |
| `ProjectionBudget` | 2,000 chars, 24 lines, 1 neighbour | per projection |
| `ProjectionFacets` bounds | 262,144 document lines, 131,072 feature lines, 262,144 indexed lines, 8,192 spines, 262,144 terms | per `SharedContext` |
| element parallelism / capacity / cache | 4 / 16 / 64 | `SharedContextElement.open` |
| stdio `--max-in-flight` / `--max-line-bytes` | 8 / 1,048,576 | extra requests are answered -32001, not queued |

Root settings (`SharedContextRoot.settings`): `OROBOROS_CONTEXT_SCOPES` (comma-separated; default `reel/*`),
`OROBOROS_CONTEXT_KUBO_REPO`, `OROBOROS_CONTEXT_KUBO_API`, `OROBOROS_CONTEXT_KUBO_BIN`, `OROBOROS_CONTEXT_TIMEOUT_S`.
Naming a Kubo repo makes PEER mode; nothing is discovered. The settings can express no publication.

## 4. Receipts and refusals

- `ContextRejection`: `ABSENT`, `OVERSIZE`, `DIGEST_MISMATCH`, `EXCHANGE_FAILED`, `FORBIDDEN_SCOPE`, `SCOPE_MISMATCH`,
  `MANIFEST_INVALID`, `SPINE_MISMATCH`, `NOT_TEXT`, `BUDGET_EXCEEDED`.
- `PublicationRefusal`: `LOCAL_MODE`, `PUBLIC_NOT_APPROVED`, `SCOPE_NOT_APPROVED`, `PRIVATE_LINEAGE`,
  `CLOSURE_INCOMPLETE`, `MANIFEST_INVALID`, `BUDGET_EXCEEDED`.
- Per block, kept separate: `published`, `pinned`, `verified` (served back and digest-checked), `failure`.
- `HeadDecision`: `ACCEPTED_FIRST`, `ACCEPTED_NEWER`, `UNCHANGED`, `REFUSED_OLDER`, `REFUSED_CONFLICT`, `REFUSED_INVALID`.
- Oversight: `NOT_REQUESTED`, `REVIEWED`, `UNAVAILABLE`, with a verdict per excerpt.

## 5. Verification status

Recorded 2026-10-24 on one macOS arm64 machine (12 CPUs). Re-run before relying on a line. Opt-in suites are skipped by
default, and a skip is not a pass: read the per-class `skipped=` count in
`build/test-results/jvmTest/TEST-<class>.xml`.

Verified by execution:

- The suites written or changed for this work (context, projection, oversight, reel, publication, heads, root, stdio,
  Zenith tools, Kubo exchange, merge fixes): 264 passed, 0 failed, 10 skipped (the opt-in ones below) in the default
  environment. The Python launcher tests (`test_mcp_launcher`) pass 4/4.
- Real 4-bit MLX Jeva (`jeva-4b-mlx-q4g32-dist`), run and not skipped, in process and through a real stdio server
  subprocess: an irrelevant excerpt scored 0.154 (NOT_RELEVANT) and a relevant one 0.836 (RELEVANT), status REVIEWED.
- Real Kubo 0.43.1: `KuboRealBinaryTest` 4/4, `KuboContextBlockExchangeTest` 12/12. Raw CIDs agree with an independent
  sha2-256/raw computation on three inputs, including the empty block.
- Two real Kubo nodes on loopback in throwaway repositories (`SharedContextPeerIntegrationTest`, 3/3): an MCP server
  backed by one node projects what the other published; a reader with nothing acquires exactly what the publisher
  approved; no unapproved block reaches either store. The mtime of `~/.ipfs/config` is unchanged.
- Continuity (`SharedContinuityTest`, 2/2): two children share one baseline and adopt a change only when they choose; a
  revision the author never approved for export never reaches the other child.
- The facet layer (`ProjectionFacetsTest` 8/8, `BreadcrumbProjectionEquivalenceTest` 2/2): the indexed projector was
  compared with the plain oracle in 2,190 comparisons over 1,230 generated question/budget combinations (cold, warm and
  evicting facets, with a 5-term pool), on every field including the bits of each score, and at the three measured corpus
  sizes. Reuse is pinned by
  counters: a warm question scores only the lines of its own postings, a revision tokenizes only the lines it added,
  identical lines share one entry, the spine memo cannot make a wrong spine pass, and six concurrent first questions build
  one index. The oracle test was run green against the unchanged projector first. It has not been run against mutants.
- Mutation checks, run by hand-written anchored substitutions with hash-verified restoration: 27 of 27 publication and
  head mutants killed. A gap in the drain tests (admission after drain was not pinned in `acquire` or `project`) was found
  and closed; three mutants are now killed when run alone. One reel mutant, removing the empty-selection guard, survived
  alone; a test (`anEmptySelectionNeverConsultsSystemOneAndIsNotBlamedOnIt`) now pins it and passes on the real code, but
  it has not been run against the mutant.
- The stdio launcher: a real `scripts/mcp-stdio` process answers `initialize` and `tools/list`, refuses an ungranted tool
  as unknown, and keeps stdout to protocol lines. Its stderr is one line per call (tool name and outcome, never
  arguments) plus JVM warnings.
- Targets: commonMain compiles for JVM, JS, wasmJs and Kotlin/Native macosArm64. commonTest compiles for JS apart from
  three errors in `narsese/JevaElementTest.kt`, which this work did not touch and which are unmodified from HEAD.
  `compileCommonMainKotlinMetadata` could not run offline (a cached artifact is missing). `compilePagesKotlinJs`
  (the browser app that contains `MuxPage.kt`) compiles.
- Full `:jvmTest`, default environment, final state: 5,513 tests, 242 failed, 51 skipped. The pristine HEAD checkout with
  the same command: 5,258 tests, 242 failed, 41 skipped. Matched by class and name, 241 of the failures here also fail at
  HEAD (Camel, GraalPy polyglot, Tika, CoreNLP, io_uring, Kanban HTTP and others, none in areas this work touches), one
  HEAD failure passes here, and one test fails here that passed at HEAD: `JvmSocketRingTest.failedSynchronousSubmission
  SettlesAdmittedPollBeforeClosing` (`expected 1 but was -11`, an `EAGAIN`). That test failed in two of three full runs of
  this tree, passed in the third, and passes 7/7 in isolation three times, so it is timing-dependent under load; HEAD was
  sampled once, so this does not show HEAD is immune. The suite is not green.

Measured (in memory, synthetic Zipf-like corpus with 5 planted needles, `TRIKESHED_TEST_MEASURE=1`; 12 CPUs, 3 GiB test
heap, one run, so expect noise of tens of percent). Every needle was found, the rendered projection was 348 characters,
and the indexed answer was asserted identical to the plain algorithm's at every size. Times are milliseconds:

| lines | documents | bytes | acquire (first) | acquire (again) | plain algorithm | indexed, first question | indexed, new needle question | indexed, every line matches | retained MiB |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 10,000 | 50 | 547,213 | 16 | 1 | 37 | 35 | <1 | 5 | 7 |
| 50,000 | 250 | 2,780,910 | 67 | 3 | 164 | 170 | <1 | 20 | 32 |
| 200,000 | 1,000 | 11,255,355 | 220 | 10 | 758 | 752 | <1 | 72 | 133 |

Where the plain algorithm's time went at 200,000 lines: 200 ms to normalize and hash every line for the spine check in
acquire, 165 ms to hash every line again in project, 175 ms to tokenize, and the rest rebuilding document frequencies and
scanning all lines. The first indexed question costs about what the plain algorithm costs, because it does the same work
once; later questions skip it. A run before term interning retained 264 MiB at 200,000 lines, and the every-line question
took 525 ms in that run against 72 ms in this one, so read that column as a range. The synthetic vocabulary has 5,000
terms, which flatters interning; a corpus with a larger vocabulary will retain more per line. A question that matches
every line still sorts all of them and tries each against the budget, so its cost grows with the matches.

This covers acquire plus lexical projection only. It does not cover Jeva oversight latency, 50,000 open breadcrumb
choices, or a real (non-synthetic) corpus.

Not verified, or not done:

- Nothing was published beyond the local machine and the two throwaway loopback repositories. Public publication is off
  and needs its own approval.
- The Oroboros daemon builds its shared context from `SharedContextRoot`; that wiring is covered by compilation and
  `BreadcrumbReelServiceContextTest`. The daemon was not booted.
- The browser apps were not bundled or run. Only the `pages` app, which contains the edited `MuxPage.kt`, was compiled
  (`compilePagesKotlinJs`); the other apps' sources, the webpack bundles and in-browser behaviour were not exercised.
- The UTF-8 exactness tests were written together with the fix and were not observed failing without it.
- Zenith is the mission subset (`zenith_status`, `zenith_plan_validate`, the gated run tools). The upstream coordinator,
  controller and server are not ported.
- No remote model inference was run, and no mission was resumed from saved state.
- Breadcrumb records (`BreadcrumbIndex`) are not wired to shared contexts. Local and peer evidence already take the same
  path (`acquire`, then the projector), but mapping an agent-authored record to a manifest document (granularity, identity,
  what may be exported) is a design decision that has not been made.

Reproduce:

    cd trikeshed
    ./gradlew --offline :jvmTest -PkotlinJsApps= --continue --rerun \
        --tests 'borg.trikeshed.agent.modelmux.*' --tests 'borg.trikeshed.mcp.*' --tests 'borg.trikeshed.mux.*' \
        --tests 'borg.trikeshed.cas.*' --tests 'borg.trikeshed.agent.zenith.*'
    # real MLX Jeva:   source ../config/jeva-mlx.env.sh; TRIKESHED_TEST_JEVA_MLX=1
    # real Kubo:       TRIKESHED_TEST_KUBO_BIN=/opt/homebrew/bin/ipfs
    # two real nodes:  add TRIKESHED_TEST_KUBO_LOOPBACK_PEERS=1
    # measurement:     TRIKESHED_TEST_MEASURE=1 (use -i to see the table)
