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


def _iter_sources(root: Path, include_tests: bool):
    for p in sorted(root.rglob("*.kt")):
        if not include_tests and "Test" in p.name:
            continue
        yield p


def analyse(root: Path, include_tests: bool = False) -> dict:
    demands: list[dict] = []
    supervision: list[dict] = []
    contracts: list[dict] = []
    cast_rows: list[dict] = []

    for p in _iter_sources(root, include_tests):
        try:
            text = p.read_text(errors="replace")
        except OSError:
            continue
        rel = str(p.relative_to(root))
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

    if args.fail_on_suspicious:
        bad = len(a["suspicious_supervision"]) + len(a["unsatisfiable_under_ccek_assembly"])
        return 1 if bad else 0
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
