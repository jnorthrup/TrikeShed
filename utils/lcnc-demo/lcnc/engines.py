"""Engine adapters with declared fragments, output semantics and resource contracts.

Real engines only. The default profile runs no generative language model (no Jeva, no LLM):

  StanzaEngine          Stanza's trained tagger and parser (no sampling), frame rules, pinned SUMO
                        (utils/stanza/run.py); repeatable, and as fallible as any parser
  OperatorEngine        operator logic read off the same parse, weighed two ways (utils/stanza/operators.py)
  Q4BertEngine          the one optional model lane: a 4-bit BERT encoder scoring a masked cue position
                        (utils/q4bert/worker.py). It writes no text and decides nothing.
  ConditionalEvaluator  finite evidence states of one conditional, scoped by context

JevaWorkerEngine (a quantized causal language-model backbone used as a scorer) stays selectable with
--model jeva and is outside the default profile. Every adapter reports availability honestly; an absent
optional lane never blocks the deterministic run. The stub engine is SYNTHETIC, for unit tests only.

Every process starts through the run's Launcher (lcnc.airgap): the host owns the process boundary.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
HOME_DATA = Path.home() / ".local/share/TrikeShed"
STANZA_DIR = REPO / "utils/stanza"
STANZA_PY = Path(os.environ.get("TRIKESHED_STANZA_PYTHON", HOME_DATA / "stanza/venv/bin/python"))
SUMO_FULL = REPO / "build/generated/sumo-full/resources/sumo/full"
WN_NOUN = REPO / "build/generated/sumo/resources/sumo/WordNetMappings/WordNetMappings30-noun.txt"
Q4BERT_DIR = REPO / "utils/q4bert"
Q4BERT_PY = Path(os.environ.get("TRIKESHED_Q4BERT_PYTHON", HOME_DATA / "bert/bin/python"))
Q4BERT_BUNDLE = Path(os.environ.get("TRIKESHED_Q4BERT_BUNDLE", HOME_DATA / "q4bert/legal-bert-base-uncased-q4g32"))
Q4BERT_FILES = ("model.safetensors", "config.json", "vocab.txt", "provenance.json", "SHA256SUMS")
JEVA_KEXE = REPO / "build/bin/macos/jevaWorkerDebugExecutable/jevaWorker.kexe"
JEVA_BUNDLE = Path(os.environ.get("TRIKESHED_JEVA_MODEL", Path.home() / "work/Jeva/models/jeva-4b-mlx-q4g32-dist"))


class EngineError(RuntimeError):
    pass


def file_sha(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _sumo_env() -> dict:
    return {"TRIKESHED_SUMO_FULL": str(SUMO_FULL), "TRIKESHED_WN_NOUN": str(WN_NOUN)}


def _read_jsonl(path: Path) -> list:
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


class StanzaEngine:
    """Deterministic UD frames + SUMO typing. CPU, offline after setup, no generative model."""

    name = "stanza-legalframes"
    fragment = {"lane": "deterministic", "processors": "tokenize,mwt,pos,lemma,depparse,ner",
                "typing": "SUMO+WordNet30", "generative": False}

    def available(self):
        if not STANZA_PY.exists():
            return False, "stanza venv missing (run utils/stanza/setup.sh)"
        if not SUMO_FULL.is_dir() or not WN_NOUN.exists():
            return False, "SUMO corpus missing (./gradlew fetchSumoCorpus fetchSumoFullCorpus)"
        return True, None

    def run(self, manifest: Path, out_dir: Path, launcher) -> dict:
        ok, why = self.available()
        if not ok:
            raise EngineError(why)
        code, out, err = launcher.run(self.name, STANZA_PY, STANZA_DIR / "run.py", [manifest, out_dir, "--cpu"],
                                      cwd=STANZA_DIR, extra_env=_sumo_env())
        if code != 0:
            raise EngineError("stanza run failed: " + err[-800:])
        out_dir = Path(out_dir)
        return {"frames": _read_jsonl(out_dir / "frames.jsonl"),
                "gaps": json.loads((out_dir / "gaps.json").read_text(encoding="utf-8")),
                "summary": json.loads((out_dir / "summary.json").read_text(encoding="utf-8")),
                "log": out.strip()}

    @staticmethod
    def identity(summary: dict) -> str:
        models = summary.get("parser", {}).get("models", {})
        hashes = sorted(m["sha256"] for ms in models.values() for m in ms)
        return "stanza:" + summary.get("parser", {}).get("version", "?") + ":" + \
               ",".join(h[:12] for h in hashes)


class OperatorEngine:
    """Operator logic from parse-tree constructions, with a deterministic and an ontological weight.

    Reads the parse the frames lane cached (no parser is loaded, nothing is re-parsed) and the pinned SUMO
    corpus. This step loads no model; its input is the parser's tree. The same inputs give the same bytes."""

    name = "stanza-operators"
    sources = ("operators.py", "legalframes/operators.py")
    fragment = {"lane": "deterministic", "reads": "the frames lane's cached parse and its SUMO typing",
                "constructions": "subordinator+clause, causal verb+oblique, fixed preposition, modal aux, "
                                 "norm predicate, negator, coordinator+conjunct",
                "deterministicWeight": "base x resolved/required roles; base 0.9 lexical, 0.8 explicit, 0.5 ambiguous",
                "ontologicalWeight": "SUMO checks entailed/total on the first mapped sense; anySense as the bound",
                "models": 0, "generative": False}

    def available(self):
        ok, why = StanzaEngine().available()
        if not ok:
            return ok, why
        if not all((STANZA_DIR / s).exists() for s in self.sources):
            return False, "utils/stanza operator lane missing"
        return True, None

    def run(self, manifest: Path, out_dir: Path, launcher) -> dict:
        ok, why = self.available()
        if not ok:
            raise EngineError(why)
        code, out, err = launcher.run(self.name, STANZA_PY, STANZA_DIR / "operators.py", [manifest, out_dir],
                                      cwd=STANZA_DIR, extra_env=_sumo_env())
        if code != 0:
            raise EngineError("operator lane failed: " + (err or out)[-800:])
        out_dir = Path(out_dir)
        raw = (out_dir / "operators.jsonl").read_bytes()
        return {"raw": raw, "operators": [json.loads(line) for line in raw.decode("utf-8").splitlines() if line],
                "logic": _read_jsonl(out_dir / "logic.jsonl"), "masks": _read_jsonl(out_dir / "masks.jsonl"),
                "summary": json.loads((out_dir / "operators.summary.json").read_text(encoding="utf-8")),
                "log": out.strip()}

    def identity(self, summary: dict) -> str:
        code = hashlib.sha256(b"".join((STANZA_DIR / s).read_bytes() for s in self.sources)).hexdigest()
        return "%s:%s" % (summary.get("schema", "operators"), code[:12])


class Q4BertEngine:
    """A 4-bit BERT encoder with its masked-language-model head, as a local worker.

    It answers one question about one position: which words does the context put where this cue stands.
    The reply is the cue's own probability and rank and the probability mass of each cue class. It writes
    no text (output tokens are always 0) and decides nothing; over-limit input is refused, never cut.
    Over-limit and malformed items come back refused and the worker keeps serving."""

    name = "q4bert-masked-cue"
    fragment = {"lane": "optional-model", "kind": "neural encoder (BERT), masked-language-model head",
                "question": "which word does the context put where this cue stands",
                "output": "probability and rank of the cue word; probability mass per cue class",
                "quantization": "packed 4-bit group-32 linear weights, 8-bit group-64 embeddings (MLX affine layout)",
                "generative": False, "autoregressive": False, "causalLanguageModel": False}

    def __init__(self, bundle: Path = Q4BERT_BUNDLE, python: Path = Q4BERT_PY, max_tokens: int = 512,
                 threads: int = 4):
        self.bundle, self.python = Path(bundle), Path(python)
        self.max_tokens, self.threads = max_tokens, threads
        self.proc = None
        self.launcher = None
        self.identity = None
        self.init = None

    def available(self):
        if not self.python.exists():
            return False, "q4bert interpreter missing at %s (needs numpy and torch)" % self.python
        if not (Q4BERT_DIR / "worker.py").exists():
            return False, "utils/q4bert worker missing"
        missing = [n for n in Q4BERT_FILES if not (self.bundle / n).exists()]
        if missing:
            return False, "q4 BERT bundle incomplete at %s: missing %s (utils/q4bert/quantize.py makes it; " \
                          "nothing is downloaded at run time)" % (self.bundle, ", ".join(missing))
        return True, None

    def pins(self) -> dict:
        """What the adapter is pinned to, read from the bundle's own records without loading weights."""
        provenance = json.loads((self.bundle / "provenance.json").read_text(encoding="utf-8"))
        config = json.loads((self.bundle / "config.json").read_text(encoding="utf-8"))
        sums = {}
        with open(self.bundle / "SHA256SUMS", encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    digest, name = line.split()
                    sums[name] = digest
        return {"checkpoint": {"name": provenance["name"], "snapshot": provenance["source"]["snapshot"],
                               "float32Sha256": provenance["source"]["files"]["model.safetensors"]},
                "weightsSha256": sums["model.safetensors"], "tokenizerSha256": sums["vocab.txt"],
                "maxSequenceTokens": self.max_tokens,
                "head": "masked-language-model head at the masked position; softmax over the vocabulary",
                "quantization": provenance["quantization"],
                "storedBitsPerWeight": provenance["storedBitsPerWeight"],
                "weightError": provenance["relativeFrobenius"],
                "normalization": "lower-case, accents stripped" if config["q4bert"]["lower_case"] else "cased"}

    def _send(self, payload: dict) -> dict:
        proc = self.proc
        if proc is None or proc.poll() is not None:
            raise EngineError("q4bert worker is not running")
        assert proc.stdin is not None and proc.stdout is not None
        proc.stdin.write(json.dumps(payload) + "\n")
        proc.stdin.flush()
        line = proc.stdout.readline()
        if not line:
            stderr = proc.stderr.read()[-800:] if proc.stderr else ""
            raise EngineError("q4bert worker closed its output: " + stderr)
        reply = json.loads(line)
        if reply.get("error"):
            raise EngineError(reply["error"])
        return reply

    def start(self, launcher) -> dict:
        ok, why = self.available()
        if not ok:
            raise EngineError(why)
        self.launcher = launcher
        self.proc = launcher.popen(self.name, self.python, Q4BERT_DIR / "worker.py", cwd=Q4BERT_DIR)
        reply = self._send({"op": "init", "bundle": str(self.bundle), "max_tokens": self.max_tokens,
                            "threads": self.threads})
        if not (reply.get("available") and reply.get("loaded")) or reply.get("model", {}).get("generative") is not False:
            raise EngineError("q4bert worker init inconsistent: %s" % reply)
        self.identity, self.init = reply["identity"], reply
        return reply

    def fill(self, items: list, classes: dict, k: int = 5, request_id=None) -> dict:
        """items: [{"key", "text", "begin", "end"}]; begin/end are the cue's code-point span inside text."""
        return self._send({"op": "fill", "id": request_id, "classes": classes, "k": k, "items": items})

    def close(self):
        proc = self.proc
        if proc is not None:
            if proc.poll() is None and proc.stdin:
                proc.stdin.close()
            try:
                proc.wait(timeout=30)
            except Exception:       # a worker that will not end is killed, and the inventory says so
                proc.kill()
                proc.wait()
            for stream in (proc.stdout, proc.stderr):
                if stream:
                    stream.close()
            if self.launcher is not None:
                self.launcher.finished(proc)
        self.proc = None


class JevaWorkerEngine:
    """Opt-in (--model jeva), outside the default profile: the native MLX lane, a packed Q4 causal
    language-model backbone with a float32 decision head.

    Scorer, not generator: candidate_probabilities + null_probability, output_tokens always 0. tokenmax
    caps are enforced by the worker; over-cap input is a REFUSAL (the worker exits after writing the error).
    """

    name = "jeva-mlx-worker"
    fragment = {"lane": "optional-model", "backend": "mlx-q4g32-dist", "head": "float32-cpu",
                "decisions": ["noul", "choice"], "generative": False, "autoregressive": False,
                "causalLanguageModel": True}

    def __init__(self, bundle: Path = JEVA_BUNDLE, kexe: Path = JEVA_KEXE,
                 max_tokens: int = 2048, max_state_length: int = 9000,
                 max_option_length: int = 8192, timeout_ms: int = 600_000):
        self.bundle, self.kexe = Path(bundle), Path(kexe)
        self.caps = {"mlx_max_tokens": max_tokens, "max_state_length": max_state_length,
                     "max_option_length": max_option_length, "timeout_ms": timeout_ms}
        self.proc = None
        self.launcher = None
        self.identity = None
        self.dead = False

    def available(self):
        if not self.kexe.exists():
            return False, "native jeva worker binary missing (gradle jevaWorker executable)"
        if not (self.bundle / "config.yaml").exists() or not (self.bundle / "head.pt").exists():
            return False, "q4 bundle missing at %s" % self.bundle
        return True, None

    def _send(self, payload: dict) -> dict:
        proc = self.proc
        if self.dead or proc is None or proc.poll() is not None:
            raise EngineError("jeva worker is not running (it exits after any error)")
        assert proc.stdin is not None and proc.stdout is not None
        proc.stdin.write(json.dumps(payload) + "\n")
        proc.stdin.flush()
        line = proc.stdout.readline()
        if not line:
            self.dead = True
            stderr = proc.stderr.read()[-500:] if proc.stderr else ""
            raise EngineError("jeva worker closed stdout: " + stderr)
        reply = json.loads(line)
        if reply.get("error"):  # null/absent error is success (worker status carries error:null)
            self.dead = True  # protocol: the worker exits after an error response
            raise EngineError(reply["error"])
        return reply

    def start(self, launcher):
        ok, why = self.available()
        if not ok:
            raise EngineError(why)
        self.launcher = launcher
        self.proc = launcher.popen_native(self.name, [self.kexe])
        self.dead = False
        reply = self._send({"op": "init", "config": {
            "model": str(self.bundle), "device": "mlx", "revision": None,
            "max_state_length": self.caps["max_state_length"],
            "max_option_length": self.caps["max_option_length"],
            "timeout_ms": self.caps["timeout_ms"],
            "mlx_max_tokens": self.caps["mlx_max_tokens"],
            "mlx_checkpoint_limit_bytes": None, "mlx_max_footprint_bytes": None}})
        if not (reply.get("available") and reply.get("loaded") and reply.get("runtime") == "kotlin"
                and reply.get("device") == "mlx"):
            self.dead = True
            raise EngineError("jeva worker init inconsistent: %s" % reply)
        self.identity = reply["model_identity"]
        return reply

    def infer(self, state: str, questions: dict) -> dict:
        """questions: {id: {"decision_type","question","options","allow_null"}} (noul/choice only)."""
        for q in questions.values():
            if q["decision_type"] not in ("noul", "choice"):
                raise EngineError("Kotlin ingest maps noul and choice only")
            if q["decision_type"] == "noul" and (q["options"] != ["false", "true"] or q["allow_null"]):
                raise EngineError("noul requires options [false,true] and allow_null=false")
        rows = {k: {**v, "positive_index": 1} for k, v in questions.items()}
        return self._send({"op": "infer", "state": state, "questions": rows})

    def close(self):
        proc = self.proc
        if proc is not None:
            if proc.poll() is None and proc.stdin:
                proc.stdin.close()
            try:
                proc.wait(timeout=10)
            except Exception:
                proc.kill()
                proc.wait()
            for stream in (proc.stdout, proc.stderr):
                if stream:
                    stream.close()
            if self.launcher is not None:
                self.launcher.finished(proc)
        self.proc = None


class ConditionalEvaluator:
    """Deterministic CPU middle operation for the evidence-state use case.

    Distinguishes missing / negative / conflict (I05). Premises are scoped to an explicit context (I03):
    an evaluation sees the asserted premises and those of its own context, so a hypothetical premise
    changes only its own scenario even when it sits in the same premise list.
    """

    name = "conditional-evaluator"
    fragment = {"lane": "deterministic", "logic": "propositional-conditional", "generative": False}
    identity = "conditional-evaluator:v2"
    contexts = ("asserted", "hypothetical", "fictional", "derived")

    def available(self):
        return True, None

    @classmethod
    def evaluate(cls, clause: dict, premises: list, context: str = "asserted") -> dict:
        """clause: {"antecedent": p, "consequent": q}. premises: [{"term","polarity","context","source"}]."""
        if context not in cls.contexts:
            raise ValueError("evaluation requires an explicit context")
        ant = clause["antecedent"]
        visible = [p for p in premises if p["term"] == ant and p.get("context", "asserted") in ("asserted", context)]
        pos = [p for p in visible if p["polarity"] is True]
        neg = [p for p in visible if p["polarity"] is False]
        if pos and neg:
            return {"status": "conflict", "consequent": None, "context": context,
                    "support": {"affirmed": pos, "denied": neg},
                    "note": "conflicting support; host action is a separate authority check"}
        if pos:
            return {"status": "eligible", "consequent": clause["consequent"], "context": context,
                    "support": {"affirmed": pos}}
        if neg:
            return {"status": "ineligible", "consequent": None, "context": context, "support": {"denied": neg},
                    "note": "antecedent denied; this is NOT a claim the consequent is universally false"}
        return {"status": "pending", "consequent": None, "context": context, "support": {},
                "note": "antecedent unknown: missing evidence is not a negative premise"}


class StubEngine:
    """SYNTHETIC fixture engine for unit tests and renderer load. Never stands in
    for real parser/model integration evidence (charter: synthetic != measured)."""

    name = "stub-synthetic"
    fragment = {"lane": "synthetic", "generative": False}
    identity = "stub-synthetic:v1"

    def __init__(self, answers=None):
        self.answers = answers or {}

    def available(self):
        return True, None

    def infer(self, state: str, questions: dict) -> dict:
        out = {}
        for qid in questions:
            out[qid] = self.answers.get(qid, {"kind": "noul", "candidate_probabilities": [0.5, 0.5],
                                              "null_probability": 0.0, "noul": 0.5})
        return {"model": self.identity, "answers": out,
                "usage": {"input_tokens": 0, "output_tokens": 0, "truncated_tokens": 0,
                          "synthetic": True}}
