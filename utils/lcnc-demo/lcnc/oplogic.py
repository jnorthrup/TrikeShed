"""Harness-side reading of the operator lane: verify what it claims, attach evidence, write the articulation.

The operator lane (utils/stanza/operators.py) emits candidates. Nothing here changes one: weights are
recomputed from their recorded parts to check them, quotes are compared with the retained text, and
masked-cue evidence from the encoder lane is kept beside an operator, never inside its weights.
"""
from __future__ import annotations

from collections import Counter

BASES = {0.9: "lexical", 0.8: "explicit", 0.5: "ambiguous"}
CAUSAL_FAMILY = ("cause", "reason", "condition", "exception", "purpose", "temporal", "concession")
TRIAGE = ("cue-supported", "cue-contested", "cue-not-one-token", "refused", "no-classed-cue")


def weight_faults(op: dict) -> list:
    """Why an operator's two weights do not follow from the parts recorded with them (empty: they do)."""
    faults = []
    d = op["weights"]["deterministic"]
    if BASES.get(d["base"]) != d["level"]:
        faults.append("base %r is not a level" % d["base"])
    if d["resolved"] != d["required"] - len(d["unresolvedRoles"]) or not 0 < d["required"]:
        faults.append("resolved roles do not add up")
    elif d["value"] != round(d["base"] * d["resolved"] / d["required"], 4):
        faults.append("deterministic value is not base x resolved/required")
    o = op["weights"]["ontological"]
    checks = op["sumo"]["checks"]
    counts = Counter(c["status"] for c in checks)
    other = sum(1 for c in checks if c["status"] != "entailed"
                and (c.get("otherSensesEntailed") or c.get("otherSenseIsProcess")))
    if o["total"] != len(checks) or o["entailed"] != counts["entailed"]:
        faults.append("ontological counts differ from the SUMO checks")
    elif not checks:
        if o["value"] is not None:
            faults.append("ontological value without checks")
    else:
        if o["value"] != round(counts["entailed"] / len(checks), 4):
            faults.append("ontological value is not entailed/total")
        if o["anySense"] != round((counts["entailed"] + other) / len(checks), 4):
            faults.append("anySense is not (entailed + other sense)/total")
        if o["disjoint"] != (counts["disjoint"] > 0):
            faults.append("disjoint flag differs from the checks")
    return faults


def quote_faults(ops: list, text: str, begin: int) -> list:
    """Operators whose cue or an operand is not the retained text at its own offsets."""
    bad = []
    for op in ops:
        for span in [op["cue"]] + op["operands"]:
            if text[span["begin"] - begin:span["end"] - begin] != span["quote"]:
                bad.append(op["id"])
                break
    return bad


def sentence_quote(op: dict, text: str, begin: int) -> str:
    return text[op["sentenceBegin"] - begin:op["sentenceEnd"] - begin]


def find(ops: list, text: str, begin: int, marker: str, cls: str, construction: str) -> dict:
    """The one operator of this class and construction in the sentence containing the marker. Selection is
    by marker, never by position: the first operator of a kind is rarely the clause a name suggests."""
    hits = [op for op in ops if op["class"] == cls and op["construction"] == construction
            and marker in sentence_quote(op, text, begin)]
    if len(hits) != 1:
        raise LookupError("%d operators of %s/%s in the sentence containing %r" % (len(hits), cls, construction, marker))
    return hits[0]


def observed(op: dict) -> dict:
    """The fields a reference expectation can pin."""
    d, o = op["weights"]["deterministic"], op["weights"]["ontological"]
    return {"operator": op["operator"], "cue": op["cue"]["keyText"], "form": op["sumo"]["form"],
            "deterministic": d["value"], "level": d["level"], "unresolvedRoles": d["unresolvedRoles"],
            "ontological": o["value"], "anySense": o["anySense"], "disjoint": o["disjoint"],
            "readingRule": op.get("readingRule"), "unresolved": op["unresolved"],
            "operandQuotes": {x["role"]: x["quote"] for x in op["operands"]}}


def mismatches(op: dict, expected: dict) -> dict:
    """Expected fields the operator does not show. `unresolvedIncludes` asks for one entry of `unresolved`."""
    seen = observed(op)
    out = {}
    for key, want in expected.items():
        if key == "unresolvedIncludes":
            if want not in seen["unresolved"]:
                out[key] = {"expected": want, "observed": seen["unresolved"]}
        elif key == "operandQuotes":
            for role, quote in want.items():
                if seen["operandQuotes"].get(role) != quote:
                    out["operandQuotes." + role] = {"expected": quote, "observed": seen["operandQuotes"].get(role)}
        elif seen.get(key) != want:
            out[key] = {"expected": want, "observed": seen.get(key)}
    return out


def triage(op: dict, evidence) -> str:
    """Where masked-cue evidence leaves an operator. It routes attention; it admits and rejects nothing."""
    if evidence is None:
        return "no-classed-cue"
    if evidence.get("status") != "ok":
        return "refused"
    if evidence.get("p") is None:
        return "cue-not-one-token"
    return "cue-supported" if evidence.get("concordant") else "cue-contested"


def evidence_line(evidence) -> str:
    if evidence is None:
        return "none: the cue word has no class in the cue lexicon"
    if evidence.get("status") != "ok":
        return "refused: %s" % evidence.get("reason")
    best = evidence["preferredClass"]
    mass = evidence["classMass"].get(best)
    if evidence.get("p") is None:
        return "the vocabulary splits the cue; model prefers %s %.3f" % (best, mass)
    verdict = "supported" if evidence["concordant"] else "contested, to review"
    return "p %.3f, rank %d, top %s; model prefers %s %.3f: %s" % (
        evidence["p"], evidence["rank"], "/".join(w for w, _ in evidence["top"][:3]), best, mass, verdict)


def _one_line(s: str) -> str:
    return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")


def _mean(values):
    values = [v for v in values if v is not None]
    return "%.2f" % (sum(values) / len(values)) if values else "  - "


def articulate(title: str, begin: int, end: int, ops: list, logic: list, evidence: dict,
               identities: dict) -> str:
    """The operator logic of the slice as text: per sentence its composed form, then each operator with the
    construction that licensed it, both weights with their parts, the cue evidence and what stays open.
    Quotes are the source's own characters; a line break inside one is written \\n."""
    by_sentence = {}
    for op in ops:
        by_sentence.setdefault(op["sentence"], []).append(op)
    lines = ["Operator logic of: %s" % title,
             "source characters %d-%d; %d sentences, %d operator candidates; nothing here is admitted"
             % (begin, end, len(logic), len(ops)), ""]
    for key in sorted(identities):
        lines.append("%-12s %s" % (key, identities[key]))
    lines += ["",
              "deterministic = base x resolved/required roles of the construction (lexical 0.9, explicit 0.8, ambiguous 0.5)",
              "ontological   = SUMO checks entailed/total on the first mapped sense (any sense: if another mapped sense may stand in)",
              "cue           = what the 4-bit BERT encoder puts at the cue's own masked position; evidence, not a decision",
              "", "%-12s %5s %6s %6s %8s %10s %10s" % ("class", "count", "det", "ont", "any", "supported", "contested")]
    classes = Counter(op["class"] for op in ops)
    for cls in sorted(classes, key=lambda c: (c not in CAUSAL_FAMILY, c)):
        group = [op for op in ops if op["class"] == cls]
        marks = Counter(triage(op, evidence.get(op["id"])) for op in group) if evidence else Counter()
        lines.append("%-12s %5d %6s %6s %8s %10s %10s" % (
            cls, len(group), _mean(op["weights"]["deterministic"]["value"] for op in group),
            _mean(op["weights"]["ontological"]["value"] for op in group),
            _mean(op["weights"]["ontological"]["anySense"] for op in group),
            marks["cue-supported"] if evidence else "-", marks["cue-contested"] if evidence else "-"))
    for row in logic:
        found = by_sentence.get(row["sentence"])
        if not found:
            continue
        lines += ["", "sentence %d  characters %d-%d" % (row["sentence"], row["begin"], row["end"]),
                  "  " + _one_line(row["quote"]), "  form  %s" % row["form"]]
        for op in found:
            d, o = op["weights"]["deterministic"], op["weights"]["ontological"]
            lines.append("  %s  %s  %s" % (op["id"], op["operator"], op["construction"]))
            lines.append("      " + _one_line(op["articulation"]))
            open_roles = "; unresolved role: " + ", ".join(d["unresolvedRoles"]) if d["unresolvedRoles"] else ""
            lines.append("      deterministic %.4g = %s %s x %d/%d roles%s"
                         % (d["value"], d["base"], d["level"], d["resolved"], d["required"], open_roles))
            if o["value"] is None:
                lines.append("      ontological   none: no SUMO check applies")
            else:
                parts = ", ".join("%d %s" % (n, s) for s, n in sorted(o["statuses"].items()))
                lines.append("      ontological   %.4g = %d/%d SUMO checks (%s); any sense %.4g"
                             % (o["value"], o["entailed"], o["total"], parts, o["anySense"]))
            if evidence:
                lines.append("      cue %-9s %s" % (_one_line(repr(op["cue"]["keyText"])), evidence_line(evidence.get(op["id"]))))
            lines.append("      open          " + "; ".join(op["unresolved"]))
    return "\n".join(lines) + "\n"
