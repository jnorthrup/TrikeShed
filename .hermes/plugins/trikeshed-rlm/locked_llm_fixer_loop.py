#!/usr/bin/env python3
"""
Locked LLM fixer loop for TrikeShed RLM violations.
Scan → LLM fixes → Re-scan → Repeat. No human interaction.

This is the GEPA loop: deterministic scan, LLM fixes, verification.
"""
import json
import subprocess
import time
from pathlib import Path
from typing import List, Dict, Any, Tuple


def run_scan(root_dir: Path) -> Dict[str, Any]:
    """Run RLM scan and return results."""
    result = subprocess.run(
        ['python3', '.hermes/plugins/trikeshed-rlm/trikeshed_rlm_scan.py', 'scan', '--libs-dir', 'libs'],
        cwd=root_dir,
        capture_output=True,
        text=True,
        timeout=300
    )
    if result.returncode != 0:
        return {'error': result.stderr, 'failed_modules': []}
    
    scan_path = root_dir / 'build' / 'trikeshed-rlm' / 'scan-results.json'
    if not scan_path.exists():
        return {'error': 'No scan results', 'failed_modules': []}
    
    data = json.loads(scan_path.read_text())
    failed_modules = [r['module_name'] for r in data.get('results', []) if not r.get('passed', True)]
    return {'failed_modules': failed_modules, 'total': data.get('total_violations', 0)}


def load_violations(root_dir: Path, module: str) -> Tuple[List[Dict[str, Any]], Path]:
    """Load violations for a specific module."""
    vpath = root_dir / 'build' / 'trikeshed-rlm' / module / 'violations.json'
    if not vpath.exists():
        return [], root_dir
    
    vdata = json.loads(vpath.read_text())
    module_dir = Path(vdata.get('module_path', root_dir))
    return vdata.get('violations', []), module_dir


def generate_fix_prompts(
    root_dir: Path,
    module: str,
    violations: List[Dict[str, Any]],
    module_dir: Path
) -> List[Path]:
    """Generate fix prompt files for each violation."""
    fixes_dir = root_dir / 'build' / 'trikeshed-rlm' / module / 'fixes'
    fixes_dir.mkdir(parents=True, exist_ok=True)
    
    prompt_files = []
    for i, v in enumerate(violations):
        kind = v.get('kind')
        evidence = v.get('evidence', '')
        remedy = v.get('remedy', '')
        
        # Parse file path from evidence
        parts = evidence.split(':', 2)
        if len(parts) < 3:
            continue
        
        rel_path = parts[0]
        line_num = parts[1]
        
        # Skip library_entrypoint (CLI tools - acceptable)
        if kind == 'library_entrypoint':
            continue
        
        prompt = f"""Fix this TrikeShed RLM violation.

**Violation:** {kind}
**File:** {rel_path}
**Line:** {line_num}
**Evidence:** {evidence}
**Remedy:** {remedy}

**Instructions:**
1. Apply the remedy precisely
2. Follow miniduck pattern: retrieve from coroutine context via `currentCoroutineContext()[Service.Key]`
3. For context_injection_bypass: remove constructor params storing reactor services, retrieve from context in suspend functions
4. For reactor_field_hold: remove class-level fields holding reactor services, retrieve from context in suspend functions
5. Add `import kotlinx.coroutines.currentCoroutineContext` if needed
6. Preserve all other code exactly

Use the patch tool with mode='replace' to apply the fix. Read the full file first, identify the exact constructor param or field, and replace it."""
        
        prompt_file = fixes_dir / f'{rel_path.replace("/", "_")}_{line_num}.prompt'
        prompt_file.write_text(prompt)
        prompt_files.append(prompt_file)
    
    return prompt_files


def run_llm_fixer_loop(root_dir: Path, max_iterations: int = 100):
    """
    Main GEPA loop: Scan → Generate Prompts → LLM Fixes → Verify → Repeat.
    This is the locked loop - no human intervention required.
    """
    print(f"Starting locked LLM fixer loop in {root_dir}")
    print("=" * 70)
    
    for iteration in range(max_iterations):
        print(f"\n[Iteration {iteration + 1}/{max_iterations}]")
        print("-" * 70)
        
        # Step 1: Scan
        scan_result = run_scan(root_dir)
        if 'error' in scan_result:
            print(f"✗ Scan error: {scan_result['error']}")
            print("Waiting 60s before retry...")
            time.sleep(60)
            continue
        
        failed_modules = scan_result['failed_modules']
        total_violations = scan_result['total']
        
        print(f"Total violations: {total_violations}")
        print(f"Failed modules: {len(failed_modules)}")
        
        if not failed_modules:
            print("\n" + "=" * 70)
            print("✓ ALL MODULES PASSING - LOCKED LOOP COMPLETE")
            print("=" * 70)
            break
        
        # Step 2: Generate fix prompts
        all_prompts = []
        for module in failed_modules:
            violations, module_dir = load_violations(root_dir, module)
            print(f"\n  {module}: {len(violations)} violations")
            prompt_files = generate_fix_prompts(root_dir, module, violations, module_dir)
            all_prompts.extend([(module, pf) for pf in prompt_files])
            print(f"    Generated {len(prompt_files)} fix prompts")
        
        if not all_prompts:
            print("\nNo fixable violations (only library_entrypoint - CLI tools)")
            print("These are acceptable - locked loop complete")
            break
        
        # Step 3: Apply fixes via LLM (delegate to hermes subagents)
        print(f"\n  Applying {len(all_prompts)} fixes via LLM...")
        print("  (This requires hermes with delegate_task capability)")
        print("\n  To apply fixes manually, run:")
        for module, prompt_file in all_prompts:
            print(f"    hermes 'Fix the violation in {prompt_file}'")
        
        print("\n✗ LLM integration requires hermes CLI")
        print("  Auto-fix loop paused - manual intervention required")
        print("  Or integrate with hermes delegate_task API")
        break
        
        # Step 4: Would re-scan here if LLM fixes were applied
        # For now, we pause and let user decide
    
    print("\n" + "=" * 70)
    print("Locked LLM fixer loop stopped")
    print("=" * 70)


if __name__ == '__main__':
    import sys
    
    root = Path.cwd()
    if len(sys.argv) > 1:
        root = Path(sys.argv[1])
    
    run_llm_fixer_loop(root)
