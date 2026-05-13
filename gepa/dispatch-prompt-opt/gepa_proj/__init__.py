"""
Bugzee Dispatch Prompt Optimization Project for RLM-GEPA.

Optimizes subagent dispatch prompts (context + goal) for multi-agent coding tasks.
Each example is a past dispatch with execution metrics and quality scores.
"""
from __future__ import annotations

import json
import random
from collections.abc import Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from rlm_gepa import (
    EvaluationContext,
    RLMGepaExampleResult,
    RLMGepaProject,
    agent_spec_from_rlm,
)
from rlm_gepa.schema import AgentSpec


PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent


@dataclass
class DispatchExample:
    """One dispatch example: task description + execution outcome."""
    task_name: str
    target_tool: str  # codex|copilot|qwen|opencode
    goal: str
    context: str
    expected_files: list[str]
    read_files: list[str]
    actual_duration: float
    actual_api_calls: int
    actual_tokens: int
    quality: float
    had_file_conflicts: bool
    compilation_errors: int


def _load_trace() -> list[dict[str, Any]]:
    """Load execution trace from bugzee dispatch run."""
    trace_path = PROJECT_ROOT / "gepa" / "dispatch-prompt-opt" / "bench" / "execution_trace.json"
    if not trace_path.exists():
        return []
    return json.loads(trace_path.read_text())


def _flatten_executions(trace: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Flatten nested execution data into flat examples."""
    examples = []
    for round_data in trace:
        agents = round_data.get("agents", [])
        for agent in agents:
            examples.append({
                "round": round_data.get("round", ""),
                "tool": agent.get("tool", ""),
                "task": agent.get("task", ""),
                "goal_len": agent.get("goal_len", 0),
                "context_len": agent.get("context_len", 0),
                "duration": agent.get("duration_seconds", 0),
                "api_calls": agent.get("api_calls", 0),
                "tokens": agent.get("tokens", {}),
                "quality": agent.get("quality", 0),
                "issues": round_data.get("issues", ""),
            })
    return examples


def load_examples() -> list[DispatchExample]:
    """Load all dispatch examples from execution traces."""
    trace = _load_trace()
    flat = _flatten_executions(trace)

    examples = []
    for ex in flat:
        tokens = ex.get("tokens", {})
        examples.append(DispatchExample(
            task_name=ex["task"],
            target_tool=ex["tool"],
            goal=f"Goal length: {ex['goal_len']} chars",
            context=f"Context length: {ex['context_len']} chars",
            expected_files=[],
            read_files=[],
            actual_duration=ex["duration"],
            actual_api_calls=ex["api_calls"],
            actual_tokens=tokens.get("input", 0) + tokens.get("output", 0),
            quality=ex["quality"],
            had_file_conflicts="conflict" in ex.get("issues", "").lower(),
            compilation_errors=0,
        ))
    return examples


def score_dispatch(
    candidate: dict[str, str],
    example: DispatchExample,
) -> RLMGepaExampleResult:
    """Score a dispatch prompt against an example execution."""
    score = example.quality

    # Penalize file conflicts
    if example.had_file_conflicts:
        score -= 0.1

    # Penalize high token usage
    if example.actual_tokens > 300_000:
        excess = (example.actual_tokens - 300_000) / 100_000
        score -= 0.05 * excess

    # Penalize long duration
    if example.actual_duration > 120:
        excess = (example.actual_duration - 120) / 60
        score -= 0.05 * excess

    # Penalize compilation errors
    score -= 0.1 * example.compilation_errors

    # Bonus for good tool selection
    if example.target_tool == "codex" and "codex" in str(candidate).lower():
        score += 0.05

    score = max(0.0, min(1.0, score))

    feedback = (
        f"Task: {example.task_name}, Tool: {example.target_tool}, "
        f"Duration: {example.actual_duration:.1f}s, "
        f"Tokens: {example.actual_tokens:,}, "
        f"API calls: {example.actual_api_calls}, "
        f"Quality: {example.quality:.2f}"
    )
    if example.had_file_conflicts:
        feedback += ", FILE CONFLICT"
    if example.actual_tokens > 300_000:
        feedback += f", EXCESS TOKENS ({example.actual_tokens:,})"
    if example.actual_duration > 120:
        feedback += f", SLOW ({example.actual_duration:.1f}s)"

    return RLMGepaExampleResult(
        score=score,
        feedback=feedback,
        traces=[],
    )


class DispatchPromptProject(RLMGepaProject):
    project_name = "bugzee-dispatch-prompt-opt"
    components = ("dispatch_strategy", "prompt_template", "context_specifier")
    agent_spec = AgentSpec(
        use_cases=[
            "Dispatch parallel subagents to implement features across a KMP monorepo",
            "Dispatch subagents to create new library modules with cross-dependencies",
            "Dispatch subagents to fix compilation errors across multiple files",
            "Dispatch subagents for exploratory analysis of a codebase",
            "Dispatch subagents for test generation across modules",
        ],
        runtime_grounding_examples={
            "file_isolation": [
                "Two parallel agents modifying the same file causes merge conflicts",
                "Agent A's write_file overwrites Agent B's changes to BugzeeService.kt",
                "Subagents must have exclusive write targets; no shared files",
            ],
            "token_budgets": [
                "A 36 api_calls subagent used 1.5M input tokens for a single task",
                "Context field was 782 chars of file paths — redundant reads inflated tokens",
                "Over-specifying existing code in context causes token explosion",
            ],
            "dependency_ordering": [
                "bugzee build failed because hazelnut dependency wasn't declared",
                "Agent modified build.gradle.kts but parent module didn't compile",
                "Transport layer depends on cluster, which depends on service — sequential order needed",
            ],
        },
        tool_signatures="""Dispatch parallel subagents to implement features across a KMP monorepo.""",
        target_signature="""Given a multi-agent coding task, produce optimal dispatch prompts for 3-5
subagents. Each prompt has: (1) tool field (codex|copilot|qwen|opencode),
(2) goal field (self-contained task description), (3) context field
(file paths, existing code references, constraints). The dispatch must
minimize token waste, avoid file conflicts, and maximize output quality.""",
        scoring_description="""Score from 0-1. 1.0 = all subagents complete with zero conflicts, minimal
token usage (<200K input per agent), clean compilation, and no shared file
modifications. Deductions: -0.1 per shared-file conflict, -0.05 per 100K
excess tokens above 300K, -0.1 per compilation error introduced, -0.05 per
60s above 120s average duration. Bonus +0.05 if sequential dependency
ordering is explicit for dependent tasks.""",
        counterfactual_axis_name="failure modes",
        domain_conventions_note="""TrikeShed is a KMP monorepo. CharSequence not String in commonMain.
No type conversion wrappers. UringFacade is the platform. Tests verify
algebraic purity. Subagents cannot use clarify, memory, or execute_code.""",
    )

    def seed_candidate(self) -> dict[str, str]:
        return {
            "dispatch_strategy": "Sequential dependency ordering: service -> cluster -> couch -> transports -> spokes. Each agent gets exclusive file targets. No shared file modifications between parallel agents.",
            "prompt_template": "Goal: {goal}\nContext: Read these files first: {read_files}\nCreate/modify: {write_files}\nConstraints: {constraints}\nOutput: Only modify specified files. Use CharSequence types. Match existing style.",
            "context_specifier": "Minimal context: only file paths that agent must read. No duplicate information. Reference existing patterns with specific file:line references, not full file contents.",
        }

    def load_trainset(self) -> Sequence[DispatchExample]:
        examples = load_examples()
        if not examples:
            # Synthetic examples from known patterns
            return [
                DispatchExample(
                    task_name="service",
                    target_tool="codex",
                    goal="service",
                    context="service",
                    expected_files=["BugzeeService.kt"],
                    read_files=["BugzeeService.kt"],
                    actual_duration=97.26,
                    actual_api_calls=7,
                    actual_tokens=59201,
                    quality=0.85,
                    had_file_conflicts=True,
                    compilation_errors=0,
                ),
                DispatchExample(
                    task_name="cluster",
                    target_tool="copilot",
                    goal="cluster",
                    context="cluster",
                    expected_files=["BugzeeCluster.kt"],
                    read_files=["HazelnutService.kt", "FunctionalUringFacade.kt"],
                    actual_duration=377.43,
                    actual_api_calls=16,
                    actual_tokens=512763,
                    quality=0.90,
                    had_file_conflicts=False,
                    compilation_errors=0,
                ),
                DispatchExample(
                    task_name="couch_grounding",
                    target_tool="qwen",
                    goal="couch_grounding",
                    context="couch_grounding",
                    expected_files=["BugzeeCouchGrounding.kt"],
                    read_files=["HazelnutService.kt", "BugzeeService.kt"],
                    actual_duration=337.28,
                    actual_api_calls=13,
                    actual_tokens=366276,
                    quality=0.88,
                    had_file_conflicts=False,
                    compilation_errors=0,
                ),
            ]
        # Return first 70% as training
        random.seed(42)
        random.shuffle(examples)
        n_train = max(1, int(len(examples) * 0.7))
        return examples[:n_train]

    def load_valset(self) -> Sequence[DispatchExample]:
        examples = load_examples()
        if not examples:
            return [
                DispatchExample(
                    task_name="transports",
                    target_tool="qwen",
                    goal="transports",
                    context="transports",
                    expected_files=["BugzeeTransports.kt"],
                    read_files=["FunctionalUringFacade.kt", "UringOp.kt"],
                    actual_duration=409.06,
                    actual_api_calls=36,
                    actual_tokens=1528450,
                    quality=0.82,
                    had_file_conflicts=False,
                    compilation_errors=1,
                ),
                DispatchExample(
                    task_name="spokes",
                    target_tool="qwen",
                    goal="spokes",
                    context="spokes",
                    expected_files=["BugzeeSpokes.kt"],
                    read_files=["BugzeeService.kt", "BugzeeCluster.kt"],
                    actual_duration=336.29,
                    actual_api_calls=10,
                    actual_tokens=579638,
                    quality=0.90,
                    had_file_conflicts=False,
                    compilation_errors=0,
                ),
            ]
        # Return last 30% as validation
        random.seed(42)
        random.shuffle(examples)
        n_train = max(1, int(len(examples) * 0.7))
        return examples[n_train:]

    def evaluate_example(
        self,
        candidate: dict[str, str],
        example: DispatchExample,
        context: EvaluationContext,
    ) -> RLMGepaExampleResult:
        result = score_dispatch(candidate, example)
        return result


def build_project() -> DispatchPromptProject:
    return DispatchPromptProject()
