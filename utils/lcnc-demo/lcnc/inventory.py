"""What a run read: sources, parser models, ontology pins, model bundle, code, runtimes — each with a digest.

This is an inventory of one run on one machine, written so a reader can tell which bytes produced the
report. It is not a sealed bundle (docs/lcnc/airgap-demonstrator WP23): nothing was packaged, transferred
or installed from it, and the interpreters' own packages are named by version, not hashed.
"""
from __future__ import annotations

import hashlib
import platform
from pathlib import Path

from .engines import Q4BERT_DIR, Q4BERT_FILES, REPO, STANZA_DIR

CODE = [("utils/stanza", ["run.py", "operators.py", "legalframes/*.py"]),
        ("utils/q4bert", ["worker.py", "q4bert/*.py"]),
        ("utils/lcnc-demo", ["demo.py", "lcnc/*.py", "airgap/*.py", "fixtures/*.json"])]


def _sha(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def build(state: dict, model: str) -> list:
    rows = []
    if "source" in state:
        rows.append({"role": "source", "name": state["source"]["name"], "bytes": state.get("source_bytes"),
                     "sha256": state["source"]["originalCid"][7:], "required": True})
    summary = state.get("stanza_summary")
    if summary:
        parser = summary["parser"]
        for processor, models in sorted(parser["models"].items()):
            for m in models:
                rows.append({"role": "parser-model", "name": "stanza %s %s" % (processor, m["file"]),
                             "sha256": m["sha256"], "required": True})
        rows.append({"role": "parser-resources", "name": "stanza resources.json", "sha256": parser["resourcesJsonSha256"],
                     "required": True})
        ref = next((p for p in summary["sumo"]["pins"] if "ref =" in p), None)
        rows.append({"role": "ontology", "name": "SUMO selection, %d files, %d terms" % (summary["sumo"]["files"], summary["sumo"]["terms"]),
                     "ref": ref.split("=")[1].strip() if ref else None, "required": True})
        for pos, m in sorted(summary["wordnet"].items()):
            rows.append({"role": "lexicon-mapping", "name": m["file"], "sha256": m["sha256"], "required": True})
        rows.append({"role": "runtime", "name": "parser lane", "versions": {"stanza": parser["version"], "torch": parser["torch"]},
                     "required": True})
    bundle = state.get("model_bundle")
    if bundle:
        for name in Q4BERT_FILES:
            path = Path(bundle) / name
            rows.append({"role": "model-bundle", "name": "q4bert %s" % name, "bytes": path.stat().st_size,
                         "sha256": _sha(path), "required": False})
        pins = state["model_pins"]
        rows.append({"role": "model-provenance", "name": "float32 checkpoint the bundle was quantized from (not read by this run)",
                     "ref": "%s@%s" % (pins["checkpoint"]["name"], pins["checkpoint"]["snapshot"]),
                     "sha256": pins["checkpoint"]["float32Sha256"], "required": False})
        rows.append({"role": "runtime", "name": "encoder lane", "versions": state.get("model_runtime"), "required": False})
    elif model == "jeva" and state.get("model_identity"):
        rows.append({"role": "model-bundle", "name": "jeva bundle (identity as the worker reported it; files not re-hashed here)",
                     "identity": state["model_identity"], "required": False})
    elif model != "none":
        rows.append({"role": "model-bundle", "name": "%s lane" % model, "status": "not loaded in this run", "required": False})
    for base, patterns in CODE:
        for pattern in patterns:
            for path in sorted((REPO / base).glob(pattern)):
                rows.append({"role": "code", "name": str(path.relative_to(REPO)), "bytes": path.stat().st_size,
                             "sha256": _sha(path), "required": True})
    rows.append({"role": "host", "name": platform.platform(), "machine": platform.machine(),
                 "python": platform.python_version(), "required": True})
    return rows


__all__ = ["build", "CODE", "STANZA_DIR", "Q4BERT_DIR"]
