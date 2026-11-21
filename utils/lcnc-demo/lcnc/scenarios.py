"""Baseline curation use cases (D0 fixtures + D1 live vertical slice).

UC1 intake-retention     immutable bytes, distinct extraction identity, coverage, no review by import
UC2 deterministic-frames Stanza lane with preamble aliases; permission readings; quotation receipts
UC3 operator-logic       operators read off parse-tree constructions, a deterministic and an ontological
                         weight each; exact quotes; byte-identical on a second run; loads no model
UC4 masked-cue-evidence  the one model lane: a 4-bit BERT encoder scores each operator's cue at its own
                         masked position. Evidence beside the operator, never inside its weights; contested
                         cues go to review; over-limit input is refused. (--model jeva swaps in the earlier
                         Jeva decision lane; --model none leaves the deterministic record.)
UC5 evidence-states      F05 missing/negative/conflict, context isolation, and the same four states over
                         the conditional UC3 read from the source, hypothetically
UC6 admit-revoke-replay  explicit admission, fenced production, withdrawal, return to analysis

Each use case declares expected outcomes and records observed checks; the runner fails loudly on any
mismatch. Synthetic premises and reference observations are labeled as such in their fixtures.
"""
from __future__ import annotations

import hashlib
import json
import time
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

from . import oplogic
from .cas import Cas
from .curation import build_record, causal_candidates, quotation, retain_source
from .engines import (ConditionalEvaluator, EngineError, JevaWorkerEngine, OperatorEngine, Q4BertEngine,
                      StanzaEngine)
from .journal import Journal
from .receipts import mint_receipt, quotation_receipt
from .review import ReviewLedger

FIXTURES = Path(__file__).resolve().parents[1] / "fixtures"
AMENDMENT_MARKER = "We may amend or modify this Agreement"
REMEDY_MARKER = "sole and exclusive remedy"
FILL_BATCH = 64


@dataclass
class Ctx:
    run_dir: Path
    cas: Cas
    journal: Journal
    ledger: ReviewLedger
    launcher: object = None
    checks: list = field(default_factory=list)
    state: dict = field(default_factory=dict)

    def check(self, uc: str, name: str, ok: bool, observed=None, expected=None):
        entry = {"uc": uc, "name": name, "ok": bool(ok), "observed": observed, "expected": expected}
        self.checks.append(entry)
        self.journal.emit("check", refs=[], **entry)
        return ok


def _load_fixture(name: str):
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


def _clause_record(frames: list, marker: str, rule: str) -> dict:
    for f in frames:
        fr = f.get("frame", {})
        if fr.get("rule") == rule and marker in (fr.get("quote") or ""):
            return f
    raise EngineError("no %s frame quote contains %r" % (rule, marker))


def _requires(ctx: Ctx, uc: str, key: str, earlier: str) -> bool:
    if key in ctx.state:
        return True
    ctx.check(uc, "requires %s" % earlier, False, "missing", "run %s first" % earlier)
    return False


def uc1_intake(ctx: Ctx) -> dict:
    fx = _load_fixture("f01-coinbase-manifest.json")["source"]
    path = Path(fx["path"]).expanduser()
    original = path.read_bytes()
    text = original.decode("utf-8")
    begin = text.find(fx["slice"]["beginMarker"])
    if begin < 0:
        raise EngineError("preamble marker not found in capture")
    limit = min(len(text), begin + fx["slice"]["endOffsetFromBegin"])
    marker = fx["slice"]["endMarker"]
    at = text.rfind(marker, begin, limit)
    if at < 0:
        raise EngineError("end marker not found inside the bounded range")
    end = at + len(marker)
    slice_text = text[begin:end]
    source = retain_source(ctx.cas, original=original, text=slice_text, name=fx["name"],
                           media_type=fx["mediaType"], origin=fx["origin"], begin=begin, end=end,
                           anchors=[{"begin": 0, "end": len(slice_text), "section": "preamble-through-account-terms"}],
                           unresolved=["page", "folio", "table", "diagram", "coordinates"])
    ctx.journal.emit("source-retained", refs=[source["originalCid"], source["extractedTextCid"]],
                     name=fx["name"], coverage=source["coverage"])
    ctx.check("UC1", "original bytes immutable under CID", ctx.cas.get(source["originalCid"]) == original,
              source["originalCid"], "sha256 of capture")
    ctx.check("UC1", "extraction is a distinct identity", source["originalCid"] != source["extractedTextCid"],
              source["extractedTextCid"], "different CID")
    ctx.check("UC1", "authentication stays unverified on import", source["authentication"] == "unverified",
              source["authentication"], "unverified")
    ctx.check("UC1", "coverage badge reflects selected slice",
              source["coverage"]["selectedChars"] == end - begin and source["coverage"]["totalChars"] == len(slice_text),
              source["coverage"], {"selectedChars": end - begin})
    ctx.check("UC1", "preamble included (alias antecedent available)",
              "Coinbase, Inc." in slice_text[:600], True, True)
    ctx.check("UC1", "the range ends on its end marker, inside the character bound",
              slice_text.endswith(marker) and end <= limit, {"end": end, "bound": limit, "unusedChars": limit - end},
              "ends with the marker")
    ctx.state.update(source=source, slice_text=slice_text, slice_begin=begin, source_path=path,
                     source_bytes=len(original))
    return source


def uc2_frames(ctx: Ctx) -> dict:
    if not _requires(ctx, "UC2", "source", "UC1"):
        return {}
    fx = _load_fixture("f01-coinbase-manifest.json")
    source, text = ctx.state["source"], ctx.state["slice_text"]
    manifest = {"sources": [{"id": "coinbase-ua-2026-08-01", "title": source["name"],
                             "text": str(Path(fx["source"]["path"]).expanduser()),
                             "layout": "paragraphs", "begin": ctx.state["slice_begin"],
                             "end": ctx.state["slice_begin"] + len(text), "origin": source["origin"]}]}
    manifest_path = ctx.run_dir / "stanza-manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    engine = StanzaEngine()
    ok, why = engine.available()
    if not ok:
        ctx.journal.emit("engine-unavailable", engine=engine.name, reason=why)
        ctx.check("UC2", "stanza lane available", False, why, "available")
        return {}
    t0 = time.time()
    out = engine.run(manifest_path, ctx.run_dir / "stanza-out", ctx.launcher)
    elapsed = time.time() - t0
    frames, summary = out["frames"], out["summary"]
    frames_cid = ctx.cas.put_json(frames)
    identity = StanzaEngine.identity(summary)
    receipt = mint_receipt(ctx.cas, stage="nlp", engine=engine.name, engine_identity=identity,
                           fragment=engine.fragment, inputs=[source["extractedTextCid"]],
                           outputs=[frames_cid], cost={"seconds": round(elapsed, 3), "cpu": True,
                                                       "sentences": summary["sources"][0]["sentences"],
                                                       "frames": len(frames)})
    ctx.journal.emit("engine-receipt", refs=[receipt["receiptCid"], frames_cid], stage="nlp",
                     frames=len(frames), seconds=round(elapsed, 3))
    aliases = [f["frame"] for f in frames if f["frame"]["rule"] == "alias"]
    alias_text = " ".join(a["quote"] for a in aliases)
    ctx.check("UC2", "party aliases registered (we -> Coinbase)", "Coinbase, Inc." in alias_text and len(aliases) >= 2,
              {"aliasFrames": len(aliases)}, ">=2 alias frames naming Coinbase, Inc.")
    amend_rec = _clause_record(frames, AMENDMENT_MARKER, "modal-action")
    amend, amend_inf = amend_rec["frame"], amend_rec["inference"]
    ctx.check("UC2", "amendment clause reads deontic permission with preamble",
              amend_inf.get("reading") == "permission", amend_inf.get("reading"), "permission")
    remedy = _clause_record(frames, REMEDY_MARKER, "assent")["frame"]
    q_receipts = []
    for fr in (amend, remedy):
        q = quotation(source, text, fr["begin"] - ctx.state["slice_begin"], fr["end"] - ctx.state["slice_begin"])
        qr = quotation_receipt(ctx.cas, source_cid=source["originalCid"], text_cid=source["extractedTextCid"],
                               begin=q["begin"], end=q["end"], quote=q["quote"],
                               u16_begin=q["u16Begin"], u16_end=q["u16End"],
                               reader_receipt_cid=receipt["receiptCid"])
        q_receipts.append(qr)
        ctx.journal.emit("quotation-retained", refs=[qr["receiptCid"]], begin=q["begin"], end=q["end"])
    candidates = causal_candidates(frames, source["originalCid"])
    ctx.check("UC2", "candidates are never auto-admitted", all(not c["admitted"] for c in candidates),
              {"candidates": len(candidates)}, "all admitted=False")
    ctx.state.update(frames=frames, stanza_summary=summary, stanza_receipt=receipt, stanza_identity=identity,
                     amend_frame=amend, remedy_frame=remedy, quotation_receipts=q_receipts,
                     candidates=candidates, frames_cid=frames_cid, manifest_path=manifest_path,
                     stanza_out=ctx.run_dir / "stanza-out")
    return {"frames": len(frames), "framesCid": frames_cid, "identity": identity, "receipt": receipt["receiptCid"]}


def uc3_operators(ctx: Ctx) -> dict:
    if not _requires(ctx, "UC3", "frames", "UC2"):
        return {}
    fx = _load_fixture("f01-operators.json")
    source, text, begin = ctx.state["source"], ctx.state["slice_text"], ctx.state["slice_begin"]
    engine = OperatorEngine()
    ok, why = engine.available()
    if not ok:
        ctx.journal.emit("engine-unavailable", engine=engine.name, reason=why)
        ctx.check("UC3", "operator lane available", False, why, "available")
        return {}
    t0 = time.time()
    out = engine.run(ctx.state["manifest_path"], ctx.state["stanza_out"], ctx.launcher)
    elapsed = time.time() - t0
    ops, summary = out["operators"], out["summary"]
    ops_cid = ctx.cas.put(out["raw"])
    logic_cid, masks_cid = ctx.cas.put_json(out["logic"]), ctx.cas.put_json(out["masks"])
    identity = engine.identity(summary)
    receipt = mint_receipt(ctx.cas, stage="operators", engine=engine.name, engine_identity=identity,
                           fragment=engine.fragment, inputs=[source["extractedTextCid"], ctx.state["frames_cid"]],
                           outputs=[ops_cid, logic_cid, masks_cid],
                           cost={"seconds": round(elapsed, 3), "cpu": True, "operators": len(ops),
                                 "masks": len(out["masks"]), "models": summary["models"]})
    ctx.journal.emit("engine-receipt", refs=[receipt["receiptCid"], ops_cid], stage="operators",
                     operators=len(ops), seconds=round(elapsed, 3))

    digest = hashlib.sha256(out["raw"]).hexdigest()
    ctx.check("UC3", "operator bytes match the lane's own digest", digest == summary["operatorsSha256"],
              digest[:16], summary["operatorsSha256"][:16])
    again = engine.run(ctx.state["manifest_path"], ctx.state["stanza_out"], ctx.launcher)
    ctx.check("UC3", "a second run in a new process gives the same bytes", again["raw"] == out["raw"],
              hashlib.sha256(again["raw"]).hexdigest()[:16], digest[:16])
    ctx.check("UC3", "the operator lane loads no model: it reads the parser's cached tree",
              summary["models"] == 0 and summary["generativeModels"] == 0,
              {"models": summary["models"], "generativeModels": summary["generativeModels"]}, {"models": 0})
    bad_quotes = oplogic.quote_faults(ops, text, begin)
    ctx.check("UC3", "every cue and operand is the retained text at its own offsets", not bad_quotes,
              {"operators": len(ops), "mismatched": bad_quotes[:5]}, "0 mismatched")
    faults = {op["id"]: f for op in ops for f in [oplogic.weight_faults(op)] if f}
    ctx.check("UC3", "both weights of every operator recompute from their recorded parts", not faults,
              {"operators": len(ops), "faults": dict(list(faults.items())[:3])}, "0 faults")
    last = out["logic"][-1]
    ctx.check("UC3", "the range cuts no sentence: the last one ends the slice with its own full stop",
              last["end"] == begin + len(text) and last["quote"].rstrip().endswith((".", "!", "?", ".)", ".”", '."')),
              {"sentences": len(out["logic"]), "lastEnds": last["end"], "sliceEnds": begin + len(text),
               "tail": last["quote"][-40:]}, "last sentence complete")
    ctx.check("UC3", "operators are candidates: none admitted",
              all(op["admitted"] is False and op["kind"] == "candidate" for op in ops) and summary["admitted"] == 0,
              {"admitted": sum(1 for op in ops if op["admitted"])}, {"admitted": 0})
    reference = {}
    for ref in fx["operators"]:
        try:
            op = oplogic.find(ops, text, begin, ref["sentenceMarker"], ref["class"], ref["construction"])
        except LookupError as e:
            ctx.check("UC3", "reference operator %s found by marker" % ref["id"], False, str(e), "one operator")
            continue
        reference[ref["id"]] = op
        wrong = oplogic.mismatches(op, ref["expected"])
        seen = oplogic.observed(op)
        ctx.check("UC3", "%s: %s by %s, weights as referenced" % (ref["id"], ref["expected"]["operator"], ref["construction"]),
                  not wrong, wrong or {"id": op["id"], "deterministic": seen["deterministic"],
                                       "ontological": seen["ontological"], "anySense": seen["anySense"]},
                  {k: ref["expected"][k] for k in ("deterministic", "ontological", "anySense") if k in ref["expected"]})
    by_class = {c: {"count": v["count"], "deterministic": v["deterministicMean"], "ontological": v["ontologicalMean"],
                    "anySense": v["ontologicalAnySenseMean"]} for c, v in summary["byClass"].items()}
    ctx.state.update(operators=ops, operators_raw=out["raw"], logic=out["logic"], masks=out["masks"],
                     cue_classes=summary["cueClasses"], operators_summary=summary, operators_identity=identity,
                     operators_receipt=receipt, operators_cid=ops_cid, logic_cid=logic_cid, masks_cid=masks_cid,
                     reference_ops=reference)
    return {"operators": len(ops), "masks": len(out["masks"]), "sha256": digest[:16], "identity": identity,
            "byClass": by_class}


def _nlp_refs(ctx: Ctx) -> dict:
    refs = {"framesCid": ctx.state["frames_cid"]}
    for key, name in (("operators_cid", "operatorsCid"), ("logic_cid", "logicCid"), ("masks_cid", "masksCid")):
        if key in ctx.state:
            refs[name] = ctx.state[key]
    return refs


def _deterministic_receipts(ctx: Ctx) -> list:
    return [ctx.state[k] for k in ("stanza_receipt", "operators_receipt") if k in ctx.state]


def _model_unavailable(ctx: Ctx, engine_name: str, why: str) -> dict:
    ctx.journal.emit("engine-unavailable", engine=engine_name, reason=why, deterministicRunContinues=True)
    ctx.check("UC4", "an unavailable model lane does not block the deterministic run", True,
              why, "deterministic results retained regardless")
    record = build_record(ctx.cas, source=ctx.state["source"], nlp=_nlp_refs(ctx),
                          model=None, model_id="unavailable", proposals=[],
                          reasons=["model lane unavailable: %s; deterministic record retained" % why],
                          tool_receipts=_deterministic_receipts(ctx),
                          quotation_receipts=ctx.state["quotation_receipts"],
                          candidates=ctx.state["candidates"])
    ctx.journal.emit("curation-record", refs=[record["recordCid"]], proposals=0, modelAvailable=False)
    ctx.state["record"] = record
    return {"skipped": True, "reason": why, "recordCid": record["recordCid"]}


def uc4_evidence(ctx: Ctx, *, model: str = "q4bert", bundle=None) -> dict:
    if not _requires(ctx, "UC4", "frames", "UC2"):
        return {}
    if model == "jeva":
        return _uc4_jeva(ctx, bundle)
    if not _requires(ctx, "UC4", "operators", "UC3"):
        return {}
    engine = Q4BertEngine(bundle=bundle) if bundle else Q4BertEngine()
    if model == "none":
        return _model_unavailable(ctx, engine.name, "disabled by operator (--model none)")
    ok, why = engine.available()
    if not ok:
        return _model_unavailable(ctx, engine.name, why or "unavailable")
    pin = _load_fixture("q4bert-pin.json")
    source = ctx.state["source"]
    ops, masks, classes = ctx.state["operators"], ctx.state["masks"], ctx.state["cue_classes"]
    receipts, evidence, usage = [], {}, Counter()
    try:
        init = engine.start(ctx.launcher)
    except EngineError as e:        # present but not loadable (changed file, other layout): a failure, said out loud
        engine.close()
        ctx.check("UC4", "bundle files verified against the bundle's own digests before loading", False, str(e),
                  {"loaded": True})
        return _model_unavailable(ctx, engine.name, "bundle refused at load: %s" % e)
    try:
        identity = init["identity"]
        pins = engine.pins()
        ctx.journal.emit("engine-ready", refs=[], engine=engine.name, identity=identity, pins=pins,
                         runtime=init.get("runtime"), loadSeconds=init.get("loadSeconds"))
        ctx.check("UC4", "bundle files verified against the bundle's own digests before loading",
                  init["loaded"] is True and init["error"] is None, {"loaded": init["loaded"]}, {"loaded": True})
        observed_pin = {"identity": identity, "weightsSha256": pins["weightsSha256"],
                        "tokenizerSha256": pins["tokenizerSha256"], "checkpoint": pins["checkpoint"],
                        "maxSequenceTokens": init["maxTokens"]}
        expected_pin = {k: pin[k] for k in observed_pin}
        ctx.check("UC4", "adapter is the pinned checkpoint, tokenizer, sequence limit and quantized weights",
                  observed_pin == expected_pin, observed_pin, expected_pin)
        ctx.check("UC4", "the lane is an encoder: not generative, not autoregressive",
                  init["model"]["generative"] is False and engine.fragment["autoregressive"] is False,
                  init["model"], {"generative": False})
        for at in range(0, len(masks), FILL_BATCH):
            batch = masks[at:at + FILL_BATCH]
            items = [{"key": m["operator"], "text": m["text"], "begin": m["begin"], "end": m["end"]} for m in batch]
            t0 = time.time()
            reply = engine.fill(items, classes, request_id="masks-%d" % at)
            elapsed = time.time() - t0
            reply_cid = ctx.cas.put_json(reply)
            receipt = mint_receipt(ctx.cas, stage="model", engine=engine.name, engine_identity=identity,
                                   fragment=engine.fragment, inputs=[source["extractedTextCid"], ctx.state["masks_cid"]],
                                   outputs=[reply_cid],
                                   cost={"seconds": round(elapsed, 3), "sequences": reply["usage"]["sequences"],
                                         "inputTokens": reply["usage"]["inputTokens"],
                                         "outputTokens": reply["usage"]["outputTokens"]})
            receipts.append(receipt)
            ctx.journal.emit("engine-receipt", refs=[receipt["receiptCid"], reply_cid], stage="model",
                             sequences=reply["usage"]["sequences"], seconds=round(elapsed, 3))
            usage.update({k: reply["usage"][k] for k in ("sequences", "inputTokens", "outputTokens")})
            usage["seconds"] += reply["usage"]["seconds"]
            for r in reply["results"]:
                evidence[r["key"]] = {k: v for k, v in r.items() if k != "key"}
                evidence[r["key"]]["receiptCid"] = receipt["receiptCid"]

        answered = [e for e in evidence.values() if e["status"] == "ok"]
        refused = {k: e.get("reason") for k, e in evidence.items() if e["status"] != "ok"}
        ctx.check("UC4", "every masked cue is answered, or refused with its reason",
                  len(evidence) == len(masks) and all(refused.values()) and len(answered) + len(refused) == len(masks),
                  {"masks": len(masks), "answered": len(answered), "refused": refused}, {"masks": len(masks)})
        ctx.check("UC4", "output tokens are zero on every call (the encoder scores; it writes nothing)",
                  usage["outputTokens"] == 0, {"inputTokens": usage["inputTokens"], "outputTokens": usage["outputTokens"]},
                  {"outputTokens": 0})

        tolerance = pin["tolerance"]
        for obs in pin["observations"]:
            op = ctx.state["reference_ops"].get(obs["operator"])
            ev = evidence.get(op["id"]) if op else None
            if ev is None or ev["status"] != "ok":
                ctx.check("UC4", "%s: masked-cue evidence present" % obs["operator"], False, ev, "an answer")
                continue
            seen = {"cue": ev["cue"], "p": ev["p"], "rank": ev["rank"], "preferredClass": ev["preferredClass"],
                    "triage": oplogic.triage(op, ev)}
            same = (seen["cue"] == obs["cue"] and seen["rank"] == obs["rank"]
                    and seen["preferredClass"] == obs["preferredClass"] and seen["triage"] == obs["triage"]
                    and ev["p"] is not None and abs(ev["p"] - obs["p"]) <= tolerance)
            ctx.check("UC4", "%s: cue %r %s as referenced" % (obs["operator"], obs["cue"], obs["triage"]), same,
                      seen, {k: obs[k] for k in ("cue", "p", "rank", "preferredClass", "triage")})

        # Over-limit input: refused with its reason, and the worker goes on serving.
        long_text = (ctx.state["remedy_frame"]["quote"] + " ") * pin["refusal"]["repeatSentence"]
        probe = [{"key": "over-limit", "text": long_text, "begin": 0, "end": 2},
                 {"key": "after-refusal", "text": ctx.state["remedy_frame"]["quote"], "begin": 0, "end": 2}]
        reply = engine.fill(probe, classes, request_id="refusal-probe")
        over, after = reply["results"]
        refusal = mint_receipt(ctx.cas, stage="model", engine=engine.name, engine_identity=identity,
                               fragment=engine.fragment, inputs=[source["extractedTextCid"]], outputs=[],
                               cost={"inputChars": len(long_text), "outputTokens": 0},
                               status="refused" if over["status"] == "refused" else "unexpected",
                               reason=over.get("reason"))
        receipts.append(refusal)
        ctx.journal.emit("engine-refusal", refs=[refusal["receiptCid"]], reason=over.get("reason"))
        ctx.check("UC4", "over-limit input is refused with its reason, never truncated",
                  over["status"] == "refused" and pin["refusal"]["expectReason"] in (over.get("reason") or ""),
                  over.get("reason") or over["status"], pin["refusal"]["expectReason"])
        ctx.check("UC4", "the worker keeps serving after a refusal", after["status"] == "ok",
                  {"status": after["status"], "cue": after.get("cue")}, {"status": "ok"})
        runtime, egress = init.get("runtime"), reply.get("egress")
    finally:
        engine.close()

    marks = {op["id"]: oplogic.triage(op, evidence.get(op["id"])) for op in ops}
    counts = Counter(marks.values())
    queue = [op["id"] for op in ops if marks[op["id"]] == "cue-contested"]
    proposals = [{"operator": op["id"], "kind": "masked-cue-evidence", "triage": marks[op["id"]],
                  "evidence": evidence[op["id"]], "receiptCid": evidence[op["id"]]["receiptCid"], "admitted": False}
                 for op in ops if op["id"] in evidence]
    evidence_cid = ctx.cas.put_json({"identity": identity, "evidence": evidence, "triage": marks})
    # what the encoder returned, without receipts or timings: equal digests mean equal numbers across runs
    outputs_sha = hashlib.sha256(json.dumps(
        {k: {f: v for f, v in e.items() if f != "receiptCid"} for k, e in sorted(evidence.items())},
        sort_keys=True, ensure_ascii=False).encode("utf-8")).hexdigest()
    ctx.journal.emit("review-queued", refs=[evidence_cid], reason="cue-contested", operators=queue)
    untouched = b"".join(json.dumps(op, ensure_ascii=False, sort_keys=True, default=str).encode("utf-8") + b"\n"
                         for op in ops) == ctx.state["operators_raw"]
    ctx.check("UC4", "evidence sits beside the operators: their bytes and weights are unchanged", untouched,
              untouched, True)
    ctx.check("UC4", "evidence is a proposal: nothing admitted, nothing rejected",
              all(not p["admitted"] for p in proposals) and all(not op["admitted"] for op in ops),
              {"proposals": len(proposals), "admitted": 0, "removed": 0}, "all admitted=False")
    ctx.check("UC4", "contested cues are queued for review, not decided", len(queue) == counts["cue-contested"],
              {"queued": len(queue), "supported": counts["cue-supported"]}, "queue = contested operators")
    with_p = [e for e in answered if e["p"] is not None]
    stats = {"masks": len(masks), "answered": len(answered), "refused": len(refused),
             "triage": {k: counts[k] for k in oplogic.TRIAGE if counts[k]},
             "cueRank1": sum(1 for e in with_p if e["rank"] == 1),
             "meanCueProbability": round(sum(e["p"] for e in with_p) / len(with_p), 4) if with_p else None,
             "inputTokens": usage["inputTokens"], "outputTokens": usage["outputTokens"],
             "secondsPerMask": round(usage["seconds"] / len(answered), 4) if answered else None}
    record = build_record(ctx.cas, source=source, nlp=_nlp_refs(ctx),
                          model={"lane": engine.name, "evidenceCid": evidence_cid, "stats": stats, "pins": pins},
                          model_id=identity, proposals=proposals,
                          reasons=["masked-cue evidence is not an admission; contested cues are queued for review"],
                          tool_receipts=_deterministic_receipts(ctx) + receipts,
                          quotation_receipts=ctx.state["quotation_receipts"],
                          candidates=ctx.state["candidates"])
    ctx.journal.emit("curation-record", refs=[record["recordCid"]], proposals=len(proposals), receipts=len(receipts))
    ctx.state.update(record=record, model_receipts=receipts, evidence=evidence, triage=marks, review_queue=queue,
                     model_identity=identity, model_pins=pins, model_runtime=runtime, model_stats=stats,
                     model_bundle=engine.bundle, model_egress=egress)
    return {"identity": identity, **stats, "reviewQueue": len(queue), "outputsSha256": outputs_sha,
            "recordCid": record["recordCid"]}


def _uc4_jeva(ctx: Ctx, bundle=None) -> dict:
    """The earlier decision lane, opt-in: Jeva's packed Q4 causal backbone scoring authored options on exact
    quotes. Outside the default profile; its worker exits after any error, so the refusal probe runs last."""
    fx = _load_fixture("f01-coinbase-manifest.json")
    source = ctx.state["source"]
    engine = JevaWorkerEngine(bundle=bundle) if bundle else JevaWorkerEngine()
    ok, why = engine.available()
    if not ok:
        return _model_unavailable(ctx, engine.name, why or "unavailable")
    engine.start(ctx.launcher)
    ctx.journal.emit("engine-ready", refs=[], engine=engine.name, identity=engine.identity, caps=engine.caps)
    proposals, receipts, observations = [], [], {}
    try:
        for fam in fx["questionFamilies"]:
            fr = ctx.state["amend_frame"] if fam["id"] == "QF-amendment" else ctx.state["remedy_frame"]
            quote = fr["quote"]
            t0 = time.time()
            reply = engine.infer(quote, fam["questions"])
            elapsed = time.time() - t0
            reply_cid = ctx.cas.put_json(reply)
            receipt = mint_receipt(ctx.cas, stage="model", engine=engine.name,
                                   engine_identity=engine.identity or "unresolved", fragment=engine.fragment,
                                   inputs=[source["extractedTextCid"]], outputs=[reply_cid],
                                   cost={"seconds": round(elapsed, 3),
                                         "inputTokens": reply.get("usage", {}).get("input_tokens"),
                                         "outputTokens": reply.get("usage", {}).get("output_tokens", 0)})
            receipts.append(receipt)
            proposals.append({"family": fam["id"], "raw": reply, "quote": quote,
                              "begin": fr["begin"], "end": fr["end"],
                              "receiptCid": receipt["receiptCid"], "admitted": False})
            ctx.journal.emit("engine-receipt", refs=[receipt["receiptCid"], reply_cid], stage="model",
                             family=fam["id"], seconds=round(elapsed, 3))
            ans, exp = reply["answers"], fam["expected"]
            if fam["id"] == "QF-amendment":
                reading = ans["reading"]["candidate_probabilities"]
                labels = ["deontic-permission", "epistemic-possibility", "obligation"]
                argmax = labels[reading.index(max(reading))]
                observations["amend_right_noul"] = ans["amend_right"]["noul"]
                observations["reading_argmax"] = argmax
                ctx.check("UC4", "amendment reading argmax matches reference observation",
                          argmax == exp["modelReadingArgmax"], argmax, exp["modelReadingArgmax"])
                lo, hi = exp["modelNoulRange"]
                ctx.check("UC4", "amend_right noul within reference range",
                          lo <= ans["amend_right"]["noul"] <= hi, ans["amend_right"]["noul"], [lo, hi])
            else:
                cond = ans["remedy"]["candidate_probabilities"]
                labels = ["terminate-and-close", "arbitration", "litigation", "refund", "none"]
                choice = labels[cond.index(max(cond))]
                observations["remedy_choice"] = choice
                observations["remedy_null"] = ans["remedy"]["null_probability"]
                ctx.check("UC4", "remedy choice matches reference", choice == exp["modelChoice"],
                          choice, exp["modelChoice"])
                ctx.check("UC4", "open-set null mass preserved (not renormalized)",
                          ans["remedy"]["null_probability"] >= exp["modelNullMin"],
                          ans["remedy"]["null_probability"], ">=%s" % exp["modelNullMin"])
                ctx.check("UC4", "conditional confidence on correct option",
                          max(cond) >= exp["modelConditionalMin"], max(cond), ">=%s" % exp["modelConditionalMin"])
            ctx.check("UC4", "%s output_tokens is zero (scorer, not generator)" % fam["id"],
                      reply.get("usage", {}).get("output_tokens", 0) == 0,
                      reply.get("usage", {}).get("output_tokens"), 0)
        long_state = (ctx.state["amend_frame"]["quote"] + " ") * 200
        refused, reason = False, ""
        try:
            engine.infer(long_state, {"x": {"decision_type": "noul",
                                            "question": "Does this clause give Coinbase the right to change the agreement?",
                                            "options": ["false", "true"], "allow_null": False}})
        except EngineError as e:
            refused, reason = True, str(e)
            refusal = mint_receipt(ctx.cas, stage="model", engine=engine.name,
                                   engine_identity=engine.identity or "unresolved", fragment=engine.fragment,
                                   inputs=[source["extractedTextCid"]], outputs=[],
                                   cost={"inputChars": len(long_state)}, status="refused", reason=reason)
            receipts.append(refusal)
            ctx.journal.emit("engine-refusal", refs=[refusal["receiptCid"]], reason=reason)
        ctx.check("UC4", "over-cap input refused, never silently truncated", refused,
                  reason if refused else "no error", "EngineError with token limit")
    finally:
        engine.close()
    record = build_record(ctx.cas, source=source, nlp=_nlp_refs(ctx),
                          model={"proposals": [p["raw"] for p in proposals]},
                          model_id=engine.identity or "unresolved", proposals=proposals,
                          reasons=["model proposals are not admissions"],
                          tool_receipts=_deterministic_receipts(ctx) + receipts,
                          quotation_receipts=ctx.state["quotation_receipts"],
                          candidates=ctx.state["candidates"])
    ctx.journal.emit("curation-record", refs=[record["recordCid"]],
                     proposals=len(proposals), receipts=len(receipts))
    ctx.check("UC4", "proposals retained without admission", all(not p["admitted"] for p in proposals),
              {"proposals": len(proposals)}, "all admitted=False")
    ctx.state.update(record=record, model_receipts=receipts, model_identity=engine.identity)
    return {"observations": observations, "recordCid": record["recordCid"]}


def uc5_evidence_states(ctx: Ctx) -> dict:
    fx = _load_fixture("f05-release-clause.json")
    ev = ConditionalEvaluator()
    clause = fx["clause"]
    results = {}
    for st in fx["evidenceStates"]:
        r = ev.evaluate(clause, st["premises"], context="asserted")
        results[st["id"]] = r["status"]
        receipt = mint_receipt(ctx.cas, stage="conditional", engine=ev.name, engine_identity=ev.identity,
                               fragment=ev.fragment, inputs=[ctx.cas.put_json(st)],
                               outputs=[ctx.cas.put_json(r)], cost={"cpu": True, "premises": len(st["premises"])})
        ctx.journal.emit("engine-receipt", refs=[receipt["receiptCid"]], state=st["id"], status=r["status"])
        ctx.check("UC5", "%s -> %s" % (st["id"], st["expected"]), r["status"] == st["expected"],
                  r["status"], st["expected"])
    # One premise list, two contexts: the hypothetical premise is invisible to the asserted evaluation.
    hyp = fx["hypothetical"]
    asserted = ev.evaluate(clause, hyp["premises"], context="asserted")
    hypothetical = ev.evaluate(clause, hyp["premises"], context="hypothetical")
    ctx.check("UC5", "hypothetical premise does not leak into asserted context",
              asserted["status"] == "pending" and hypothetical["status"] == "ineligible",
              {"asserted": asserted["status"], "hypothetical": hypothetical["status"]},
              {"asserted": "pending", "hypothetical": "ineligible"})
    ctx.state["f05"] = {"clause": clause, "results": results}

    derived = _load_fixture("f01-operators.json")["derivedConditional"]
    op = ctx.state.get("reference_ops", {}).get(derived["operator"])
    if op is None:
        ctx.journal.emit("derived-conditional-skipped", reason="operator lane did not run")
        return results
    operands = {o["role"]: o for o in op["operands"]}
    term = "%s:antecedent" % op["id"]
    derived_clause = {"id": op["id"], "antecedent": term, "consequent": "%s:consequent" % op["id"],
                      "antecedentQuote": operands["antecedent"]["quote"],
                      "consequentQuote": operands["consequent"]["quote"], "form": op["sumo"]["form"],
                      "context": "hypothetical", "operatorAdmitted": op["admitted"],
                      "sourceCid": ctx.state["source"]["originalCid"]}
    every = []
    for st in derived["evidenceStates"]:
        premises = [{**p, "term": term} for p in st["premises"]]
        every += premises
        r = ev.evaluate(derived_clause, premises, context="hypothetical")
        results[st["id"]] = r["status"]
        receipt = mint_receipt(ctx.cas, stage="conditional", engine=ev.name, engine_identity=ev.identity,
                               fragment=ev.fragment, inputs=[ctx.cas.put_json({"clause": derived_clause, "premises": premises})],
                               outputs=[ctx.cas.put_json(r)],
                               cost={"cpu": True, "premises": len(premises), "context": "hypothetical"})
        ctx.journal.emit("engine-receipt", refs=[receipt["receiptCid"]], state=st["id"], status=r["status"],
                         operator=op["id"])
        ctx.check("UC5", "%s -> %s over the source's own conditional" % (st["id"], st["expected"]),
                  r["status"] == st["expected"] and r["context"] == "hypothetical", r["status"], st["expected"])
    asserted = ev.evaluate(derived_clause, every, context="asserted")
    ctx.check("UC5", "evaluating an unadmitted operator asserts nothing",
              asserted["status"] == "pending" and op["admitted"] is False,
              {"asserted": asserted["status"], "operatorAdmitted": op["admitted"]},
              {"asserted": "pending", "operatorAdmitted": False})
    ctx.state["derived_conditional"] = derived_clause
    return results


def _amendment_candidate(ctx: Ctx) -> dict:
    """The amendment clause's candidate, found by its marker. With UC3 it is the operator candidate with
    both weights (and the cue evidence consulted, when UC4 ran); otherwise the frame candidate."""
    op = ctx.state.get("reference_ops", {}).get("amendment-permission")
    if op is not None:
        candidate = {"kind": "operator-candidate", "id": op["id"], "operator": op["operator"],
                     "construction": op["construction"], "reading": op["reading"], "readingRule": op["readingRule"],
                     "selectedReading": op["reading"], "cue": {k: op["cue"][k] for k in ("quote", "begin", "end")},
                     "articulation": op["articulation"], "form": op["sumo"]["form"],
                     "weights": {"deterministic": op["weights"]["deterministic"]["value"],
                                 "ontological": op["weights"]["ontological"]["value"],
                                 "anySense": op["weights"]["ontological"]["anySense"]},
                     "unresolved": op["unresolved"], "sourceCid": ctx.state["source"]["originalCid"],
                     "admitted": False}
        ev = ctx.state.get("evidence", {}).get(op["id"])
        if ev is not None:
            candidate["consulted"] = {"maskedCue": {k: ev.get(k) for k in ("cue", "p", "rank", "preferredClass")},
                                      "triage": ctx.state["triage"][op["id"]], "receiptCid": ev["receiptCid"]}
        return candidate
    for c in ctx.state["candidates"]:
        if c["rule"] == "modal-action" and AMENDMENT_MARKER in (c["sentenceQuote"] or ""):
            return c
    raise EngineError("no candidate for the amendment clause")


def uc6_admit_revoke(ctx: Ctx) -> dict:
    if not _requires(ctx, "UC6", "candidates", "UC2"):
        return {}
    candidate = _amendment_candidate(ctx)
    ctx.check("UC6", "the candidate under review is the amendment clause, selected by marker",
              candidate["selectedReading"] == "permission"
              and AMENDMENT_MARKER in (candidate.get("articulation") or candidate.get("sentenceQuote") or ""),
              {"kind": candidate["kind"], "reading": candidate["selectedReading"]}, {"reading": "permission"})
    review = ctx.ledger.admit(candidate=candidate, scope="legal-pack/amendment",
                              reviewer="operator", context="asserted")
    ctx.journal.emit("review-admitted", refs=[review["reviewCid"]], scope="legal-pack/amendment")
    source = ctx.state["source"]
    deps = [source["originalCid"], review["reviewCid"], ctx.state["stanza_identity"]]
    if "operators_identity" in ctx.state:
        deps.append(ctx.state["operators_identity"])
    reference = {"reading": "permission", "readingRule": "agentive-subject"}
    prod = ctx.ledger.propose_production(pid="amendment-reading-route",
                                         condition={"question": "amendment-modality", "source": source["originalCid"]},
                                         action={"effect": "route-to-bounded-analysis"},
                                         dependencies=deps, reference_result=reference)
    ctx.check("UC6", "production starts in shadow", prod["status"] == "shadow", prod["status"], "shadow")
    observed = {"reading": candidate["selectedReading"], "readingRule": candidate["readingRule"]}
    ctx.check("UC6", "shadow agrees with reference result", observed == prod["referenceResult"], observed, reference)
    ctx.ledger.activate(prod["id"])
    first = ctx.ledger.try_activate(prod["id"], generation=1, query={"q": "amendment-modality"})
    ctx.check("UC6", "active production dispatches with effect receipt", first["dispatched"],
              first.get("effectReceiptCid"), "dispatched")
    withdrawal = ctx.ledger.withdraw(review["reviewCid"], reason="operator withdrew the reading review")
    ctx.journal.emit("review-withdrawn", refs=[review["reviewCid"]],
                     suspended=withdrawal["suspendedProductions"])
    ctx.check("UC6", "withdrawal suspends the production generation",
              prod["status"] == "suspended" and prod["id"] in withdrawal["suspendedProductions"],
              prod["status"], "suspended")
    stale = ctx.ledger.try_activate(prod["id"], generation=1, query={"q": "amendment-modality"})
    ctx.check("UC6", "stale activation refused after withdrawal", not stale["dispatched"] and stale["returnsToAnalysis"],
              stale.get("reason"), "refused, returns to analysis")
    older = ctx.ledger.try_activate(prod["id"], generation=0, query={"q": "amendment-modality"})
    ctx.check("UC6", "older generation fenced", not older["dispatched"] and older["reason"] == "stale generation",
              older.get("reason"), "stale generation")
    ctx.journal.emit("returns-to-analysis", refs=[source["originalCid"]], production=prod["id"])
    return {"production": prod["id"], "status": prod["status"], "activations": prod["activations"],
            "candidate": candidate["kind"]}


def write_articulation(ctx: Ctx):
    """The slice's operator logic as text, with the cue evidence when the encoder lane ran. Returns its CID."""
    if "operators" not in ctx.state:
        return None
    source = ctx.state["source"]
    identities = {"source": source["originalCid"], "parser": ctx.state["stanza_identity"],
                  "operators": ctx.state["operators_identity"],
                  "sumo": next((p for p in ctx.state["stanza_summary"]["sumo"]["pins"] if "ref =" in p), "?").lstrip("# "),
                  "encoder": ctx.state.get("model_identity") if ctx.state.get("evidence") else "not run"}
    begin = ctx.state["slice_begin"]
    text = oplogic.articulate(source["name"], begin, begin + len(ctx.state["slice_text"]), ctx.state["operators"],
                              ctx.state["logic"], ctx.state.get("evidence") or {}, identities)
    (ctx.run_dir / "articulation.txt").write_text(text, encoding="utf-8")
    cid = ctx.cas.put(text.encode("utf-8"))
    ctx.journal.emit("articulation-written", refs=[cid], operators=len(ctx.state["operators"]),
                     withEvidence=bool(ctx.state.get("evidence")))
    return cid


USE_CASES = {"uc1": uc1_intake, "uc2": uc2_frames, "uc3": uc3_operators, "uc4": uc4_evidence,
             "uc5": uc5_evidence_states, "uc6": uc6_admit_revoke}
REQUIRES = {"uc1": [], "uc2": ["uc1"], "uc3": ["uc1", "uc2"], "uc4": ["uc1", "uc2", "uc3"], "uc5": [],
            "uc6": ["uc1", "uc2"]}
