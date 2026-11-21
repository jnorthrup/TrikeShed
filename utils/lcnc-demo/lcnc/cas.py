"""Content-addressed retention and canonical identities (invariant I01).

Original bytes are immutable: retention stores them under their SHA-256 and
never rewrites. Extraction and derived artifacts are NEW objects with their
own identities. A matching hash proves retained bytes, not authenticity (I02).
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path


def canonical_json(value) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def cid_of_bytes(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def cid_of(value) -> str:
    return cid_of_bytes(canonical_json(value).encode("utf-8"))


class Cas:
    """File CAS: objects/<hex>.blobs plus a manifest of named refs per run."""

    def __init__(self, root: Path):
        self.root = Path(root)
        (self.root / "objects").mkdir(parents=True, exist_ok=True)

    def _path(self, cid: str) -> Path:
        if not cid.startswith("sha256:") or len(cid) != 71:
            raise ValueError(f"invalid cid: {cid!r}")
        return self.root / "objects" / (cid[7:] + ".blob")

    def put(self, data: bytes) -> str:
        cid = cid_of_bytes(data)
        path = self._path(cid)
        if not path.exists():
            tmp = path.with_suffix(".tmp")
            with open(tmp, "wb") as f:
                f.write(data)
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp, path)  # immutable once present; identical bytes are a no-op
        return cid

    def put_json(self, value) -> str:
        return self.put(canonical_json(value).encode("utf-8"))

    def get(self, cid: str) -> bytes:
        path = self._path(cid)
        if not path.exists():
            raise FileNotFoundError(f"missing CAS object: {cid}")
        data = path.read_bytes()
        if cid_of_bytes(data) != cid:
            raise ValueError(f"CAS object failed verification: {cid}")
        return data

    def get_json(self, cid: str):
        return json.loads(self.get(cid).decode("utf-8"))

    def has(self, cid: str) -> bool:
        return self._path(cid).exists()
