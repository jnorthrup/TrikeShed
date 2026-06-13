#!/usr/bin/env python3
"""
Orchestrator: Spawns 2 subagents per TODO test to convert weak tests into TDD RED tests.

Subagent A (TRACER): Reads production code, traces intent, outputs RED test spec
Subagent B (REWRITER): Replaces TODO with proper failing test, verifies it fails

Usage: python3 scripts/tdd_red_orchestrator.py
"""

import json
import subprocess
import sys
from pathlib import Path

# Add scripts to path for imports
sys.path.insert(0, str(Path(__file__).parent))
from tdd_red_harness import find_todo_tests, build_trace_prompt, build_rewrite_prompt

ROOT = Path("/Users/jim/work/TrikeShed")

def run_subagent(goal: str, context: str, toolsets: list[str] | None = None) -> dict:
    """Spawn a subagent via delegate_task (via hermes CLI)."""
    # We'll use the hermes delegate_task tool via terminal
    # For now, output the delegate_task call structure
    return {
        "goal": goal,
        "context": context,
        "toolsets": toolsets or ["terminal", "file", "web"]
    }

def extract_module_from_path(test_file: str) -> str:
    """Extract gradle module path from test file path."""
    # /Users/jim/work/TrikeShed/libs/tiny-btrfs/src/commonTest/... -> :libs:tiny-btrfs
    parts = Path(test_file).parts
    try:
        libs_idx = parts.index("libs")
        module = parts[libs_idx + 1]
        return f":libs:{module}"
    except (ValueError, IndexError):
        try:
            src_idx = parts.index("src")
            # src/commonTest -> root project
            return ""
        except ValueError:
            return ""

def main():
    todos = find_todo_tests(ROOT)
    print(f"Orchestrating {len(todos)} TODO tests with 2-subagent pipeline")
    
    for i, todo in enumerate(todos):
        print(f"\n{'='*60}")
        print(f"[{i+1}/{len(todos)}] {todo['file']}:{todo['line']}")
        print(f"Function: {todo['function']}")
        
        module = extract_module_from_path(todo['file'])
        print(f"Module: {module or 'root'}")
        
        # Phase 1: TRACER subagent
        trace_prompt = build_trace_prompt(todo)
        trace_task = run_subagent(
            goal="Trace test intent from production code and output RED test spec JSON",
            context=trace_prompt,
            toolsets=["terminal", "file"]
        )
        
        print("\n--- SUBAGENT A (TRACER) ---")
        print(json.dumps(trace_task, indent=2))
        
        # In real execution, we'd call delegate_task here and get result
        # For now, this outputs the structure for the user to run
        
        # Phase 2: REWRITER subagent (would use trace result)
        # rewrite_prompt = build_rewrite_prompt(todo, trace_result)
        # rewrite_task = run_subagent(...)
        
        # Stop after first for demo
        if i == 0:
            print("\n[Demo mode: stopping after first test. Remove break to process all.]")
            break

if __name__ == "__main__":
    main()