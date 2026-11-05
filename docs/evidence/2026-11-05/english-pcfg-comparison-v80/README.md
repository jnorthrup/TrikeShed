# Two external PCFG comparisons

Two supplied synthetic sentences were parsed with Stanford's existing English PCFG grammar on 2026-11-05. The raw records support a negative result: **this comparison does not demonstrate a correction or an accuracy gain.**

| Case | Recorded differences from the retained dependency baseline | Unresolved result |
|---|---|---|
| G07 | Two POS differences: Tenant (1) and Owner (6), `NN` → `NNP` | Trustee (9) stays `NN`; consents (10) stays `NNS`. The tree puts their noun phrase under a coordinated VP. The ambiguity remains. |
| G13 | Three POS differences: Tenant (1), opening quote (4), Owner (6). The names change `NN` → `NNP`; the opener changes from two backticks to two apostrophes. | The closing quote (8) retains two backticks and sits inside the PP containing “without acting on it”. Neither quote pairing nor guard scope is established. |

The archive retains exact source bytes, original canonical inspection CBOR and JSON, token/node identities and UTF-16 spans, raw Penn trees, every POS and constituent, argv, stdout/stderr, before/after network records, child exits, the visible-process closure record, exact executed Java/Python sources, original preparation protocol, and the independent static review. It also preserves the initial missing-`AIRGAP=1` refusal: no compiler or JVM started in that preflight. Later command-scoped `TRIKESHED_AIRGAP=1` launches completed compilation and two parser processes; there was no global environment change.

The original inspection producer was source `0219223a3846887fe283b93598942de332214cd2`, main SHA-256 `d0a8133b10e4b18e1bd4076c1440688ad94a2eeb2bbcdefe743f4759ace75636`. Those baseline identities remain unchanged. Application source `c9298dc6846281de16475878c92ff8ca3b56b5c8` and its 503-test snapshot main `e2352d79aa5b8a6180ed7152897a80bd98d86a48cd3282e4656268ec1ee7bf0d` are **contextual pins only**. The actual comparison classpath was the observer's compiled directory plus 28 SDK JARs; it did not include application main.

This was an external SDK experiment, outside `DocumentEnglishWire` and Graal containment. It produced no new application inspection receipt, dependency tree, CAS/source mutation, NAL admission, query result, provider call or model upgrade. PCFG is generative in the formal statistical grammar sense; this observer parsed supplied text and used no freeform LLM or document generation. These two examples are not legal gold, a benchmark denominator, a throughput comparison, or an accuracy rate. The original baseline has no copied nodes, so copy behavior remains unexercised.

Compiler and parser child/wrapper exits are recorded as zero. Each stage recorded loopback-only user/network namespace state, equal before and after. The closure scan found no visible experiment processes but could not inspect 508 `/proc` entries. It supports recorded child exits and visible-process closure, **not whole-host zero processes or a full OS seal**. Namespace inode numbers can be reused by sequential launches; their equality does not identify simultaneous retained namespaces.

## Replay the records

From this directory, using only Python's standard library:

```sh
python3 replay-recorded.py --extract /tmp/english-pcfg-recorded-v80
```

Choose a nonexistent destination. The script checks `SHA256.json`, validates the entire archive file set and headers before extraction, rejects links/devices/directories/traversal/duplicate paths, extracts ordinary files, and independently checks source/CBOR hashes, all token UTF-16 spans, tree leaves/POS/constituent spans, exact POS differences, classpath metadata, recorded network state and process/log hashes. It writes `recorded-replay-result.json` into the extraction directory. It never imports or executes the archived observer, launcher, compiler, JVM or parser.

Default replay is `recorded-metadata-only`: the SDK, grammar resource, contextual 120 JARs and executable pins are historical metadata. Their binaries are intentionally absent, and private absolute paths are not required or opened. In the original workspace only, optional `--verify-external` additionally rehashes original dependencies/inputs and reads the pinned grammar ZIP resource; it still executes none of them. The original independent review rehashed those external bytes and is retained as a historical review record, distinct from portable replay.

`records-manifest.json` lists every archive member. `observer/final-record-manifest.json` is the original producer's final manifest, preserved byte-for-byte. Its single compiled `.class` entry is deliberately omitted from this package; its recorded hash and compiler source/argv remain. No JARs, whole models, Java executables, private documents or contextual main are bundled. Original `PROTOCOL.txt` and frozen manifests retain their preparation-time HOLD wording; the later raw observations, report and closure show what subsequently ran.
