# utils/ipfsindex/legal

Index layer over the legal corpora in the Hugging Face bucket `jim8vsiwest/open-us-law-bucket`
(private copy of `justicedao/open-us-law-bucket`, 114 files, 1,140,186,125 bytes, copied 2026-11-22).

    corpora/      pins: what bytes we index (file, sha256, rows, bytes), plus the bucket identity
    navigation/   the index schema: twelve fields per record, bundle layout, authority flags
    identity/     content-address profile (CIDv1, base32, sha2-256, raw | dag-json)
    rights/       source-rights evidence (which sources may be used, and for what)
    receipts/     intake of justicedao legal-corpora-reindex receipts (lcr-071, -083, -084)
    backends/     fetch/pin interface, default local Kubo, bucket reader
    tests/        fixtures and checks (none yet)

Status: skeleton plus pins and schema. No indexer code yet. Open questions are listed in each README.
Hard rules, copied from the upstream bundle's design: index rows never assert admission, formalisation or
proof; every row traces to a pinned source object; bounds on bytes and rows are explicit.
