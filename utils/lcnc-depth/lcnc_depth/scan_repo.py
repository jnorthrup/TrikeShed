"""`python -m lcnc_depth.scan_repo <src-root>` — the static half, no LLM required.

Everything the RLM's stage 1 derives WITHOUT comprehension is computable here:
context demands and their severity, supervision boundaries and their mechanism,
the contract vocabulary, and cast hazards. What is deliberately NOT here is the
part that genuinely needs a model — naming the Kotlin type a port carries, and
attributing a demand to the node types that can reach it.

Run it as a gate (`--fail-on-suspicious`) or as a report. Stdlib only, so it
works in CI with nothing installed.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

from .modules import kotlin_scan as ks

# Elements the CCEK assembly scope is known to provide. A `throws`-severity
# demand for anything outside this set is unsatisfiable under that scope.
CCEK_ASSEMBLY_PROVIDES = {"MuxReactorElement.Key", "LcncScopeFrame", "Job"}

# The plane's package. Files elsewhere that happen to sit in a `ccek/` directory
# (lcnc/ccek/LcncCcekAssembly.kt) are the LCNC side of the assembly, not the plane.
CCEK_PACKAGE = "borg.trikeshed.ccek"

# ── CCEK decomposition rulings ───────────────────────────────────────────
# The scanner reports a FACT per public member of the CCEK package: reached by an
# LCNC runner file, unreached, or orphan (no file outside its own imports it).
# Whether an unreached member SHOULD be a lego is a ruling, and rulings are
# declared here with their reason so they can be argued with. A member that is
# unreached and unruled is a GAP — a capability a program cannot reach.
#
# Keyed by qualified name, then by owner type, then by file name.
CCEK_RULINGS: dict[str, str] = {
    # the daemon composes these once at boot; a program rides the bound reactor
    "CCEK.initialize": "substrate — OroborosDaemon calls CCEK.initialize(muxReactor) once; programs ride the binding via CcekSeams.live(binding)",
    "CCEK.inputChannel": "substrate — channel factory used inside ArticulatedNode",
    "CCEK.fanOutChannel": "substrate — channel factory used inside ArticulatedNode",
    "CCEK.childScope": "substrate — per-block child scope, owned by ArticulatedNode.registerChildScopes",
    "CcekReactorBinding.reactor": "substrate — the reactor element the binding was made from",
    # the same capability under another name
    "ArticulatedNode.signalIn": "same capability — sendSignal writes this channel; ccek.signal is the lego",
    "ArticulatedNode.start": "not a lego — init() and sendSignal() call it; a drained node's signalIn is closed for good, so start() cannot revive it (CCEK.kt cancel/start). Exposing it would promise a restart the engine does not perform",
    "ArticulatedNode.stop": "alias — fun stop() = cancel(); ccek.drain is the lego",
    "ForgeDocNode": "wrapper — every member delegates to ArticulatedNode; ccek.incarnate is the lego",
    "coroutineService": "substrate — generic keyed lookup; ccek.validate reports which keys the runner's context carries",
    "KeyedService": "substrate — marker interface for context elements",
    "CcekKeyService": "carried — a context element ccek.validate can require by name",
    "requireCcekScope": "lego — ccek.validate",
    "CcekScopeValidation": "carried — ccek.validate's outputs",
    "UserContext.parentId": "carried — ccek.lineage / ccek.context document field",
    "UserContext.active": "carried — ccek.activate / ccek.lineage output",
    "UserContext.reteTable": "lego — ccek.query (CausalReteTable.query / containsFact)",
    "CausalReteTable": "lego — ccek.query",
    "PolyglotFact": "lego — ccek.polyglot.load / ccek.polyglot.query",
    "TableTestResult": "carried — ccek.table.test outputs",
    "GraphicalFlow": "lego — ccek.flow",
    "GraphicalBlock": "carried — ccek.flow input rows",
    "GraphicalEdge": "carried — ccek.flow input rows",
    "GraphicalCursor": "carried — ccek.flow output",
    "SpreadsheetVeneer": "lego — ccek.veneer",
    "MetaLcncParadigm": "lego — ccek.paradigm",
    "LcncRule": "carried — ccek.paradigm input rows",
    "AdaptedParadigm": "carried — ccek.paradigm output",
    "ForgeProjection": "carried — ccek.projection reads the typed replay caches; ForgeProjection.Error surfaces as a Failed status event",
    "AgentStatusEvent": "carried — ccek.status renders every case (statusMap)",
    "CausalAssertion": "carried — ccek.fact / ccek.query rows",
    "VoteBallot": "carried — panel.vote input rows (PanelVoteNode)",
    "VoteTally": "carried — panel.vote output",
    "VoteResult": "carried — panel.vote output",
    # vocabulary ahead of implementation (doc/ccek-consistency-pass.md §4)
    "Seat.kt": "orphan vocabulary — Seat/MuxVenn have no caller outside their file; council seats run through CouncilNodes, not this type",
    "SupervisorJob.kt": "orphan vocabulary — an interface set that shadows kotlinx SupervisorJob; RealSupervisorJob's one caller is graal/subvm/GuestModules.kt",
}
# Sealed-case constructors and their fields are constructed/rendered by the verb
# node; the class is what a program reaches, the fields ride inside it.
CCEK_CARRIED_OWNERS = {
    "AppendBlock", "UpdateText", "DeleteBlock", "MoveCard", "Continue", "Repeat",
    "Abort", "Fork", "Join", "Vote", "Started", "Completed", "Failed",
    "DocumentChanged", "BoardChanged", "MarkdownChanged", "Error",
}


def ruling_for(row: dict) -> str | None:
    q = row["qualified"]
    if q in CCEK_RULINGS:
        return CCEK_RULINGS[q]
    for key in (row.get("owner"), row.get("root"), row["member"]):
        if key and key in CCEK_RULINGS:
            return CCEK_RULINGS[key]
    if row.get("owner") in CCEK_CARRIED_OWNERS or row.get("root") in CCEK_CARRIED_OWNERS:
        return "carried — a sealed case the verb node constructs or renders"
    name = Path(row["path"]).name
    return CCEK_RULINGS.get(name)


def _iter_sources(root: Path, include_tests: bool):
    for p in sorted(root.rglob("*.kt")):
        if not include_tests and "Test" in p.name:
            continue
        yield p


def ccek_decomposition(root: Path, texts: dict[str, str]) -> dict:
    """The CCEK package, member by member, against the LCNC runner files.

    Surface = every public member declared in the `borg.trikeshed.ccek` package
    (by its `package` line, not its directory — `lcnc/ccek/` is the LCNC side of
    the assembly, not the plane). Seams = every file under an `lcnc/` directory
    (the runners are the only way a program touches the plane). The whole tree
    decides orphan-hood.
    """
    surface: list[dict] = []
    for rel, text in sorted(texts.items()):
        if ks._package_of(text) == CCEK_PACKAGE:
            surface += ks.ccek_surface(text, rel)
    seams = {rel: t for rel, t in texts.items() if "/lcnc/" in "/" + rel.replace("\\", "/")}
    rows = ks.ccek_coverage(surface, seams, texts)
    for r in rows:
        r["ruling"] = ruling_for(r)
        r["gap"] = r["status"] in ("unreached", "orphan") and r["ruling"] is None
    return {
        "surface": rows,
        "reached": [r for r in rows if r["status"] == "reached"],
        "unreached": [r for r in rows if r["status"] == "unreached"],
        "orphan": [r for r in rows if r["status"] == "orphan"],
        "gaps": [r for r in rows if r["gap"]],
    }


def analyse(root: Path, include_tests: bool = False) -> dict:
    demands: list[dict] = []
    supervision: list[dict] = []
    contracts: list[dict] = []
    cast_rows: list[dict] = []
    texts: dict[str, str] = {}

    for p in _iter_sources(root, include_tests):
        try:
            text = p.read_text(errors="replace")
        except OSError:
            continue
        rel = str(p.relative_to(root))
        texts[rel] = text
        demands += ks.context_demands(text, rel)
        supervision += ks.supervision(text, rel)
        cast_rows += ks.casts(text, rel)
        if "LcncPortContract(" in text:
            contracts += ks.contracts(text, rel)

    hard = [d for d in demands if d["severity"] == "throws"]
    unsatisfiable = [d for d in hard if d["element_key"] not in CCEK_ASSEMBLY_PROVIDES]
    suspicious = [s for s in supervision if s.get("suspicious")]

    kind_freq: Counter[str] = Counter()
    for c in contracts:
        kind_freq.update(c["inputKinds"].values())
        kind_freq.update(c["outputKinds"].values())

    silent = [c for c in cast_rows if c["on_cast_failure"] in ("silent-empty", "silent-null")]

    return {
        "root": str(root),
        "contracts": contracts,
        "kind_frequency": dict(kind_freq.most_common()),
        "context_demands": demands,
        "hard_demands": hard,
        "unsatisfiable_under_ccek_assembly": unsatisfiable,
        "supervision": supervision,
        "suspicious_supervision": suspicious,
        "casts": cast_rows,
        "silent_cast_failures": silent,
        "ccek": ccek_decomposition(root, texts),
    }


def _report(a: dict) -> None:
    w = sys.stdout.write
    w(f"\nlcnc-depth static scan · {a['root']}\n")
    w("=" * 72 + "\n")

    # ── vocabulary ──
    kinds = a["kind_frequency"]
    total = sum(kinds.values())
    w(f"\nVOCABULARY  {len(a['contracts'])} contracts, {total} kind declarations\n")
    for k, n in kinds.items():
        share = 100 * n / total if total else 0
        w(f"    {k:10} {n:4}  {share:5.1f}%\n")
    if kinds:
        top, n = next(iter(kinds.items()))
        if total and n / total > 0.5:
            w(
                f"    → '{top}' carries {100 * n / total:.0f}% of all declarations, so it\n"
                f"      distinguishes almost nothing. Kinds constrain which cables may be\n"
                f"      DRAWN; the executor never reads one.\n"
            )

    # ── context ──
    by_key: dict[str, list[dict]] = defaultdict(list)
    for d in a["hard_demands"]:
        by_key[d["element_key"]].append(d)
    w(f"\nCONTEXT DEMANDS  {len(a['hard_demands'])} hard (node cannot run if absent)\n")
    for key in sorted(by_key, key=lambda k: -len(by_key[k])):
        ok = key in CCEK_ASSEMBLY_PROVIDES
        mark = "provided" if ok else "NOT PROVIDED by the CCEK assembly scope"
        w(f"    {key:26} {len(by_key[key]):2} sites   [{mark}]\n")
        if not ok:
            for d in sorted(by_key[key], key=lambda d: d["path"]):
                w(f"        {d['path']}:{d['line']}\n")
                if d["error_message"]:
                    w(f'            "{d["error_message"]}"\n')

    # ── supervision ──
    by_pos: Counter[str] = Counter(
        s["position"] for s in a["supervision"] if s["mechanism"] == "detached-root"
    )
    w(f"\nSUPERVISION  {len(a['suspicious_supervision'])} suspicious parentless SupervisorJob()\n")
    for pos, n in by_pos.most_common():
        w(f"    {pos:22} {n:3}\n")
    for s in sorted(a["suspicious_supervision"], key=lambda s: (s["path"], s["line"])):
        w(f"        {s['path']}:{s['line']}  ({s['position']})\n")
    catch = [s for s in a["supervision"] if s["mechanism"] == "try-catch"]
    w(f"    {len(catch)} catch-based isolation sites — conventional, not structural\n")

    # ── casts ──
    modes = Counter(c["on_cast_failure"] for c in a["casts"])
    w(f"\nCAST HAZARDS  {len(a['casts'])} cast sites\n")
    for mode, n in modes.most_common():
        w(f"    {mode:16} {n:4}\n")
    w(
        f"    → {len(a['silent_cast_failures'])} degrade SILENTLY. A kind-legal wire that\n"
        f"      delivers the wrong Kotlin type takes one of these branches and\n"
        f"      produces a plausible-looking empty answer, with no error anywhere.\n"
    )

    # ── CCEK decomposition ──
    c = a["ccek"]
    owners = sorted({r["root"] or r["member"] for r in c["surface"]})
    w(
        f"\nCCEK DECOMPOSITION  {len(c['surface'])} public members across {len(owners)} types; "
        f"{len(c['reached'])} reached by an LCNC runner, {len(c['unreached'])} unreached, "
        f"{len(c['orphan'])} orphan\n"
    )
    by_root: dict[str, list[dict]] = defaultdict(list)
    for r in c["surface"]:
        by_root[r["root"] or r["member"]].append(r)
    for root_name in sorted(by_root, key=lambda k: (-sum(1 for r in by_root[k] if r["status"] != "reached"), k)):
        rows = by_root[root_name]
        reached = sum(1 for r in rows if r["status"] == "reached")
        if reached == len(rows):
            continue
        w(f"    {root_name:26} {reached:2}/{len(rows):<2} reached\n")
        for r in rows:
            if r["status"] == "reached":
                continue
            tag = "GAP" if r["gap"] else r["status"]
            w(f"        {tag:9} {r['kind']:9} {r['qualified']:40} {r['path']}:{r['line']}\n")
            if r["ruling"]:
                w(f"            {r['ruling']}\n")
    w(
        f"    → {len(c['gaps'])} GAP(s): public capability with no LCNC lego and no ruling.\n"
        f"      A ruling lives in CCEK_RULINGS with its reason; a lego is a contract + runner.\n"
    )
    w("\n")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="lcnc-depth-scan", description=__doc__)
    ap.add_argument("root", type=Path, help="source root, e.g. TrikeShed/src")
    ap.add_argument("--json", action="store_true", help="emit raw findings as JSON")
    ap.add_argument("--include-tests", action="store_true")
    ap.add_argument(
        "--fail-on-suspicious",
        action="store_true",
        help="exit non-zero when a suspicious supervision site or an unsatisfiable "
        "context demand is found — for use as a CI gate",
    )
    ap.add_argument(
        "--fail-on-ccek-gap",
        action="store_true",
        help="exit non-zero when a public CCEK member is neither reached by an LCNC "
        "runner nor covered by a ruling in CCEK_RULINGS",
    )
    args = ap.parse_args(argv)

    if not args.root.is_dir():
        print(f"not a directory: {args.root}", file=sys.stderr)
        return 2

    a = analyse(args.root, args.include_tests)
    if args.json:
        json.dump(a, sys.stdout, indent=2)
        sys.stdout.write("\n")
    else:
        _report(a)

    bad = 0
    if args.fail_on_suspicious:
        bad += len(a["suspicious_supervision"]) + len(a["unsatisfiable_under_ccek_assembly"])
    if args.fail_on_ccek_gap:
        bad += len(a["ccek"]["gaps"])
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
