# corpora

`open-us-law.pins.tsv` lists the 105 parquet files named in the bucket's `SHA256SUMS.json`
(file, sha256, rows, bytes). The bucket holds 114 files; the other 9 are `.gitattributes`, `README.md`,
`SHA256SUMS.json` itself and 6 receipts under `legal-corpora-reindex/` (see ../receipts/).

Source identity: Vaquill Open US Law v2026.07 (snapshot 2026-07-21). Authority class: secondary mirror,
not an official-source scrape. The justicedao receipt `lcr084_evidence_bundle.json` says so itself
("recovery-only and is not official live scrape evidence"; GA and NC statutes missing, withdrawn 2026-08-12).

Open: sha256 values are copied from the bucket's SHA256SUMS.json. They have not been recomputed against
the bucket bytes here.

`justicedao-american-municipal-law-tn.pins.tsv` and `american-municipal-law.pins`: the Tennessee package of the public
dataset justicedao/american_municipal_law (`municipal_laws_parquet_cid/STATE-TN*`, 9 files, 16,358,951 bytes), pulled
2026-11-22 to `~/.local/share/TrikeShed/legal/justicedao/american_municipal_law/`. Each sha256 was recomputed and equals the
Hub's LFS oid. `STATE-TN_cid_index.parquet` is a table of 822 rows (ipfs_cid, identifier, official_cite, bluebook_citation,
text, jsonld), not byte offsets. The 822 rows are four cities: Red Bank 576, Forest Hills 197, Johnson City 37, Oak Hill 11
(one row has no place). Hohenwald is not in it. It is also absent from the private bucket jim8vsiwest/american-municipal-law
(935 places, 5 in Tennessee: Forest Hills, Germantown, Oak Hill, Red Bank, Johnson City; it holds `american_law/` only, not
`municipal_laws_parquet_cid/`), from TN `graph/nodes` and `bm25/slim-documents` of
justicedao/ipfs_american_municipal_law_normalized_graphrag, and from the 2026-11 local handoffs of
endomorphosis/american_municipal_law (Tennessee: Mason, Sunbright, Walden, Wartrace, Westmoreland).
