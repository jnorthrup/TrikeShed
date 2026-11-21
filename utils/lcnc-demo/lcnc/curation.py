"""Curation records mirroring the Kotlin DocumentCurationRecord semantics.

Immutable attempts retained by CAS: source identity, retained NLP, model
proposals (raw kept even when untyped), reasons, per-stage receipts. Quotation
occurrence is separate from semantic admission. Parser-derived causal/
conditional candidates are NEVER auto-admitted; promotion is the explicit
review.admit operation. Retrieval and repetition add no evidence (I06).
"""
from __future__ import annotations

from .cas import Cas, cid_of


def retain_source(cas: Cas, *, original: bytes, text: str, name: str, media_type: str,
                  origin: str, begin: int, end: int, anchors=(), unresolved=()) -> dict:
    """I01/I02: original bytes and extracted text are distinct immutable objects."""
    original_cid = cas.put(original)
    text_cid = cas.put(text.encode("utf-8"))
    return {"originalCid": original_cid, "extractedTextCid": text_cid,
            "name": name, "mediaType": media_type, "origin": origin,
            "category": "text" if original_cid == text_cid else "document",
            "coverage": {"begin": begin, "end": end, "selectedChars": end - begin,
                         "totalChars": len(text)},
            "anchors": list(anchors), "unresolved": list(unresolved),
            "provenance": {"origin": origin}, "authentication": "unverified"}


def quotation(source: dict, text: str, begin: int, end: int) -> dict:
    if not (0 <= begin < end <= len(text)):
        raise ValueError("quotation outside extracted text")
    # Python str is code points; UTF-16 offsets are computed for browser anchors.
    u16_begin = len(text[:begin].encode("utf-16-le", "surrogatepass")) // 2
    u16_end = len(text[:end].encode("utf-16-le", "surrogatepass")) // 2
    return {"originalCid": source["originalCid"], "extractedTextCid": source["extractedTextCid"],
            "begin": begin, "end": end, "u16Begin": u16_begin, "u16End": u16_end,
            "quote": text[begin:end], "authentication": "unverified"}


def causal_candidates(frames: list, source_cid: str) -> list:
    """Parser-grounded candidates from conditional/causal frames — candidates only.

    Mirrors NlpcoreAxiomatics discipline: fixed structural weight by pattern,
    source-attributed, fail-closed, never admitted here.
    """
    out = []
    for f in frames:
        fr = f.get("frame", {})
        rule = fr.get("rule")
        if rule not in ("modal-action", "assent", "norm-predicate", "grant", "copular"):
            continue
        readings = fr.get("readings") or []
        inf = f.get("inference") or {}
        weight = {"modal-action": 0.6, "grant": 0.6, "assent": 0.5,
                  "norm-predicate": 0.5, "copular": 0.4}[rule]
        out.append({"sentenceQuote": fr.get("quote"), "begin": fr.get("begin"), "end": fr.get("end"),
                    "rule": rule, "readings": readings,
                    "selectedReading": inf.get("reading"), "readingRule": inf.get("readingRule"),
                    "structuralWeight": weight, "sourceCid": source_cid,
                    "admitted": False, "kind": "candidate"})
    return out


def build_record(cas: Cas, *, source: dict, nlp: dict | None, model: dict | None,
                 model_id: str, proposals: list, reasons: list,
                 tool_receipts: list, quotation_receipts: list,
                 candidates: list) -> dict:
    record = {"source": source, "nlp": nlp, "model": model, "modelId": model_id,
              "proposals": proposals, "reasons": reasons,
              "reservedReceiptCids": [r["receiptCid"] for r in tool_receipts],
              "submittedReceiptCids": [], "duplicateReceiptCids": [],
              "quotationReservedReceiptCids": [r["receiptCid"] for r in quotation_receipts],
              "quotationSubmittedReceiptCids": [],
              "toolReceipts": tool_receipts, "candidates": candidates,
              "instructions": "proposals are not admissions; quotation is not assertion"}
    record_cid = cas.put_json(record)
    return {"recordCid": record_cid, "record": record}
