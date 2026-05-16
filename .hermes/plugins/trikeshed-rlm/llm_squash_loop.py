#!/usr/bin/env python3
"""
Locked LLM fixer loop for TrikeShed RLM violations.
Scan → LLM fixes → Re-scan → Repeat. No human interaction.
"""
import json
import subprocess
import time
from pathlib import Path
from typing import List, Dict, Any


def run_scan(root_dir: Path) -> Dict[str, Any]:
    """Run RLM scan and return results."""
    result = subprocess.run(
        ['python3', 'scripts/trikeshed_rlm_scan.py', 'scan', '--libs-dir', 'libs'],
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


def load_violations(root_dir: Path, module: str) -> tuple[List[Dict[str, Any]], Path]:
    """Load violations for a specific module. Returns (violations_list, module_dir)."""
    vpath = root_dir / 'build' / 'trikeshed-rlm' / module / 'violations.json'
    if not vpath.exists():
        return [], root_dir
    
    vdata = json.loads(vpath.read_text())
    module_dir = Path(vdata.get('module_path', root_dir))
    return vdata.get('violations', []), module_dir


def fix_violation_with_llm(
    root_dir: Path,
    module_dir: Path,
    violation: Dict[str, Any]
) -> bool:
    """
    Use LLM to fix a single violation.
    Returns True if fix was applied successfully.
    """
    kind = violation.get('kind')
    evidence = violation.get('evidence', '')
    remedy = violation.get('remedy', '')
    
    # Parse file path from evidence: "src/path/to/File.kt:42: ..."
    parts = evidence.split(':', 2)
    if len(parts) < 3:
        return False
    
    rel_path = parts[0]
    file_path = module_dir / rel_path
    
    if not file_path.exists():
        return False
    
    # Read file content
    content = file_path.read_text()
    
    # Build fix prompt for LLM
    prompt = f"""Fix this TrikeShed RLM violation by applying the required refactoring.

**Violation Kind:** {kind}

**File:** {rel_path}

**Evidence:** {evidence}

**Remedy:** {remedy}

**Current File Content:**
```kotlin
{content}
```

**Instructions:**
1. Apply the remedy precisely - don't make other changes
2. Follow the miniduck pattern: retrieve reactor services from coroutine context via `currentCoroutineContext()[Service.Key]` inside suspend functions
3. Remove constructor parameters that store reactor services as fields
4. Remove class-level fields that hold reactor services
5. Add `import kotlinx.coroutines.currentCoroutineContext` if needed
6. Preserve all other code, formatting, and comments exactly

**Output:** Return ONLY the complete fixed file content. No explanations, no markdown blocks. Just the raw Kotlin code.
"""
    
    # Call LLM via subprocess (using the same CLI we're in)
    # This assumes hermes is available and we can call it
    # For now, we'll write the prompt to a temp file and use execute_code
    
    import tempfile
    with tempfile.NamedTemporaryFile(mode='w', suffix='.kt', delete=False) as f:
        temp_file = f.name
    
    try:
        # Write prompt to temp file
        prompt_file = file_path.parent / f'{file_path.stem}_FIX_PROMPT.txt'
        prompt_file.write_text(prompt)
        
        # We'd need to call the LLM here, but we can't do it from Python
        # without the hermes API. For now, return False to indicate manual fix needed.
        # TODO: Integrate with hermes LLM API
        
        return False
    finally:
        import os
        if os.path.exists(temp_file):
            os.unlink(temp_file)


def llm_squash_loop(root_dir: Path, max_iterations: int = 100):
    """Main LLM squash loop."""
    print(f"Starting LLM squash loop in {root_dir}")
    print("=" * 60)
    
    for iteration in range(max_iterations):
        print(f"\n[Iteration {iteration + 1}/{max_iterations}]")
        
        # Scan
        scan_result = run_scan(root_dir)
        if 'error' in scan_result:
            print(f"Scan error: {scan_result['error']}")
            time.sleep(60)
            continue
        
        failed_modules = scan_result['failed_modules']
        total_violations = scan_result['total']
        
        print(f"Total violations: {total_violations}")
        print(f"Failed modules: {len(failed_modules)}")
        
        if not failed_modules:
            print("\n✓ ALL MODULES PASSING - Done!")
            break
        
        # Fix each module
        fixed_count = 0
        for module in failed_modules:
            violations, module_dir = load_violations(root_dir, module)
            print(f"\n  {module}: {len(violations)} violations")
            
            for v in violations:
                kind = v.get('kind')
                print(f"    - {kind}: {v.get('evidence', '')[:80]}...")
                
                try:
                    if fix_violation_with_llm(root_dir, module_dir, v):
                        print(f"      ✓ FIXED")
                        fixed_count += 1
                    else:
                        print(f"      ✗ NO LLM INTEGRATION (manual fix required)")
                except Exception as e:
                    print(f"      ✗ ERROR - {e}")
        
        if fixed_count == 0:
            print("\nNo fixes applied - LLM integration not implemented")
            break
        
        print(f"\nFixed {fixed_count} violations this iteration")
        print("Waiting 5 seconds before re-scan...")
        time.sleep(5)
    
    print("\n" + "=" * 60)
    print("LLM squash loop complete")


if __name__ == '__main__':
    import sys
    
    root = Path.cwd()
    if len(sys.argv) > 1:
        root = Path(sys.argv[1])
    
    llm_squash_loop(root)
