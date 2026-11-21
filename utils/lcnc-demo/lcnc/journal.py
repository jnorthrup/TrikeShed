"""Durable semantic event history (invariants I08, I10).

One append-only journal per run. Every admission, transfer, receipt, refusal,
pause and checkpoint is an event with a sequence number and CAS refs. Events
are fsynced before the runner reports them; a green pulse cannot precede the
completion it claims. Replay reads the journal and runs NO engines or effects.
"""
from __future__ import annotations

import json
import os
import time
from pathlib import Path

from .cas import canonical_json


class Journal:
    def __init__(self, path: Path, epoch: str, definition_cid: str):
        self.path = Path(path)
        self.epoch = epoch
        self.definition_cid = definition_cid
        self.seq = 0
        self.path.parent.mkdir(parents=True, exist_ok=True)
        if self.path.exists():
            with open(self.path, encoding="utf-8") as f:
                for line in f:
                    if line.strip():
                        self.seq = max(self.seq, json.loads(line)["seq"])
        self._fh = open(self.path, "a", encoding="utf-8")

    def emit(self, kind: str, refs=(), **payload) -> dict:
        self.seq += 1
        event = {"seq": self.seq, "ts": time.time(), "epoch": self.epoch,
                 "definition": self.definition_cid, "kind": kind,
                 "refs": list(refs), "payload": payload}
        self._fh.write(canonical_json(event) + "\n")
        self._fh.flush()
        os.fsync(self._fh.fileno())
        return event

    def close(self):
        self._fh.close()

    @staticmethod
    def read(path: Path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    yield json.loads(line)
