"""trikeshed_rlm_scan.py — agentic scan loop for libs/ policing.

Architecture:
  Python helpers  = structural grep, declaration extraction, signal counting
  Hermes/GEPA     = expensive LLM refinement, only on manual trigger

Usage (from Hermes or CLI):
  python3 scripts/trikeshed_rlm_scan.py scan           --libs-dir libs/ [--root-dir .]
  python3 scripts/trikeshed_rlm_scan.py scan-module    --module-path libs/common [--root-dir .]
  python3 scripts/trikeshed_rlm_scan.py refine         --module-path libs/common [--root-dir .] [--hermes-command /path/to/hermes]
  python3 scripts/trikeshed_rlm_scan.py refine-apply   --module-path libs/common [--root-dir .]
  python3 scripts/trikeshed_rlm_scan.py gepa-optimize  --root-dir .
  python3 scripts/trikeshed_rlm_scan.py gepa-apply     --root-dir .
  python3 scripts/trikeshed_rlm_scan.py status         --root-dir .

All commands are manual-only. Nothing auto-runs.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

# Re-use the existing helper
sys.path.insert(0, str(Path(__file__).resolve().parent))
from trikeshed_rlm_gradle import (
    DEFAULT_FORBIDDEN_PATTERNS,
    all_trace_modules,
    append_trace,
    build_delegate_packet,
    build_gepa_project,
    build_module_report,
    kotlin_files,
    lint_module,
    load_rules,
    load_traces,
    trace_dir,
)


def _write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n")


def _read_json(path: Path) -> Any:
    return json.loads(path.read_text())


def _output_dir(root: Path) -> Path:
    return root / "build" / "trikeshed-rlm"


# ---------------------------------------------------------------------------
# scan: batch scan all libs, emit briefs + lints + traces
# ---------------------------------------------------------------------------
def cmd_scan(root: Path, libs_dir: str) -> dict[str, Any]:
    libs = Path(libs_dir)
    if not libs.is_absolute():
        libs = root / libs
    if not libs.exists():
        return {"error": f"libs dir not found: {libs}"}

    out = _output_dir(root)
    results: list[dict[str, Any]] = []
    for child in sorted(libs.iterdir()):
        if not child.is_dir():
            continue
        if child.name.startswith(".") or child.name == "build":
            continue
        # check for kotlin sources
        kt = kotlin_files(child)
        if not kt:
            continue
        result = _scan_single(child, root, out)
        results.append(result)

    summary = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "libs_scanned": len(results),
        "modules_with_violations": sum(1 for r in results if not r.get("passed", True)),
        "total_violations": sum(r.get("violation_count", 0) for r in results),
        "results": results,
    }
    _write_json(out / "scan-results.json", summary)
    return summary


def _scan_single(module_path: Path, root: Path, out: Path) -> dict[str, Any]:
    name = module_path.name
    brief = build_module_report(module_path)
    rules = load_rules(None, module_name=name)
    lint = lint_module(brief, rules)
    # accumulate trace
    append_trace(str(root), name, rules, lint)
    # emit per-module artifacts
    mod_out = out / name
    _write_json(mod_out / "module-brief.json", brief)
    _write_json(mod_out / "violations.json", lint)
    packet = build_delegate_packet(brief, lint)
    _write_json(mod_out / "delegate-packet.json", packet)
    return {
        "module_name": name,
        "passed": lint.get("passed", True),
        "violation_count": len(lint.get("violations", [])),
        "intent_hint": brief.get("intent_hint"),
        "delegate_task_count": len(packet.get("actor_tasks", [])),
    }


# ---------------------------------------------------------------------------
# scan-module: single module
# ---------------------------------------------------------------------------
def cmd_scan_module(root: Path, module_path: str) -> dict[str, Any]:
    mp = Path(module_path)
    if not mp.is_absolute():
        mp = root / mp
    out = _output_dir(root)
    return _scan_single(mp, root, out)


# ---------------------------------------------------------------------------
# refine: propose narrower rules for one module (optionally via hermes)
# ---------------------------------------------------------------------------
def cmd_refine(root: Path, module_path: str, hermes_command: str | None) -> dict[str, Any]:
    mp = Path(module_path)
    if not mp.is_absolute():
        mp = root / mp
    name = mp.name
    out = _output_dir(root)
    brief_path = out / name / "module-brief.json"
    lint_path = out / name / "violations.json"
    if not brief_path.exists() or not lint_path.exists():
        return {"error": f"Run scan-module first for {name}"}
    brief = _read_json(brief_path)
    lint = _read_json(lint_path)
    rules = load_rules(None, module_name=name)

    proposal_path = out / name / "refinement-proposal.json"

    if hermes_command:
        # expensive path: delegate to hermes for LLM-based refinement
        from trikeshed_rlm_gradle import refine_rules
        proposal = refine_rules(brief, lint, rules, hermes_command=hermes_command)
    else:
        # cheap path: structural narrowing from trace history
        traces = load_traces(str(root), name)
        proposal = _structural_narrowing(name, rules, traces, lint)

    _write_json(proposal_path, proposal)
    return proposal


def _structural_narrowing(name: str, rules: dict, traces: list[dict], lint: dict) -> dict[str, Any]:
    """Token-free narrowing: add patterns for violation kinds that keep recurring."""
    recurring_kinds: dict[str, int] = {}
    for t in traces:
        for kind in t.get("violation_kinds", []):
            recurring_kinds[kind] = recurring_kinds.get(kind, 0) + 1

    new_forbidden = list(rules.get("forbidden_patterns", []))
    for kind, count in recurring_kinds.items():
        if count >= 2 and kind not in new_forbidden:
            new_forbidden.append(kind)

    new_checks = list(rules.get("critic_checks", []))
    for kind in recurring_kinds:
        check = f"Recurring {kind} violations must show declining trend."
        if check not in new_checks:
            new_checks.append(check)

    return {
        "module_name": name,
        "method": "structural_narrowing",
        "trace_count": len(traces),
        "recurring_kinds": recurring_kinds,
        "proposed_rules": {
            "expected_intent": rules.get("expected_intent", "library"),
            "forbidden_patterns": new_forbidden,
            "required_terms": rules.get("required_terms", []),
            "critic_checks": new_checks,
        },
    }


# ---------------------------------------------------------------------------
# refine-apply: apply refined rules for one module
# ---------------------------------------------------------------------------
def cmd_refine_apply(root: Path, module_path: str) -> dict[str, Any]:
    mp = Path(module_path)
    if not mp.is_absolute():
        mp = root / mp
    name = mp.name
    out = _output_dir(root)
    proposal_path = out / name / "refinement-proposal.json"
    if not proposal_path.exists():
        return {"error": f"No refinement proposal for {name}. Run refine first."}
    proposal = _read_json(proposal_path)
    proposed = proposal.get("proposed_rules", proposal)
    rules_dir = root / "gradle" / "trikeshed-rlm" / "libs"
    _write_json(rules_dir / f"{name}.json", proposed)
    return {"applied": str(rules_dir / f"{name}.json"), "rules": proposed}


# ---------------------------------------------------------------------------
# gepa-optimize: build GEPA project from traces, optionally run optimization
# ---------------------------------------------------------------------------
def cmd_gepa_optimize(root: Path) -> dict[str, Any]:
    result = build_gepa_project(str(root))
    return result


# ---------------------------------------------------------------------------
# gepa-apply: apply GEPA-optimized rules back
# ---------------------------------------------------------------------------
def cmd_gepa_apply(root: Path) -> dict[str, Any]:
    gepa_dir = root / "gepa" / "trikeshed-lint-opt"
    optimized = gepa_dir / "optimized_rules.json"
    if not optimized.exists():
        return {"error": "No optimized_rules.json. Run GEPA optimization first."}
    rules = _read_json(optimized)
    rules_dir = root / "gradle" / "trikeshed-rlm" / "libs"
    applied = []
    for name in all_trace_modules(str(root)):
        _write_json(rules_dir / f"{name}.json", rules)
        applied.append(name)
    return {"applied_to": applied, "rules": rules}


# ---------------------------------------------------------------------------
# status: show accumulated traces and scan history
# ---------------------------------------------------------------------------
def cmd_status(root: Path) -> dict[str, Any]:
    modules = all_trace_modules(str(root))
    status = {}
    for name in modules:
        traces = load_traces(str(root), name)
        status[name] = {
            "trace_count": len(traces),
            "latest": traces[-1] if traces else None,
        }
    scan_results = root / "build" / "trikeshed-rlm" / "scan-results.json"
    last_scan = None
    if scan_results.exists():
        last_scan = _read_json(scan_results)
    return {"modules": status, "last_scan": last_scan}


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------
def main() -> None:
    p = argparse.ArgumentParser(description="TrikeShed RLM agentic scan loop. All manual-only.")
    p.add_argument("--root-dir", default=".", help="TrikeShed repo root")
    sub = p.add_subparsers(dest="cmd", required=True)

    s_scan = sub.add_parser("scan")
    s_scan.add_argument("--libs-dir", default="libs")

    s_mod = sub.add_parser("scan-module")
    s_mod.add_argument("--module-path", required=True)

    s_ref = sub.add_parser("refine")
    s_ref.add_argument("--module-path", required=True)
    s_ref.add_argument("--hermes-command")

    s_ra = sub.add_parser("refine-apply")
    s_ra.add_argument("--module-path", required=True)

    sub.add_parser("gepa-optimize")
    sub.add_parser("gepa-apply")
    sub.add_parser("status")

    args = p.parse_args()
    root = Path(args.root_dir).resolve()

    dispatch = {
        "scan": lambda: cmd_scan(root, args.libs_dir),
        "scan-module": lambda: cmd_scan_module(root, args.module_path),
        "refine": lambda: cmd_refine(root, args.module_path, args.hermes_command),
        "refine-apply": lambda: cmd_refine_apply(root, args.module_path),
        "gepa-optimize": lambda: cmd_gepa_optimize(root),
        "gepa-apply": lambda: cmd_gepa_apply(root),
        "status": lambda: cmd_status(root),
    }

    result = dispatch[args.cmd]()
    print(json.dumps(result, indent=2, sort_keys=True, default=str))


if __name__ == "__main__":
    main()
