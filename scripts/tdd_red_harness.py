#!/usr/bin/env python3
"""
TDD Red-Phase Harness: Agentifies 2 subagents to iteratively trace back test code
and restate weak testing into full TDD RED tests.

Uses the TODO("redefine test requirements") marker as the anchor.

Architecture:
  Subagent A (Trace): Analyzes test file + production code → produces RED spec
  Subagent B (Rewrite): Takes RED spec → writes proper failing test replacing TODO

Run: python3 scripts/tdd_red_harness.py
"""

import json
import sys
from pathlib import Path
from typing import List, Dict, Any

TODO_MARKER = 'TODO("redefine test requirements")'

def find_todo_tests(root: Path) -> List[Dict[str, Any]]:
    """Find all test files containing the TODO marker."""
    results = []
    for test_file in root.rglob("*Test*.kt"):
        if "build" in test_file.parts:
            continue
        try:
            content = test_file.read_text()
            if TODO_MARKER in content:
                lines = content.split('\n')
                for i, line in enumerate(lines):
                    if TODO_MARKER in line:
                        for j in range(i, -1, -1):
                            if '@Test' in lines[j] or 'fun `' in lines[j]:
                                func_line = lines[j].strip()
                                # Get broader context (whole test function)
                                end = i
                                while end < len(lines) and not lines[end].strip().startswith('}'):
                                    end += 1
                                results.append({
                                    "file": str(test_file),
                                    "line": i + 1,
                                    "function": func_line,
                                    "full_context": '\n'.join(lines[max(0, j-5):min(len(lines), end+2)])
                                })
                                break
        except Exception as e:
            print(f"Error reading {test_file}: {e}", file=sys.stderr)
    return results


def build_trace_prompt(todo: Dict[str, Any]) -> str:
    """Build prompt for Subagent A: Trace test intent from context."""
    return f"""You are Subagent A: TEST TRACER

TASK: Analyze this weak test placeholder and trace back to the REAL TDD RED test that should exist.

FILE: {todo['file']}
FUNCTION: {todo['function']}

CURRENT TEST CONTEXT:
```
{todo['full_context']}
```

YOUR JOB:
1. Read the production code this test targets (find the .kt file in src/commonMain or src/jvmMain)
2. Understand the actual API, types, and behavior
3. Determine what a REAL failing TDD test would look like for this function
4. Output a JSON spec for Subagent B to write the test

OUTPUT FORMAT (JSON only):
{{
  "test_name": "descriptive test name",
  "target_production_file": "path/to/production/code.kt",
  "target_class_or_function": "ClassName.methodName",
  "red_test_code": "complete Kotlin test function that WILL FAIL because implementation is missing",
  "expected_failure_reason": "what specific error/message proves the test is testing the right missing behavior",
  "dependencies": ["any imports or setup needed"],
  "tdd_phase": "RED"
}}

RULES:
- The test MUST fail on first run (compile error or assertion failure)
- Test real behavior, not mocks
- One behavior per test
- Use actual production types from the codebase
- NO assertTrue(true) or TODO
- Follow TDD skill: test-first, watch it fail, then implement
"""


def build_rewrite_prompt(todo: Dict[str, Any], trace_result: Dict[str, Any]) -> str:
    """Build prompt for Subagent B: Rewrite test with proper RED test."""
    return f"""You are Subagent B: TEST REWRITER

TASK: Replace the TODO placeholder with a proper failing TDD RED test.

FILE TO MODIFY: {todo['file']}
ORIGINAL FUNCTION: {todo['function']}

TRACE RESULT FROM SUBAGENT A:
```json
{json.dumps(trace_result, indent=2)}
```

YOUR JOB:
1. Read the current test file
2. Replace the TODO test function with the red_test_code from trace result
3. Add any missing imports
4. Write the modified file back
5. Run the test to verify it FAILS (compile error or assertion failure)
6. Report the failure output

TOOLS: You have terminal and file access. Use them.

VERIFICATION:
- Run: ./gradlew :<module>:jvmTest --tests "<TestClass>.<testName>" --rerun-tasks
- Confirm test fails for the EXPECTED reason
- If it passes, the test is wrong - fix it

OUTPUT: JSON with result status and failure evidence
"""


def main():
    root = Path("/Users/jim/work/TrikeShed")
    todos = find_todo_tests(root)
    
    print(f"Found {len(todos)} TODO test placeholders")
    
    # Output for consumption by the orchestration layer
    print(json.dumps({
        "todos": todos,
        "trace_prompt_template": build_trace_prompt(todos[0]) if todos else "",
        "rewrite_prompt_template": "see build_rewrite_prompt()"
    }, indent=2))


if __name__ == "__main__":
    main()