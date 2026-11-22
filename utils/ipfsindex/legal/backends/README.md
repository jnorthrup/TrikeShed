# backends

Interface to mirror (upstream router): add_bytes, cat, pin, unpin, block_put, block_get, add_path, ls.
Default upstream backend is the local Kubo `ipfs` CLI. Not checked: whether Kubo is installed here.
Bucket reads use `hf buckets cp` (see the repo's hf-gated-repo-to-bucket-clone skill for auth notes).
