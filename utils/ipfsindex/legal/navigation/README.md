# navigation

Schema reference: the upstream navigation bundle writes four files: `primary-records.jsonl`,
`reconstruction.json`, `navigation.parquet` (12 columns, in `fields.tsv`) and `manifest.json` (sha256 of
the others, row counts, size limits, source lineage, authority flags). Upstream hard-codes every
authority flag false (admitted, formalized, proof_authority, published, ...).

For our statute parquets the mapping is not decided. Open questions:
- which bucket columns feed `legal_id`, `label`, `src`/`dst` (cross-references into US Code / CFR)
- whether `status` (in_force, repealed, ...) becomes a column or stays a filter
Schema columns above are upstream's; ours may differ once the parquet schema is read.
