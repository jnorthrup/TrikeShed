from __future__ import annotations

import argparse
import json
import os
from datetime import datetime, timezone
from pathlib import Path
import re
import subprocess
from typing import Any

KOTLIN_SUFFIXES = (".kt", ".kts")
DEFAULT_FORBIDDEN_PATTERNS = (
    r"\bMutableList\b",
    r"\bMutableMap\b",
    r"\bMutableSet\b",
    r"\bArrayList\b",
    r"\bHashMap\b",
    r"\bHashSet\b",
    r"\bjava\.util\.(List|Map|Set)\b",
)
DEFAULT_SIGNAL_PATTERNS = {
    "mutable_state": re.compile(r"\bvar\b|\bMutable(List|Map|Set)\b|\bArrayList\b|\bHash(Map|Set)\b"),
    "supervisor_job": re.compile(r"\bSupervisorJob\b"),
    "series_terms": re.compile(r"\bSeries\b|\bMetaSeries\b|\bCursor\b"),
    "io_terms": re.compile(r"\bChannel\b|\bSocket\b|\bFile\b|\bnio\b|\buring\b"),
}
DECLARATION_PATTERN = re.compile(
    r"^\s*(?:data\s+class|class|interface|object|fun)\s+([A-Za-z_][A-Za-z0-9_]*)",
    re.MULTILINE,
)
PACKAGE_PATTERN = re.compile(r"^\s*package\s+([A-Za-z0-9_.]+)", re.MULTILINE)
IMPORT_PATTERN = re.compile(r"^\s*import\s+([A-Za-z0-9_.*]+)", re.MULTILINE)
ENTRYPOINT_PATTERN = re.compile(r"^\s*fun\s+main\s*\(", re.MULTILINE)
KEY_PATTERN = re.compile(r"\b([A-Za-z_][A-Za-z0-9_]*Key)\b")
ELEMENT_PATTERN = re.compile(r"\b([A-Za-z_][A-Za-z0-9_]*Element)\b")


def kotlin_files(module_path: Path) -> list[Path]:
    source_root = module_path / "src"
    if source_root.exists():
        scan_root = source_root
    else:
        scan_root = module_path
    return sorted(
        path
        for path in scan_root.rglob("*")
        if path.is_file()
        and path.suffix in KOTLIN_SUFFIXES
        and "build" not in path.parts
        and ".gradle" not in path.parts
    )


def load_rules(path: str | Path | None, module_name: str) -> dict[str, Any]:
    rules: dict[str, Any] = {
        "module_name": module_name,
        "expected_intent": "library",
        "forbidden_patterns": list(DEFAULT_FORBIDDEN_PATTERNS),
        "required_terms": [],
        "critic_checks": [
            "Mutable state must terminate at explicit edges.",
            "Library modules must not expose executable entrypoints.",
            "Namespace keys should remain stable and collision-resistant.",
        ],
    }
    if path is None:
        return rules
    rules_path = Path(path)
    if not rules_path.exists():
        return rules
    loaded = json.loads(rules_path.read_text())
    if isinstance(loaded, dict):
        rules.update(loaded)
    return rules


def _relative(path: Path, root: Path) -> str:
    return str(path.relative_to(root))


def build_module_report(module_path: str | Path) -> dict[str, Any]:
    root = Path(module_path)
    files = kotlin_files(root)
    packages: set[str] = set()
    imports: set[str] = set()
    declarations: list[dict[str, Any]] = []
    candidate_keys: set[str] = set()
    candidate_elements: set[str] = set()
    entrypoints: list[str] = []
    signal_hits = {name: 0 for name in DEFAULT_SIGNAL_PATTERNS}
    file_observations: list[dict[str, Any]] = []

    for file_path in files:
        text = file_path.read_text()
        file_packages = PACKAGE_PATTERN.findall(text)
        file_imports = IMPORT_PATTERN.findall(text)
        packages.update(file_packages)
        imports.update(file_imports)
        candidate_keys.update(KEY_PATTERN.findall(text))
        candidate_elements.update(ELEMENT_PATTERN.findall(text))

        line_lookup = text.splitlines()
        file_declarations: list[dict[str, Any]] = []
        for match in DECLARATION_PATTERN.finditer(text):
            name = match.group(1)
            line_number = text.count("\n", 0, match.start()) + 1
            declaration = {
                "name": name,
                "line": line_number,
                "path": _relative(file_path, root),
            }
            declarations.append(declaration)
            file_declarations.append(declaration)

        file_entrypoints: list[str] = []
        if ENTRYPOINT_PATTERN.search(text):
            package_prefix = file_packages[0] if file_packages else root.name
            file_entrypoints.append(f"{package_prefix}.main")
            entrypoints.extend(file_entrypoints)

        file_signals: dict[str, int] = {}
        for name, pattern in DEFAULT_SIGNAL_PATTERNS.items():
            hits = len(pattern.findall(text))
            signal_hits[name] += hits
            file_signals[name] = hits

        file_observations.append(
            {
                "path": _relative(file_path, root),
                "packages": file_packages,
                "imports": file_imports,
                "declarations": file_declarations,
                "entrypoints": file_entrypoints,
                "signal_hits": file_signals,
                "content": text,
            }
        )

    return {
        "module_name": root.name,
        "module_path": str(root.resolve()),
        "file_count": len(files),
        "packages": sorted(packages),
        "imports": sorted(imports),
        "declarations": declarations,
        "candidate_keys": sorted(candidate_keys),
        "candidate_elements": sorted(candidate_elements),
        "entrypoints": entrypoints,
        "signal_hits": signal_hits,
        "file_observations": file_observations,
    }


def lint_module(report: dict[str, Any], rules: dict[str, Any]) -> dict[str, Any]:
    violations: list[dict[str, Any]] = []
    expected_intent = rules.get("expected_intent", "library")

    if expected_intent == "library":
        for entrypoint in report.get("entrypoints", []):
            violations.append(
                {
                    "kind": "library_entrypoint",
                    "evidence": entrypoint,
                    "remedy": "Move entrypoint code to an executable module or dedicated app surface.",
                }
            )

    forbidden_patterns = [re.compile(pattern) for pattern in rules.get("forbidden_patterns", [])]
    for observation in report.get("file_observations", []):
        text = observation["content"]
        for pattern in forbidden_patterns:
            for match in pattern.finditer(text):
                violations.append(
                    {
                        "kind": "mutable_stdlib_leak",
                        "evidence": f"{observation['path']}: {match.group(0)}",
                        "remedy": "Replace stdlib mutable carriers with explicit algebraic or boundary-owned structures.",
                    }
                )

    return {
        "module_name": report["module_name"],
        "module_path": report["module_path"],
        "expected_intent": expected_intent,
        "passed": not violations,
        "violations": violations,
        "critic_checks": list(rules.get("critic_checks", [])),
    }


def build_delegate_packet(report: dict[str, Any], lint: dict[str, Any]) -> dict[str, Any]:
    goal = (
        f"Audit library module '{report['module_name']}' using the attached brief and violations, "
        "then propose structural corrections while preserving algebraic intent."
    )
    context = {
        "module_report": report,
        "lint_report": lint,
        "stop_margin": 0.1,
        "mode": "actor_critic",
    }
    return {
        "mode": "actor_critic",
        "stop_margin": 0.1,
        "goal": goal,
        "context": context,
        "toolsets": ["file", "terminal"],
    }


def refine_rules(
    report: dict[str, Any],
    lint: dict[str, Any],
    module_rules: dict[str, Any],
    hermes_command: str | None,
) -> dict[str, Any]:
    proposed_rules = dict(module_rules)
    proposed_rules.setdefault("forbidden_patterns", [])
    proposed_rules.setdefault("required_terms", [])

    if any(violation["kind"] == "library_entrypoint" for violation in lint.get("violations", [])):
        if r"^\s*fun\s+main\s*\(" not in proposed_rules["forbidden_patterns"]:
            proposed_rules["forbidden_patterns"].append(r"^\s*fun\s+main\s*\(")
    if any(violation["kind"] == "mutable_stdlib_leak" for violation in lint.get("violations", [])):
        for token in DEFAULT_FORBIDDEN_PATTERNS:
            if token not in proposed_rules["forbidden_patterns"]:
                proposed_rules["forbidden_patterns"].append(token)
    for key_symbol in report.get("candidate_keys", []):
        if key_symbol not in proposed_rules["required_terms"]:
            proposed_rules["required_terms"].append(key_symbol)

    hermes_prompt = json.dumps(
        {
            "instruction": "Tighten module-specific lint rules only. Return JSON object with forbidden_patterns, required_terms, critic_checks.",
            "module_report": report,
            "lint_report": lint,
            "current_rules": module_rules,
        },
        indent=2,
        sort_keys=True,
    )

    proposal = {
        "mode": "manual",
        "module_name": report["module_name"],
        "proposed_rules": proposed_rules,
        "hermes_prompt": hermes_prompt,
    }
    if not hermes_command:
        return proposal

    try:
        result = subprocess.run(
            [hermes_command, "chat", "-q", hermes_prompt],
            check=True,
            capture_output=True,
            text=True,
        )
    except Exception as exc:
        proposal["mode"] = "manual_fallback"
        proposal["error"] = str(exc)
        return proposal

    proposal["mode"] = "hermes"
    proposal["raw_response"] = result.stdout.strip()
    return proposal


# ---------------------------------------------------------------------------
# Trace accumulation
# ---------------------------------------------------------------------------

TRACE_DIR_NAME = "trikeshed-rlm-traces"


def trace_dir(root_dir: str | Path) -> Path:
    return Path(root_dir) / TRACE_DIR_NAME


def append_trace(root_dir: str | Path, module_name: str, rules: dict[str, Any], lint: dict[str, Any]) -> Path:
    td = trace_dir(root_dir)
    td.mkdir(parents=True, exist_ok=True)
    entry = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "module_name": module_name,
        "rules_snapshot": rules,
        "violation_count": len(lint.get("violations", [])),
        "violation_kinds": sorted({v["kind"] for v in lint.get("violations", [])}),
        "passed": lint.get("passed", False),
    }
    trace_file = td / f"{module_name}.jsonl"
    with open(trace_file, "a") as fh:
        fh.write(json.dumps(entry, sort_keys=True) + "\n")
    return trace_file


def load_traces(root_dir: str | Path, module_name: str) -> list[dict[str, Any]]:
    trace_file = trace_dir(root_dir) / f"{module_name}.jsonl"
    if not trace_file.exists():
        return []
    entries: list[dict[str, Any]] = []
    for line in trace_file.read_text().splitlines():
        line = line.strip()
        if line:
            entries.append(json.loads(line))
    return entries


def all_trace_modules(root_dir: str | Path) -> list[str]:
    td = trace_dir(root_dir)
    if not td.exists():
        return []
    return sorted(p.stem for p in td.glob("*.jsonl"))


# ---------------------------------------------------------------------------
# GEPA project for lint-rule optimization
# ---------------------------------------------------------------------------

def gepa_project_dir(root_dir: str | Path) -> Path:
    return Path(root_dir) / "gepa" / "trikeshed-lint-opt"


def build_gepa_project(root_dir: str | Path) -> dict[str, Any]:
    """Build GEPA project wiring for lint-rule optimization over accumulated traces."""
    project_dir = gepa_project_dir(root_dir)
    project_dir.mkdir(parents=True, exist_ok=True)

    modules = all_trace_modules(root_dir)
    examples: list[dict[str, Any]] = []
    for module_name in modules:
        traces = load_traces(root_dir, module_name)
        if not traces:
            continue
        examples.append({
            "module_name": module_name,
            "traces": traces,
            "latest_rules": traces[-1]["rules_snapshot"] if traces else None,
        })

    agent_spec = {
        "use_cases": [
            "Detect mutable stdlib leakage in Kotlin library modules",
            "Detect executable entrypoint drift in library modules",
        ],
        "runtime_grounding_examples": {
            "mutable_leak": [
                "MutableList in constructor parameters",
                "MutableMap as return type",
                "ArrayList used internally without boundary ownership",
            ],
            "entrypoint_drift": [
                "fun main() in library source",
                "top-level println in library code",
            ],
            "namespace_hygiene": [
                "duplicate Key suffixes across modules",
                "Element.Key collision in coroutine context",
            ],
        },
        "tool_signatures": "kotlin_source_scan, regex_pattern_match, declaration_extract, signal_count",
        "target_signature": "Produce tightened forbidden_patterns, required_terms, critic_checks that reduce violations on next scan without false positives",
        "scoring_description": "Score 0-1 based on: violation precision (true violations / total flagged), rule stability across runs, absence of regressions (modules that previously passed now failing from over-tightening)",
        "counterfactual_axis_name": "failure modes",
    }

    seed_rules = {
        "forbidden_patterns": list(DEFAULT_FORBIDDEN_PATTERNS),
        "required_terms": [],
        "critic_checks": [
            "Mutable state must terminate at explicit edges.",
            "Library modules must not expose executable entrypoints.",
            "Namespace keys should remain stable and collision-resistant.",
        ],
    }

    gepa_config = {
        "project_name": "trikeshed-lint-opt",
        "components": ("lint_rules",),
        "agent_spec": agent_spec,
        "seed_candidate": {"lint_rules": json.dumps(seed_rules, indent=2)},
        "examples": examples,
        "optimize_config": {
            "max_metric_calls": 200,
            "minibatch_size": 10,
            "max_iterations": 20,
        },
    }

    config_path = project_dir / "gepa_project.json"
    config_path.write_text(json.dumps(gepa_config, indent=2, sort_keys=True) + "\n")

    init_path = project_dir / "__init__.py"
    init_path.write_text(_GEPA_PROJECT_PY)

    return {
        "project_dir": str(project_dir),
        "config_path": str(config_path),
        "module_count": len(modules),
        "example_count": len(examples),
    }


_GEPA_PROJECT_PY = '''\
"""GEPA project for TrikeShed lint-rule optimization."""
from __future__ import annotations

import json
from collections.abc import Sequence
from typing import Any

from rlm_gepa import RLMGepaProject, EvaluationContext
from rlm_gepa.schema import AgentSpec


class TrikeShedLintProject(RLMGepaProject):
    project_name = "trikeshed-lint-opt"
    components = ("lint_rules",)
    agent_spec = AgentSpec(
        use_cases=[
            "Detect mutable stdlib leakage in Kotlin library modules",
            "Detect executable entrypoint drift in library modules",
        ],
        runtime_grounding_examples={
            "mutable_leak": [
                "MutableList in constructor parameters",
                "MutableMap as return type",
                "ArrayList used internally without boundary ownership",
            ],
            "entrypoint_drift": [
                "fun main() in library source",
                "top-level println in library code",
            ],
            "namespace_hygiene": [
                "duplicate Key suffixes across modules",
                "Element.Key collision in coroutine context",
            ],
        },
        tool_signatures="kotlin_source_scan, regex_pattern_match, declaration_extract, signal_count",
        target_signature="Produce tightened forbidden_patterns, required_terms, critic_checks that reduce violations on next scan without false positives",
        scoring_description="Score 0-1 based on: violation precision, rule stability, absence of regressions",
        counterfactual_axis_name="failure modes",
    )

    def __init__(self, examples: Sequence[Any]):
        self._examples = list(examples)

    def seed_candidate(self) -> dict[str, str]:
        import sys, os
        sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
        from trikeshed_rlm_gradle import DEFAULT_FORBIDDEN_PATTERNS
        seed = {
            "forbidden_patterns": list(DEFAULT_FORBIDDEN_PATTERNS),
            "required_terms": [],
            "critic_checks": [
                "Mutable state must terminate at explicit edges.",
                "Library modules must not expose executable entrypoints.",
                "Namespace keys should remain stable and collision-resistant.",
            ],
        }
        return {"lint_rules": json.dumps(seed, indent=2)}

    def load_trainset(self) -> Sequence[Any]:
        return self._examples[:max(1, len(self._examples) * 4 // 5)]

    def load_valset(self) -> Sequence[Any]:
        return self._examples[max(1, len(self._examples) * 4 // 5):]

    async def evaluate_example(self, candidate: dict[str, str], example: Any, context: EvaluationContext):
        from rlm_gepa import RLMGepaExampleResult
        import re
        try:
            rules = json.loads(candidate["lint_rules"])
        except (json.JSONDecodeError, KeyError):
            return RLMGepaExampleResult(score=0.0, feedback="Candidate lint_rules is not valid JSON")
        patterns = [re.compile(p) for p in rules.get("forbidden_patterns", [])]
        violations_found = 0
        for trace in example.get("traces", []):
            latest_rules = trace.get("rules_snapshot", {})
            reported_kinds = set(trace.get("violation_kinds", []))
            expected_kinds = set()
            if latest_rules.get("expected_intent") == "library":
                expected_kinds.add("library_entrypoint")
            if any(p.match(str(latest_rules)) for p in patterns):
                expected_kinds.add("mutable_stdlib_leak")
            overlap = reported_kinds & expected_kinds
            if overlap:
                violations_found += 1
        score = min(1.0, violations_found / max(1, len(example.get("traces", []))))
        return RLMGepaExampleResult(
            score=score,
            feedback=f"Detected {violations_found} violations across {len(example.get('traces', []))} traces",
        )


def build_project():
    import json
    config_path = __file__.replace("__init__.py", "gepa_project.json")
    with open(config_path) as fh:
        config = json.load(fh)
    return TrikeShedLintProject(examples=config["examples"])
'''


# ---------------------------------------------------------------------------
# Apply refined rules
# ---------------------------------------------------------------------------

def apply_refined_rules(proposal_path: str | Path, rules_dir: str | Path, module_name: str) -> Path:
    proposal = _read_json(proposal_path)
    proposed = proposal.get("proposed_rules", proposal)
    rd = Path(rules_dir)
    rd.mkdir(parents=True, exist_ok=True)
    key = module_name.replace(":", "__").lstrip("_")
    target = rd / f"{key}.json"
    target.write_text(json.dumps(proposed, indent=2, sort_keys=True) + "\n")
    return target


# ---------------------------------------------------------------------------
# CLI commands
# ---------------------------------------------------------------------------


def _write_json(output_path: str | Path, payload: dict[str, Any]) -> None:
    path = Path(output_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")


def _read_json(path: str | Path) -> dict[str, Any]:
    return json.loads(Path(path).read_text())


def _command_brief(args: argparse.Namespace) -> None:
    report = build_module_report(args.module_path)
    _write_json(args.output, report)


def _command_lint(args: argparse.Namespace) -> None:
    report = _read_json(args.report)
    rules = load_rules(args.rules, module_name=report["module_name"])
    lint = lint_module(report, rules)
    _write_json(args.output, lint)
    root = getattr(args, "root_dir", None) or str(Path(args.report).resolve().parents[2])
    append_trace(root, report["module_name"], rules, lint)


def _command_delegate_packet(args: argparse.Namespace) -> None:
    report = _read_json(args.report)
    lint = _read_json(args.lint)
    packet = build_delegate_packet(report, lint)
    _write_json(args.output, packet)


def _command_refine_rules(args: argparse.Namespace) -> None:
    report = _read_json(args.report)
    lint = _read_json(args.lint)
    rules = load_rules(args.rules, module_name=report["module_name"])
    proposal = refine_rules(report, lint, rules, hermes_command=args.hermes_command)
    _write_json(args.output, proposal)


def _command_apply_rules(args: argparse.Namespace) -> None:
    target = apply_refined_rules(args.proposal, args.rules_dir, args.module_name)
    print(json.dumps({"applied": str(target)}))


def _command_gepa_build(args: argparse.Namespace) -> None:
    result = build_gepa_project(args.root_dir)
    _write_json(args.output, result)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Manual TrikeShed RLM helpers for Gradle tasks.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    brief = subparsers.add_parser("brief")
    brief.add_argument("--module-path", required=True)
    brief.add_argument("--output", required=True)
    brief.set_defaults(func=_command_brief)

    lint = subparsers.add_parser("lint")
    lint.add_argument("--report", required=True)
    lint.add_argument("--rules")
    lint.add_argument("--output", required=True)
    lint.set_defaults(func=_command_lint)

    delegate_packet = subparsers.add_parser("delegate-packet")
    delegate_packet.add_argument("--report", required=True)
    delegate_packet.add_argument("--lint", required=True)
    delegate_packet.add_argument("--output", required=True)
    delegate_packet.set_defaults(func=_command_delegate_packet)

    refine = subparsers.add_parser("refine-rules")
    refine.add_argument("--report", required=True)
    refine.add_argument("--lint", required=True)
    refine.add_argument("--rules")
    refine.add_argument("--output", required=True)
    refine.add_argument("--hermes-command")
    refine.set_defaults(func=_command_refine_rules)

    apply = subparsers.add_parser("apply-rules")
    apply.add_argument("--proposal", required=True)
    apply.add_argument("--rules-dir", required=True)
    apply.add_argument("--module-name", required=True)
    apply.set_defaults(func=_command_apply_rules)

    gepa_build = subparsers.add_parser("gepa-build")
    gepa_build.add_argument("--root-dir", required=True)
    gepa_build.add_argument("--output", required=True)
    gepa_build.set_defaults(func=_command_gepa_build)

    return parser


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
