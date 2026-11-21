"""Unit tests for the LCNC baseline contracts: no weights, no parser, no network.

The stub engine is SYNTHETIC and labeled; these tests verify contracts, not model quality. Live engine
evidence comes from `demo.py run` reports. Two tests start a subprocess under the macOS sandbox and are
skipped where it is absent.
"""
import io
import json
import os
import platform
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from demo import baseline_graph  # noqa: E402
from lcnc import airgap, inventory, oplogic  # noqa: E402
from lcnc.cas import Cas, cid_of, cid_of_bytes  # noqa: E402
from lcnc.curation import build_record, causal_candidates, quotation, retain_source  # noqa: E402
from lcnc.engines import ConditionalEvaluator, EngineError, JevaWorkerEngine, Q4BertEngine, StubEngine  # noqa: E402
from lcnc.graph import Graph  # noqa: E402
from lcnc.journal import Journal  # noqa: E402
from lcnc.receipts import mint_receipt, quotation_receipt  # noqa: E402
from lcnc.review import ReviewLedger  # noqa: E402
from lcnc.scenarios import AMENDMENT_MARKER, Ctx, _amendment_candidate, uc5_evidence_states  # noqa: E402

FIXTURES = ROOT / "fixtures"
SANDBOX = platform.system() == "Darwin" and os.access(airgap.SANDBOX_EXEC, os.X_OK)


class CasTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.cas = Cas(Path(self.tmp.name))

    def tearDown(self):
        self.tmp.cleanup()

    def test_roundtrip_and_identity(self):
        cid = self.cas.put(b"hello")
        self.assertEqual(cid, cid_of_bytes(b"hello"))
        self.assertEqual(self.cas.get(cid), b"hello")

    def test_identical_bytes_are_noop(self):
        a, b = self.cas.put(b"x"), self.cas.put(b"x")
        self.assertEqual(a, b)

    def test_tamper_detected(self):
        cid = self.cas.put(b"original")
        path = self.cas._path(cid)
        path.write_bytes(b"tampered")
        with self.assertRaises(ValueError):
            self.cas.get(cid)

    def test_json_cid_stable(self):
        self.assertEqual(cid_of({"a": 1, "b": 2}), cid_of({"b": 2, "a": 1}))


class JournalTests(unittest.TestCase):
    def test_emit_read_replay(self):
        with tempfile.TemporaryDirectory() as d:
            j = Journal(Path(d) / "j.jsonl", "e1", "def1")
            j.emit("a", refs=["sha256:" + "0" * 64], x=1)
            j.emit("b", y=2)
            j.close()
            events = list(Journal.read(Path(d) / "j.jsonl"))
            self.assertEqual([e["seq"] for e in events], [1, 2])
            self.assertEqual(events[0]["kind"], "a")
            j2 = Journal(Path(d) / "j.jsonl", "e1", "def1")
            e3 = j2.emit("c")
            self.assertEqual(e3["seq"], 3)
            j2.close()


class GraphTests(unittest.TestCase):
    def _graph(self):
        return Graph([{"id": "s", "type": "source", "config": {}},
                      {"id": "e", "type": "engine", "context": "derived", "config": {"seats": 5}}],
                     [("s", "e")], label="t")

    def test_rescaffold_new_identity(self):
        g = self._graph()
        child = g.with_config("e", seats=3)
        self.assertNotEqual(g.cid, child.cid)
        self.assertEqual(g.nodes["e"]["config"]["seats"], 5)  # parent untouched

    def test_bad_type_rejected(self):
        with self.assertRaises(ValueError):
            Graph([{"id": "x", "type": "wizard"}], [])

    def test_bad_context_rejected(self):
        with self.assertRaises(ValueError):
            Graph([{"id": "x", "type": "engine", "context": "default-union"}], [])

    def test_dangling_edge_rejected(self):
        with self.assertRaises(ValueError):
            Graph([{"id": "x", "type": "source"}], [("x", "nope")])

    def test_default_graph_has_one_model_lane_and_it_is_an_encoder(self):
        g = baseline_graph("q4bert")
        engines = {n["id"]: n["config"] for n in g.nodes.values() if n["type"] == "engine"}
        self.assertEqual(sorted(engines), ["eng-conditional", "eng-operators", "eng-q4bert", "eng-stanza"])
        self.assertFalse(any(c.get("generative") or c.get("causalLanguageModel") for c in engines.values()))
        self.assertEqual([i for i, c in engines.items() if c.get("lane") == "optional-model"], ["eng-q4bert"])

    def test_model_choice_is_part_of_the_definition(self):
        cids = {m: baseline_graph(m).cid for m in ("q4bert", "none", "jeva")}
        self.assertEqual(len(set(cids.values())), 3)
        self.assertNotIn("eng-q4bert", baseline_graph("none").nodes)
        self.assertTrue(baseline_graph("jeva").nodes["eng-jeva"]["config"]["causalLanguageModel"])


class QuotationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.cas = Cas(Path(self.tmp.name))
        self.text = "Alpha \U0001F600 beta gamma"
        self.source = retain_source(self.cas, original=self.text.encode(), text=self.text,
                                    name="t", media_type="text/plain", origin="test",
                                    begin=0, end=len(self.text))

    def tearDown(self):
        self.tmp.cleanup()

    def test_offsets_and_u16(self):
        q = quotation(self.source, self.text, 6, 12)  # emoji + space + 'beta'
        self.assertEqual(q["quote"], "\U0001F600 beta")
        self.assertEqual(q["u16Begin"], 6)
        self.assertEqual(q["u16End"], 13)  # emoji counts as 2 UTF-16 units
        self.assertEqual(q["authentication"], "unverified")

    def test_out_of_range_rejected(self):
        with self.assertRaises(ValueError):
            quotation(self.source, self.text, 0, len(self.text) + 1)

    def test_quotation_receipt_not_admission(self):
        q = quotation(self.source, self.text, 0, 5)
        qr = quotation_receipt(self.cas, source_cid=self.source["originalCid"],
                               text_cid=self.source["extractedTextCid"], begin=0, end=5,
                               quote=q["quote"], u16_begin=0, u16_end=5, reader_receipt_cid="r")
        self.assertFalse(qr["semanticAdmission"])


class EvidenceStateTests(unittest.TestCase):
    def setUp(self):
        self.fx = json.loads((FIXTURES / "f05-release-clause.json").read_text())
        self.ev = ConditionalEvaluator()

    def test_four_states(self):
        for st in self.fx["evidenceStates"]:
            r = self.ev.evaluate(self.fx["clause"], st["premises"])
            self.assertEqual(r["status"], st["expected"], st["id"])

    def test_hypothetical_premise_is_invisible_to_the_asserted_context(self):
        premises = self.fx["hypothetical"]["premises"]              # one list, read in two contexts
        self.assertEqual(self.ev.evaluate(self.fx["clause"], premises, context="asserted")["status"], "pending")
        self.assertEqual(self.ev.evaluate(self.fx["clause"], premises, context="hypothetical")["status"], "ineligible")

    def test_a_hypothetical_branch_still_sees_what_is_asserted(self):
        asserted = self.fx["evidenceStates"][1]["premises"]         # affirmed, asserted
        both = asserted + self.fx["hypothetical"]["premises"]       # plus a hypothetical denial
        self.assertEqual(self.ev.evaluate(self.fx["clause"], both, context="asserted")["status"], "eligible")
        self.assertEqual(self.ev.evaluate(self.fx["clause"], both, context="hypothetical")["status"], "conflict")

    def test_evaluation_requires_a_known_context(self):
        with self.assertRaises(ValueError):
            self.ev.evaluate(self.fx["clause"], [], context="default")

    def test_missing_is_not_negative(self):
        r = self.ev.evaluate(self.fx["clause"], [])
        self.assertIsNone(r["consequent"])
        self.assertIn("not a negative premise", r["note"])


class ReviewTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.cas = Cas(Path(self.tmp.name))
        self.ledger = ReviewLedger(self.cas)
        self.review = self.ledger.admit(candidate={"rule": "modal-action"}, scope="s",
                                        reviewer="operator")
        self.prod = self.ledger.propose_production(
            pid="p1", condition={"q": "x"}, action={"effect": "route"},
            dependencies=[self.review["reviewCid"]], reference_result={"reading": "permission"})

    def tearDown(self):
        self.tmp.cleanup()

    def test_admit_requires_context(self):
        with self.assertRaises(ValueError):
            self.ledger.admit(candidate={}, scope="s", reviewer="o", context="default")

    def test_lifecycle_and_revocation(self):
        self.assertEqual(self.prod["status"], "shadow")
        self.ledger.activate("p1")
        first = self.ledger.try_activate("p1", generation=1, query={})
        self.assertTrue(first["dispatched"])
        self.ledger.withdraw(self.review["reviewCid"], "withdrawn")
        self.assertEqual(self.prod["status"], "suspended")
        stale = self.ledger.try_activate("p1", generation=1, query={})
        self.assertFalse(stale["dispatched"])
        self.assertTrue(stale["returnsToAnalysis"])
        older = self.ledger.try_activate("p1", generation=0, query={})
        self.assertEqual(older["reason"], "stale generation")
        self.assertEqual(self.prod["activations"], 1)  # reuse never inflates

    def test_cannot_activate_withdrawn(self):
        self.ledger.withdraw(self.review["reviewCid"], "w")
        with self.assertRaises(ValueError):
            self.ledger.activate("p1")


class RecordTests(unittest.TestCase):
    def test_candidates_never_admitted_and_stub_labeled(self):
        with tempfile.TemporaryDirectory() as d:
            cas = Cas(Path(d))
            frames = [{"frame": {"rule": "modal-action", "quote": "We may amend.",
                                 "begin": 0, "end": 13, "readings": ["permission"]},
                       "inference": {"reading": "permission", "readingRule": "agentive-subject"}}]
            cands = causal_candidates(frames, "sha256:" + "0" * 64)
            self.assertTrue(cands and all(not c["admitted"] for c in cands))
            stub = StubEngine()
            reply = stub.infer("state", {"q": {"decision_type": "noul"}})
            self.assertTrue(reply["usage"]["synthetic"])
            source = retain_source(cas, original=b"t", text="t", name="n", media_type="text/plain",
                                   origin="o", begin=0, end=1)
            receipt = mint_receipt(cas, stage="model", engine=stub.name, engine_identity=stub.identity,
                                   fragment=stub.fragment, inputs=[], outputs=[], cost={})
            rec = build_record(cas, source=source, nlp=None, model=reply, model_id=stub.identity,
                               proposals=[{"raw": reply, "admitted": False}], reasons=[],
                               tool_receipts=[receipt], quotation_receipts=[], candidates=cands)
            self.assertTrue(cas.has(rec["recordCid"]))
            self.assertEqual(rec["record"]["submittedReceiptCids"], [])


class FakeProc:
    def __init__(self, out):
        self.stdin, self.stdout, self.stderr = io.StringIO(), io.StringIO(out), io.StringIO()
        self.pid = 0

    def poll(self):
        return None


class WorkerProtocolTests(unittest.TestCase):
    def test_jeva_null_error_is_success_and_a_real_error_ends_the_worker(self):
        e = JevaWorkerEngine()
        e.proc = FakeProc('{"available":true,"loaded":true,"error":null}\n')  # type: ignore[assignment]
        self.assertTrue(e._send({"op": "status"})["available"])
        self.assertFalse(e.dead)
        e.proc = FakeProc('{"error":"Qwen3.5 token count exceeds configured MLX limit 2048"}\n')  # type: ignore[assignment]
        with self.assertRaises(EngineError):
            e._send({"op": "infer"})
        self.assertTrue(e.dead)

    def test_q4bert_request_error_raises_and_item_refusals_do_not(self):
        e = Q4BertEngine()
        e.proc = FakeProc('{"id":"a","error":"ValueError: unknown op"}\n'
                          '{"id":"b","results":[{"key":1,"status":"refused","reason":"600 tokens; limit is 512; '
                          'truncation is disabled"}],"usage":{"sequences":0,"inputTokens":0,"outputTokens":0,"seconds":0}}\n')  # type: ignore[assignment]
        with self.assertRaises(EngineError):
            e._send({"op": "nonsense"})
        reply = e.fill([{"key": 1, "text": "x", "begin": 0, "end": 1}], {})
        self.assertEqual(reply["results"][0]["status"], "refused")       # a refusal is a result, not an engine failure
        self.assertEqual(json.loads(e.proc.stdin.getvalue().splitlines()[-1])["op"], "fill")

    def test_q4bert_absent_bundle_is_unavailable_with_the_reason(self):
        with tempfile.TemporaryDirectory() as d:
            ok, why = Q4BertEngine(bundle=Path(d) / "absent", python=Path(sys.executable)).available()
            self.assertFalse(ok)
            self.assertIn("model.safetensors", why or "")
            self.assertIn("nothing is downloaded", why or "")

    def test_q4bert_pins_come_from_the_bundle_records(self):
        with tempfile.TemporaryDirectory() as d:
            b = Path(d)
            (b / "provenance.json").write_text(json.dumps({
                "name": "x/bert", "source": {"snapshot": "abc", "files": {"model.safetensors": "f" * 64}},
                "quantization": {"linear": {"bits": 4, "group_size": 32}}, "storedBitsPerWeight": 6.65,
                "relativeFrobenius": {"linearMean": 0.08}}))
            (b / "config.json").write_text(json.dumps({"q4bert": {"lower_case": True}}))
            (b / "SHA256SUMS").write_text("%s  model.safetensors\n%s  vocab.txt\n" % ("a" * 64, "b" * 64))
            pins = Q4BertEngine(bundle=b).pins()
            self.assertEqual((pins["weightsSha256"], pins["tokenizerSha256"]), ("a" * 64, "b" * 64))
            self.assertEqual(pins["checkpoint"], {"name": "x/bert", "snapshot": "abc", "float32Sha256": "f" * 64})
            self.assertEqual((pins["maxSequenceTokens"], pins["normalization"]), (512, "lower-case, accents stripped"))


TEXT = "xx If you do not agree, we may close it. Any use will result in loss."


def span(quote, at=0):
    b = TEXT.index(quote, at)
    return {"quote": quote, "begin": b + 100, "end": b + 100 + len(quote)}       # the slice begins at 100


def operator(i, cls, name, construction, cue, operands, base, level, resolved, required, checks, sentence):
    statuses = [c["status"] for c in checks]
    entailed = statuses.count("entailed")
    other = sum(1 for c in checks if c["status"] != "entailed" and c.get("otherSensesEntailed"))
    sb = TEXT.index(sentence)
    return {"id": "op-%d" % i, "sentence": i, "class": cls, "operator": name, "construction": construction,
            "cue": {**span(cue), "keyText": cue}, "operands": [{"role": r, **span(q)} for r, q in operands],
            "sumo": {"form": "(f)", "checks": checks}, "readingRule": None, "reading": None,
            "weights": {"deterministic": {"value": round(base * resolved / required, 4), "base": base, "level": level,
                                          "resolved": resolved, "required": required,
                                          "unresolvedRoles": ["r"] * (required - resolved)},
                        "ontological": {"value": round(entailed / len(checks), 4) if checks else None,
                                        "anySense": round((entailed + other) / len(checks), 4) if checks else None,
                                        "entailed": entailed, "otherSense": other, "total": len(checks),
                                        "statuses": {s: statuses.count(s) for s in sorted(set(statuses))},
                                        "disjoint": "disjoint" in statuses}},
            "unresolved": ["legal-force-not-established"], "articulation": "%s [%s]" % (name, operands[0][1]),
            "admitted": False, "kind": "candidate", "sentenceBegin": sb + 100, "sentenceEnd": sb + 100 + len(sentence)}


def sample_ops():
    return [operator(0, "condition", "IMPLIES", "mark(if)+advcl", "If",
                     [("antecedent", "you do not agree"), ("consequent", "we may close it")], 0.8, "explicit", 6, 6,
                     [{"status": "entailed"}, {"status": "entailed"}], "If you do not agree, we may close it."),
            operator(1, "cause", "CAUSES", "result+obl(in)", "result", [("cause", "Any use"), ("effect", "loss")],
                     0.9, "lexical", 3, 4,
                     [{"status": "entailed"}, {"status": "not-entailed", "otherSensesEntailed": ["use.n.02→Process"]},
                      {"status": "disjoint", "otherSensesEntailed": []}], "Any use will result in loss.")]


class OperatorReadingTests(unittest.TestCase):
    def test_weights_recompute_and_a_changed_value_is_a_fault(self):
        ops = sample_ops()
        self.assertEqual([oplogic.weight_faults(op) for op in ops], [[], []])
        self.assertEqual(ops[1]["weights"]["deterministic"]["value"], 0.675)
        self.assertEqual((ops[1]["weights"]["ontological"]["value"], ops[1]["weights"]["ontological"]["anySense"]), (0.3333, 0.6667))
        ops[0]["weights"]["deterministic"]["value"] = 0.95
        self.assertIn("deterministic value is not base x resolved/required", oplogic.weight_faults(ops[0]))
        ops[1]["weights"]["ontological"]["anySense"] = 1.0
        self.assertIn("anySense is not (entailed + other sense)/total", oplogic.weight_faults(ops[1]))
        ops[1]["weights"]["deterministic"]["base"] = 0.7
        self.assertIn("base 0.7 is not a level", oplogic.weight_faults(ops[1]))

    def test_a_quote_that_is_not_the_text_at_its_offsets_is_reported(self):
        ops = sample_ops()
        self.assertEqual(oplogic.quote_faults(ops, TEXT, 100), [])
        ops[1]["operands"][0]["quote"] = "Any usage"
        self.assertEqual(oplogic.quote_faults(ops, TEXT, 100), ["op-1"])
        self.assertEqual(oplogic.quote_faults(sample_ops(), TEXT, 99), ["op-0", "op-1"])     # a wrong slice origin

    def test_operators_are_found_by_marker_not_by_position(self):
        ops = sample_ops()
        self.assertEqual(oplogic.find(ops, TEXT, 100, "result in loss", "cause", "result+obl(in)")["id"], "op-1")
        with self.assertRaises(LookupError):
            oplogic.find(ops, TEXT, 100, "no such words", "cause", "result+obl(in)")
        with self.assertRaises(LookupError):
            oplogic.find(ops, TEXT, 100, "result in loss", "condition", "mark(if)+advcl")

    def test_reference_mismatches_name_the_field(self):
        op = sample_ops()[1]
        self.assertEqual(oplogic.mismatches(op, {"operator": "CAUSES", "deterministic": 0.675, "anySense": 0.6667,
                                                 "operandQuotes": {"effect": "loss"},
                                                 "unresolvedIncludes": "legal-force-not-established"}), {})
        wrong = oplogic.mismatches(op, {"deterministic": 0.9, "operandQuotes": {"cause": "use"}, "unresolvedIncludes": "x"})
        self.assertEqual(sorted(wrong), ["deterministic", "operandQuotes.cause", "unresolvedIncludes"])
        self.assertEqual(wrong["deterministic"], {"expected": 0.9, "observed": 0.675})

    def test_triage_routes_and_never_decides(self):
        op = sample_ops()[0]
        ok = {"status": "ok", "p": 0.87, "rank": 1, "concordant": True, "preferredClass": "condition",
              "classMass": {"condition": 0.873}, "top": [["if", 0.87], ["or", 0.04], ["that", 0.03]]}
        self.assertEqual(oplogic.triage(op, ok), "cue-supported")
        self.assertEqual(oplogic.triage(op, {**ok, "concordant": False}), "cue-contested")
        self.assertEqual(oplogic.triage(op, {**ok, "p": None, "rank": None}), "cue-not-one-token")
        self.assertEqual(oplogic.triage(op, {"status": "refused", "reason": "600 tokens; limit is 512"}), "refused")
        self.assertEqual(oplogic.triage(op, None), "no-classed-cue")
        self.assertFalse(op["admitted"])
        self.assertIn("p 0.870, rank 1, top if/or/that; model prefers condition 0.873: supported", oplogic.evidence_line(ok))
        self.assertIn("contested, to review", oplogic.evidence_line({**ok, "concordant": False}))
        self.assertIn("refused: 600 tokens", oplogic.evidence_line({"status": "refused", "reason": "600 tokens; limit is 512"}))

    def test_articulation_shows_parts_of_both_weights_and_exact_quotes(self):
        ops = sample_ops()
        logic = [{"sentence": 0, "begin": 103, "end": 140, "quote": "If you do not agree,\nwe may close it.", "form": "(=> a c)"},
                 {"sentence": 1, "begin": 141, "end": 168, "quote": "Any use will result in loss.", "form": "(causes a b)"},
                 {"sentence": 2, "begin": 169, "end": 175, "quote": "No operators here.", "form": "<x>"}]
        ev = {"op-1": {"status": "ok", "p": 0.99, "rank": 1, "concordant": True, "preferredClass": "causation",
                       "classMass": {"causation": 0.995}, "top": [["result", 0.99]]}}
        text = oplogic.articulate("Agreement", 100, 175, ops, logic, ev, {"operators": "id-1"})
        self.assertIn("3 sentences, 2 operator candidates; nothing here is admitted", text)
        self.assertIn("If you do not agree,\\nwe may close it.", text)                 # a line break stays visible
        self.assertIn("deterministic 0.675 = 0.9 lexical x 3/4 roles; unresolved role: r", text)
        self.assertIn("ontological   0.3333 = 1/3 SUMO checks (1 disjoint, 1 entailed, 1 not-entailed); any sense 0.6667", text)
        self.assertIn("model prefers causation 0.995: supported", text)
        self.assertIn("none: the cue word has no class in the cue lexicon", text)       # op-0 has no evidence
        self.assertNotIn("No operators here.", text)
        self.assertEqual(text, oplogic.articulate("Agreement", 100, 175, ops, logic, ev, {"operators": "id-1"}))


class ScenarioTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        cas = Cas(root / "cas")
        self.ctx = Ctx(run_dir=root, cas=cas, journal=Journal(root / "j.jsonl", "e", "d"), ledger=ReviewLedger(cas))

    def tearDown(self):
        self.ctx.journal.close()
        self.tmp.cleanup()

    def test_evidence_states_over_a_conditional_read_from_the_source_stay_hypothetical(self):
        op = sample_ops()[0]
        self.ctx.state.update(reference_ops={"remedy-conditional": op}, source={"originalCid": "sha256:" + "1" * 64})
        results = uc5_evidence_states(self.ctx)
        self.assertEqual([c["name"] for c in self.ctx.checks if not c["ok"]], [])
        self.assertEqual({k: v for k, v in results.items() if k.startswith("derived")},
                         {"derived-missing": "pending", "derived-affirmed": "eligible",
                          "derived-denied": "ineligible", "derived-conflict": "conflict"})
        self.assertEqual(self.ctx.state["derived_conditional"]["antecedentQuote"], "you do not agree")
        self.assertEqual(self.ctx.state["derived_conditional"]["context"], "hypothetical")
        self.assertFalse(op["admitted"])

    def test_without_the_operator_lane_only_the_authored_fixture_is_evaluated(self):
        results = uc5_evidence_states(self.ctx)
        self.assertEqual(sorted(results), ["state-affirmed", "state-conflict", "state-denied", "state-missing"])
        self.assertTrue(all(c["ok"] for c in self.ctx.checks))

    def test_the_amendment_candidate_is_chosen_by_marker_not_by_being_first(self):
        first = {"rule": "modal-action", "selectedReading": "permission", "kind": "candidate",
                 "sentenceQuote": "This User Agreement governs such other services that may be offered by Coinbase."}
        amendment = {"rule": "modal-action", "selectedReading": "permission", "kind": "candidate",
                     "sentenceQuote": AMENDMENT_MARKER + " at any time."}
        self.ctx.state["candidates"] = [first, amendment]
        self.assertIs(_amendment_candidate(self.ctx), amendment)
        self.ctx.state["candidates"] = [first]
        with self.assertRaises(EngineError):
            _amendment_candidate(self.ctx)


GUARDED = r'''
import json, socket, sys
out = {}
def attempt(name, fn):
    try:
        fn(); out[name] = "allowed"
    except PermissionError as e:
        out[name] = "refused"
    except OSError as e:
        out[name] = type(e).__name__
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM); attempt("connect", lambda: s.connect(("192.0.2.1", 80))); s.close()
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); attempt("sendto", lambda: s.sendto(b"x", ("192.0.2.1", 53))); s.close()
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM); attempt("bind-loopback", lambda: s.bind(("127.0.0.1", 0))); s.close()
attempt("resolve", lambda: socket.getaddrinfo("example.com", 80))
attempt("numeric", lambda: socket.getaddrinfo("192.0.2.1", 80))
a, b = socket.socketpair(); a.sendall(b"ok"); out["unix"] = b.recv(2).decode(); a.close(); b.close()
out["ledger"] = sys.modules["egress_guard"].snapshot()
out["argv"] = sys.argv[1:]
print(json.dumps(out))
'''


class EgressTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.script = self.dir / "engine.py"
        self.script.write_text(GUARDED)

    def tearDown(self):
        self.tmp.cleanup()

    def test_guard_refuses_and_records_without_payload(self):
        launcher = airgap.Launcher(self.dir, "application-local")
        code, out, err = launcher.run("engine", sys.executable, self.script, ["a", "b"], cwd=self.dir)
        self.assertEqual(code, 0, err)
        seen = json.loads(out)
        self.assertEqual({k: seen[k] for k in ("connect", "sendto", "bind-loopback", "resolve", "numeric", "unix", "argv")},
                         {"connect": "refused", "sendto": "refused", "bind-loopback": "refused", "resolve": "refused",
                          "numeric": "allowed", "unix": "ok", "argv": ["a", "b"]})
        self.assertEqual((seen["ledger"]["attempts"], seen["ledger"]["external"], seen["ledger"]["loopback"]), (4, 3, 1))
        ledger = launcher.ledger()
        row = ledger["processes"][0]
        self.assertEqual((row["name"], row["program"], row["exit"], row["ledger"]), ("engine", "engine.py", 0, "egress_guard.py"))
        self.assertEqual([(r["op"], r["scope"]) for r in row["reported"]["refused"]],
                         [("connect", "external"), ("sendto", "external"), ("bind", "loopback"), ("getaddrinfo", "external")])
        self.assertTrue(all(r["by"].endswith("engine.py:<lambda>") for r in row["reported"]["refused"]))
        self.assertNotIn("b'x'", (self.dir / "egress.jsonl").read_text())           # destinations, never payload
        summary = airgap.summarize(ledger, "application-local")
        self.assertEqual((summary["processes"], summary["ledgersReported"], summary["externalAttempts"], summary["loopbackAttempts"]),
                         (1, 1, 3, 1))

    def test_a_process_without_a_ledger_is_visible_as_such(self):
        launcher = airgap.Launcher(self.dir, "application-local")
        launcher.inventory.append({"name": "ghost", "pid": 999999, "program": "x.py", "interpreter": "python",
                                   "ledger": "egress_guard.py"})
        summary = airgap.summarize(launcher.ledger(), "application-local")
        self.assertEqual((summary["pythonProcesses"], summary["ledgersReported"]), (1, 0))
        self.assertFalse(summary["kernelDeniedEverywhere"])

    def test_probes_never_go_past_the_first_when_the_kernel_allows_network(self):
        result = airgap.probes()
        if result["denied"]:
            self.assertEqual(len(result["probes"]), 6)                  # the tests themselves run denied
        else:
            self.assertEqual(list(result["probes"]), ["udp connect 192.0.2.1:9"])
            self.assertIn("would emit traffic", result["note"])

    def test_unsupported_platform_refuses_instead_of_pretending(self):
        real, mark = platform.system, os.environ.pop(airgap.MARK, None)
        platform.system = lambda: "Plan9"
        try:
            with self.assertRaisesRegex(airgap.AirgapError, "no verified kernel egress control for Plan9"):
                airgap.enter(["demo.py", "run"])
        finally:
            platform.system = real
            if mark is not None:
                os.environ[airgap.MARK] = mark

    def test_policy_is_data(self):
        blocked = airgap.policy("egress-blocked")
        self.assertEqual(blocked["rule"], "(version 1)\n(allow default)\n(deny network*)\n")
        self.assertEqual(len(blocked["ruleSha256"]), 64)
        self.assertIsNone(airgap.policy("application-local")["kernelControl"])

    @unittest.skipUnless(SANDBOX, "macOS sandbox-exec is not available")
    def test_under_the_sandbox_every_probe_is_denied(self):
        code = "import json, sys; sys.path.insert(0, %r); from lcnc import airgap; print(json.dumps(airgap.probes()))" % str(ROOT)
        done = subprocess.run([airgap.SANDBOX_EXEC, "-p", airgap.PROFILE, sys.executable, "-c", code],
                              capture_output=True, text=True)
        self.assertEqual(done.returncode, 0, done.stderr)
        result = json.loads(done.stdout)
        self.assertTrue(result["denied"], result)
        self.assertEqual(len(result["probes"]), 6)
        self.assertTrue(result["probes"]["resolve example.com"].startswith("no resolution"))

    @unittest.skipUnless(SANDBOX, "macOS sandbox-exec is not available")
    def test_a_child_of_a_sandboxed_process_reports_the_kernel_denial(self):
        log = self.dir / "sandboxed.jsonl"
        done = subprocess.run([airgap.SANDBOX_EXEC, "-p", airgap.PROFILE, sys.executable, str(airgap.GUARD), str(self.script)],
                              capture_output=True, text=True, env=dict(os.environ, **{airgap.LOG_ENV: str(log)}))
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertTrue(json.loads(done.stdout)["ledger"]["kernelDenied"])
        self.assertEqual(json.loads(log.read_text())["kernelProbe"], "EPERM")


class InventoryTests(unittest.TestCase):
    def test_code_and_fixtures_are_listed_with_digests(self):
        rows = inventory.build({}, "none")
        code = [r for r in rows if r["role"] == "code"]
        names = {r["name"] for r in code}
        self.assertTrue({"utils/stanza/legalframes/operators.py", "utils/q4bert/worker.py", "utils/lcnc-demo/demo.py",
                         "utils/lcnc-demo/airgap/egress_guard.py", "utils/lcnc-demo/fixtures/q4bert-pin.json"} <= names)
        self.assertTrue(all(len(r["sha256"]) == 64 and r["bytes"] >= 0 for r in code))
        self.assertFalse(any(r["role"] == "model-bundle" for r in rows))         # no model lane, nothing claimed
        self.assertEqual([r["status"] for r in inventory.build({}, "q4bert") if r["role"] == "model-bundle"],
                         ["not loaded in this run"])
        jeva = [r for r in inventory.build({"model_identity": "bundle@device:abc"}, "jeva") if r["role"] == "model-bundle"]
        self.assertEqual([r.get("identity") for r in jeva], ["bundle@device:abc"])


if __name__ == "__main__":
    unittest.main()
