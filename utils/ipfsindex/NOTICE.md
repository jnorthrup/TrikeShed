# utils/ipfsindex: attribution

No third-party source code is copied here yet. The layout and schema names follow these upstream files,
read on 2026-11-22 (HEAD of default branch at that time, no commit pinned):

- endomorphosis/ipfs_datasets_py `ipfs_datasets_py/logic/autoformal/legal_primary_graph_navigation.py`
  (navigation bundle: manifest + parquet index, twelve fields)
- endomorphosis/ipfs_datasets_py `ipfs_datasets_py/ipfs_backend_router.py` (backend router shape)
- endomorphosis/ipfs_accelerate_py `agent_supervisor/core/multiformats_identity.py` (CIDv1 profile)

Licence of all three: AGPL-3.0 (repo metadata). Before copying any code, pin the commit and add the
file-level notice here.

Corpus data (pinned in legal/corpora/): Vaquill "Open US Law" snapshot v2026.07. Statutory text is public
domain (Georgia v. Public.Resource.Org, 590 U.S. 255 (2020)); Vaquill's compilation is CC BY 4.0, its
scrapers Apache-2.0. Attribute Vaquill (github.com/Vaquill-AI/open-us-law). Mirrored via
justicedao/open-us-law-bucket.
