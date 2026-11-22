# corpora

`open-us-law.pins.tsv` lists the 105 parquet files named in the bucket's `SHA256SUMS.json`
(file, sha256, rows, bytes). The bucket holds 114 files; the other 9 are `.gitattributes`, `README.md`,
`SHA256SUMS.json` itself and 6 receipts under `legal-corpora-reindex/` (see ../receipts/).

Source identity: Vaquill Open US Law v2026.07 (snapshot 2026-07-21). Authority class: secondary mirror,
not an official-source scrape. The justicedao receipt `lcr084_evidence_bundle.json` says so itself
("recovery-only and is not official live scrape evidence"; GA and NC statutes missing, withdrawn 2026-08-12).

Open: sha256 values are copied from the bucket's SHA256SUMS.json. They have not been recomputed against
the bucket bytes here.
