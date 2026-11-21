"""Stage receipts and the quotation/admission boundary (I04, I07, I12, I15).

Every engine call mints ONE receipt: engine identity, declared input fragment,
input/output CAS refs, measured cost, status. A refusal is a receipt too — a
timeout or over-cap input is not an unknown logical answer and never becomes
evidence. Quotation occurrence (the model read these bytes) is recorded
separately from semantic admission (the host accepts the interpretation).
"""
from __future__ import annotations

from .cas import Cas, cid_of


def mint_receipt(cas: Cas, *, stage: str, engine: str, engine_identity: str,
                 fragment: dict, inputs: list, outputs: list, cost: dict,
                 status: str = "ok", reason: str | None = None) -> dict:
    body = {"stage": stage, "engine": engine, "engineIdentity": engine_identity,
            "fragment": fragment, "inputs": list(inputs), "outputs": list(outputs),
            "cost": cost, "status": status, "reason": reason}
    receipt_cid = cas.put_json(body)
    return {"receiptCid": receipt_cid, **body}


def quotation_receipt(cas: Cas, *, source_cid: str, text_cid: str, begin: int,
                      end: int, quote: str, u16_begin: int, u16_end: int,
                      reader_receipt_cid: str) -> dict:
    """Exact quotation occurrence — evidence of reading, NOT an assertion."""
    body = {"kind": "quotation", "originalCid": source_cid, "extractedTextCid": text_cid,
            "begin": begin, "end": end, "u16Begin": u16_begin, "u16End": u16_end,
            "quote": quote, "readerReceiptCid": reader_receipt_cid,
            "authentication": "unverified", "semanticAdmission": False}
    return {"receiptCid": cas.put_json(body), **body}
