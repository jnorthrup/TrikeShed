# Exact proposition attention — v71

All six public contract tests passed against the frozen v71 JAR. A separate writer and JVM reopener also passed using production `PackCasStore`, `DocumentAppendLog`, the existing belief writer, HTTP handlers, LCNC runners and attention decay.

| Identity | Value |
| --- | --- |
| Frozen source | `4bdcc227781fa68b07c0105655a19f3e0c11959f` |
| Main JAR SHA-256 | `6ae17af797780fa01aac7db107908241d9066a976628f3711f7cddc4e66c1355` |
| Classpath | Frozen main JAR and 119 dependency JARs |
| Separate JVM PIDs | Writer `69494`; reopener `69530` |
| Fresh manual source | 28 exact UTF-8 bytes: `  \r\n(pays Tenant Landlord)\r\n` |
| Source CID | `sha256:11314aef9114346b08f0a79aa90e414eb1e3d282e35a2341a92f54a7cee29e0b` |
| Proposition CID | `sha256:fd450f0fd207a25206a870e5576e87c2e7614f58e6dbcb8e702e9a2512783e55` |

The coordinated parent build passed 389 checks with zero failures, errors or skips. This package independently ran the exact six attention tests, then the separate persistence proof. Neither count is a count of reasoning capabilities.

The test runner checked 26 production CodeSources. Both persistence processes independently checked 18 production CodeSources, the main-JAR hash, all 119 dependency hashes and their exact runtime classpath. Only observer/test sources compiled; no production source overlay, friend access or live build classes were used. The compiled observer inventory contains only `proof.nars.attention` classes.

## Processed attention and persistence

The writer imported the fresh manual source through production `POST /api/beliefs/kg`. Real loopback HTTP exchanges reached production `BeliefWire`; a small observer HTTP transport forwarded the requests. This tests the production handlers through actual socket requests, not the full daemon's transport or bootstrap. LCNC invoked production `BeliefsNodes` directly.

HTTP and LCNC attention returned `processed: true` only after the existing single writer applied the command. Partial budget components retained the current packed values. The same signal bytes, evidence counts, proposition definition and source bytes remained intact. Repeating an unchanged budget reported `changed: false`.

A known resident PID and a verified retained KG source are separate observations. Metadata verifies the exact definition preimage, ordered signal roles and full source hash within 64 definitions and 4MiB of total bounded reads per response. Unavailable or non-KG evidence leaves an explicit status and null source context; it does not turn attention into evidence. The six contract tests also preserve legacy angular-only Attend behavior, including absent-resident no-ops, and verify the processed LCNC response.

| Control | Observed result |
| --- | --- |
| Exact resident PID, HTTP and LCNC | Processed budget update; verified retained source context. |
| Different PID at the resident angular | `409 PROPOSITION_MISMATCH`; original signal preserved. |
| Unknown resident | `409 RESIDENT_REQUIRED`; no belief created. |
| Evicted exact PID | `409 RESIDENT_REQUIRED`; archived signal remained absent from residents. |
| Actual `pays`/`owes` angular collision | Existing import `409 PROPOSITION_MISMATCH` preserved. |
| Resonance `k=0`, negative, huge or fractional | `400`; the next valid request succeeded. |
| Both resonance modes, HTTP and LCNC | Exact PID and verified source-context metadata present. |
| Valid command after refusals | Processed successfully; writer remained active. |

The passing writer made 20 HTTP exchanges: twelve `200`, four `400` and four `409`. `/tick` retains its queued response; the observer awaited the existing FIFO processing fence before checking decay. The expected packed budget changed from `1037628474536597913` to `1010815783981652377`. Evidence stayed packed as `3865470566400`, and the complete signal SHA-256 stayed `55b0518f8237600fa471b06d0754bd31cb63fe8bbad589c66a303adf46e9a442`.

After drain, actual Pack synchronization, WAL flush and close returned successfully. The separate JVM reopened the 1,499-byte Pack and 1,499-byte CRC-framed WAL. It recovered the exact decayed budget and unchanged signal, evidence, PID, source and definition bytes; the archived control remained absent. This establishes the tested clean close/reopen path. It does not establish crash atomicity, power-loss recovery or stronger durability for other storage implementations. The command response itself promises processing, not a requested WAL flush.

## Scope and retained history

These are manually declared, unreviewed KG claims. Source authentication, review authority and world truth remain unestablished. Attention did not add evidence, receipts, proposition bytes or source bytes. No English carrier, NLP reader, model, SUMO classification or induction path was admitted by this proof. The private stores and owners are separate from the daemon and prior v67 retention evidence.

An earlier input was valid commented KIF:

```lisp
; Fresh manual attention durability fixture; unreviewed declared claims.
(pays Tenant Landlord)
```

The production format detector did not select KIF when the first non-whitespace character was `;`. `POST /api/beliefs/kg` safely returned `400 {"error":"invalid_kg_input"}` before admitting facts. The exact CRLF input, base64 encoded, and response remain under `history/`. The passing manual input is also retained as base64 so repository whitespace handling cannot change its bytes. This is a real existing format-detection limitation; the passing within-profile source does not establish general leading-comment compatibility. No frozen production code changed during this proof.

Observer compilation/identity mistakes are retained separately from that production refusal. `preliminary-source-overlay/` retains the earlier source-check history, including the WAL decay regression that led to logging changed retained budgets with existing `A:` records. Those preliminary overlay checks are not substituted for these final-JAR results.

`summary.json`, `contracts/results.json`, both process results, `http-records.json`, identities, harness sources and `frozen-jars.json` provide the detailed record. `sha256.json` covers every file in this evidence directory except itself.
