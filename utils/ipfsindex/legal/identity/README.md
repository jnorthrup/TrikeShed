# identity

Profile (upstream): CIDv1, lowercase base32, sha2-256, codec raw or dag-json; reject double-hashed
multihashes; verify by independent round trip. Planned first use: map each pinned parquet's sha256 to a
raw CIDv1 so bucket files can be addressed by CID. Not implemented.
