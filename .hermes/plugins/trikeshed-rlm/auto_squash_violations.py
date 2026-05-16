#!/usr/bin/env python3
"""
Auto-squash TrikeShed RLM violations - hard-coded deterministic fixer.
No LLM, no interaction, just patches. Runs in a loop until clean.
"""
import json
import re
import subprocess
import time
from pathlib import Path
from typing import Callable, Dict, List, Any


def run_scan(root_dir: Path) -> Dict[str, Any]:
    """Run RLM scan and return results."""
    scan_script = root_dir / '.hermes' / 'plugins' / 'trikeshed-rlm' / 'trikeshed_rlm_scan.py'
    result = subprocess.run(
        ['python3', str(scan_script), 'scan', '--libs-dir', 'libs'],
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


def fix_context_injection_bypass(root_dir: Path, module_dir: Path, violation: Dict[str, Any]) -> bool:
    """
    Fix context_injection_bypass: Remove constructor param storing reactor service.

    Pattern:
      class Foo(private val channelOps: ChannelOperations)  # BEFORE
      class Foo() {                                          # AFTER
          private suspend fun bar() {
              val channelOps = currentCoroutineContext()[ChannelOperations.Key]!!
          }
      }
    """
    evidence = violation.get('evidence', '')
    # Parse: "src/path/to/File.kt:37: private val channelOps: ChannelOperations,"
    parts = evidence.split(':', 2)
    if len(parts) < 3:
        return False

    rel_path = parts[0]
    line_no = int(parts[1])
    param_line = parts[2].strip()

    file_path = module_dir / rel_path

    if not file_path.exists():
        return False

    # Extract param name and type
    match = re.search(r'(?:private val |val |var |)(\w+):\s*(ChannelOperations|ReactorOperations|FileOperations|SystemOperations|ProcessOperations)', param_line)
    if not match:
        return False

    param_name = match.group(1)
    service_type = match.group(2)

    # Read file
    lines = file_path.read_text().splitlines()

    # Find constructor declaration and remove the param
    removed = False
    for i, line in enumerate(lines):
        if i == line_no - 1:
            lines[i] = re.sub(rf'(?:private val |val |var ){param_name}:\s*{service_type},?\s*', '', line)
            lines[i] = lines[i].replace(', ,', ',').replace(') ,', ')').replace(',)', ')')
            removed = True
            break

    if not removed:
        return False

    # Find all suspend functions that use the param and add context lookup at the beginning
    for i, line in enumerate(lines):
        if param_name in line:
            in_suspend_fun = False
            for j in range(max(0, i-50), i):
                if 'suspend fun' in lines[j]:
                    in_suspend_fun = True
                    for k in range(j, min(len(lines), j+20)):
                        if '{' in lines[k]:
                            indent = ' ' * (len(lines[k]) - len(lines[k].lstrip()) + 4)
                            lines.insert(k+1, f"{indent}val {param_name} = currentCoroutineContext()[{service_type}.Key]!!")
                            break
                    break

            if in_suspend_fun:
                if 'currentCoroutineContext' not in '\n'.join(lines[:min(20, i)]):
                    for j in range(min(10, len(lines))):
                        if lines[j].strip().startswith('import '):
                            lines.insert(j+1, f"import kotlinx.coroutines.currentCoroutineContext")
                            break
                        if lines[j].strip().startswith('package ') or lines[j].strip().startswith('class '):
                            lines.insert(j, f"import kotlinx.coroutines.currentCoroutineContext")
                            break

    file_path.write_text('\n'.join(lines) + '\n')
    return True


def fix_reactor_field_hold(root_dir: Path, module_dir: Path, violation: Dict[str, Any]) -> bool:
    """Same pattern as context_injection_bypass."""
    return fix_context_injection_bypass(root_dir, module_dir, violation)


def fix_missing_context_key(root_dir: Path, module_dir: Path, violation: Dict[str, Any]) -> bool:
    """
    Fix missing_context_key: Add companion object Key to service implementation.

    Pattern:
      class MyService : FileOperations {              # BEFORE
      class MyService : FileOperations {
          companion object Key : CoroutineContext.Key<MyService>
          override val key: CoroutineContext.Key<*> get() = Key
      }
    """
    evidence = violation.get('evidence', '')
    parts = evidence.split(':', 2)
    if len(parts) < 2:
        return False

    rel_path = parts[0]
    line_no = int(parts[1])

    file_path = module_dir / rel_path

    if not file_path.exists():
        return False

    lines = file_path.read_text().splitlines()

    class_line_idx = line_no - 1
    class_line = lines[class_line_idx]

    class_match = re.search(r'class\s+(\w+)', class_line)
    if not class_match:
        return False
    class_name = class_match.group(1)

    impl_match = re.search(r':\s*(\w+)', class_line)
    if not impl_match:
        return False

    brace_depth = 0
    found_opening = False
    for i in range(class_line_idx, len(lines)):
        if '{' in lines[i]:
            brace_depth += lines[i].count('{')
            found_opening = True
        if '}' in lines[i]:
            brace_depth -= lines[i].count('}')
            if found_opening and brace_depth == 0:
                indent = "    "
                lines.insert(i, f"{indent}companion object Key : CoroutineContext.Key<{class_name}>")
                lines.insert(i, f"{indent}override val key: CoroutineContext.Key<*> get() = Key")
                lines.insert(i, "")
                break

    if 'CoroutineContext.Key' not in '\n'.join(lines[:50]):
        for j in range(min(20, len(lines))):
            if lines[j].strip().startswith('import '):
                lines.insert(j+1, f"import kotlin.coroutines.CoroutineContext")
                break
            if lines[j].strip().startswith('package '):
                lines.insert(j+1, f"import kotlin.coroutines.CoroutineContext")
                break

    file_path.write_text('\n'.join(lines) + '\n')
    return True


def fix_mutable_stdlib_leak(root_dir: Path, module_dir: Path, violation: Dict[str, Any]) -> bool:
    """
    Fix mutable_stdlib_leak: replace mutableListOf/mutableMapOf/mutableSetOf/mutableMap
    with TrikeShed pure alternatives.

    Also handles java.nio.* violations: swap java.nio imports for userspace equivalents
    (detected by evidence containing 'java.nio.').

    MutableCollectionFactory → TrikeShed equivalent:
      mutableListOf(...)  → LongSeries.build { it += ... }
      mutableMapOf(...)   → LongLongSeries.build { putAll(mapOf(...)) }
      mutableSetOf(...)   → LongSeries.build { it += ... }
      mutableMap<K,V>()    → LongLongSeries.build { }

    Lines containing the forbidden pattern are patched in-place.
    """
    evidence = violation.get('evidence', '')
    # evidence: "src/path/to/File.kt: mutableListOf" or "java.nio." or "java.nio.file.Path"
    parts = evidence.rsplit(':', 1)
    if len(parts) != 2:
        return False

    rel_path = parts[0].strip()
    pattern = parts[1].strip()
    file_path = module_dir / rel_path

    if not file_path.exists():
        return False

    # ── java.nio.* import AND usage-site swap ───────────────────────────────
    if 'java.nio.' in evidence:
        content = file_path.read_text()
        lines = content.splitlines()
        changed = False

        # Import-level replacements (order matters — broader before narrower)
        import_replacements = [
            ('import java.nio.file.Path',    'import borg.trikeshed.userspace.nio.file.Path'),
            ('import java.nio.file.Files',   'import borg.trikeshed.userspace.nio.file.Files'),
            ('import java.nio.file.Paths',   'import borg.trikeshed.userspace.nio.file.Paths'),
            ('import java.nio.file.*',      'import borg.trikeshed.userspace.nio.file.*'),
            ('import java.nio.ByteBuffer',  'import borg.trikeshed.userspace.nio.ByteBuffer'),
            ('import java.nio.ByteOrder',   'import borg.trikeshed.userspace.nio.ByteOrder'),
            ('import java.nio.*',           'import borg.trikeshed.userspace.nio.*'),
        ]

        for i, line in enumerate(lines):
            new_line = line
            for old, new in import_replacements:
                if old in new_line:
                    new_line = new_line.replace(old, new)
                    changed = True
                    break
            # Also replace usage sites: java.nio.ByteBuffer. → borg.trikeshed.userspace.nio.ByteBuffer.
            # and java.nio.ByteOrder. → borg.trikeshed.userspace.nio.ByteOrder.
            if 'java.nio.' in new_line:
                new_line = new_line.replace('java.nio.ByteBuffer.', 'borg.trikeshed.userspace.nio.ByteBuffer.')
                new_line = new_line.replace('java.nio.ByteOrder.', 'borg.trikeshed.userspace.nio.ByteOrder.')
                new_line = new_line.replace('java.nio.file.Files.', 'borg.trikeshed.userspace.nio.file.Files.')
                new_line = new_line.replace('java.nio.file.Path.', 'borg.trikeshed.userspace.nio.file.Path.')
                new_line = new_line.replace('java.nio.file.Paths.', 'borg.trikeshed.userspace.nio.file.Paths.')
                # Also handle fully-qualified type in signatures: : java.nio.ByteBuffer
                new_line = new_line.replace(': java.nio.ByteBuffer', ': borg.trikeshed.userspace.nio.ByteBuffer')
                new_line = new_line.replace(': java.nio.ByteOrder', ': borg.trikeshed.userspace.nio.ByteOrder')
                new_line = new_line.replace(' java.nio.ByteBuffer', ' borg.trikeshed.userspace.nio.ByteBuffer')
                new_line = new_line.replace(' java.nio.ByteOrder', ' borg.trikeshed.userspace.nio.ByteOrder')
                new_line = new_line.replace('(java.nio.ByteBuffer', '(borg.trikeshed.userspace.nio.ByteBuffer')
                new_line = new_line.replace('(java.nio.ByteOrder', '(borg.trikeshed.userspace.nio.ByteOrder')
                if new_line != line:
                    changed = True
            lines[i] = new_line

        if changed:
            file_path.write_text('\n'.join(lines) + '\n')
        return changed

    # ── Mutable collection factories ─────────────────────────────────────────
    lines = file_path.read_text().splitlines()
    changed = False

    # Map forbidden pattern → (series_type, replacement_template)
    replacers = {
        'mutableListOf': 'LongSeries.build { it += ',
        'mutableMapOf':  'LongLongSeries.build { putAll(mapOf(',
        'mutableSetOf':  'LongSeries.build { it += ',
        'mutableMap':    'LongLongSeries.build { ',
    }

    if pattern not in replacers:
        return False

    replacement_template = replacers[pattern]

    for i, line in enumerate(lines):
        if pattern in line and not line.strip().startswith('//'):
            new_line = line.replace(pattern, replacement_template)
            # Balance builder braces
            openers = new_line.count('{')
            closers = new_line.count('}')
            if openers > closers:
                indent = ' ' * (len(line) - len(line.lstrip()))
                new_line = new_line.rstrip() + ' })'
            lines[i] = new_line
            changed = True

    if changed:
        file_path.write_text('\n'.join(lines) + '\n')
    return changed


def fix_java_nio(root_dir: Path, module_dir: Path, violation: Dict[str, Any]) -> bool:
    """
    Fix java.nio.* violations: swap java.nio imports for userspace equivalents.

    Mapping:
      java.nio.file.Files  → borg.trikeshed.userspace.nio.file.Files
      java.nio.file.Path   → borg.trikeshed.userspace.nio.file.Path
      java.nio.file.Paths  → borg.trikeshed.userspace.nio.file.Paths
      java.nio.file.Files  → borg.trikeshed.userspace.nio.file.Files
      java.nio.ByteBuffer  → borg.trikeshed.userspace.nio.ByteBuffer
      java.nio.ByteOrder   → borg.trikeshed.userspace.nio.ByteOrder
      import java.nio.*    → import borg.trikeshed.userspace.nio.*
      import java.nio.file.* → import borg.trikeshed.userspace.nio.file.*

    Also patches usage sites:
      java.nio.ByteBuffer.wrap   → borg.trikeshed.userspace.nio.ByteBuffer.wrap
      java.nio.ByteOrder.LITTLE_ENDIAN → borg.trikeshed.userspace.nio.ByteOrder.LITTLE_ENDIAN
      java.nio.ByteOrder.BIG_ENDIAN    → borg.trikeshed.userspace.nio.ByteOrder.BIG_ENDIAN

    For ByteBuffer.wrap: the userspace ByteBuffer has wrap() static methods.
    For ByteOrder: direct alias swap — no call-site changes needed.
    """
    evidence = violation.get('evidence', '')
    parts = evidence.rsplit(':', 1)
    if len(parts) != 2:
        return False

    rel_path = parts[0].strip()
    file_path = module_dir / rel_path
    if not file_path.exists():
        return False

    content = file_path.read_text()
    lines = content.splitlines()
    changed = False

    # Import-level replacements (order matters — broader before narrower)
    import_replacements = [
        ('import java.nio.file.Path',    'import borg.trikeshed.userspace.nio.file.Path'),
        ('import java.nio.file.Files',   'import borg.trikeshed.userspace.nio.file.Files'),
        ('import java.nio.file.Paths',   'import borg.trikeshed.userspace.nio.file.Paths'),
        ('import java.nio.file.*',      'import borg.trikeshed.userspace.nio.file.*'),
        ('import java.nio.ByteBuffer',  'import borg.trikeshed.userspace.nio.ByteBuffer'),
        ('import java.nio.ByteOrder',   'import borg.trikeshed.userspace.nio.ByteOrder'),
        ('import java.nio.*',           'import borg.trikeshed.userspace.nio.*'),
    ]

    for i, line in enumerate(lines):
        orig = line
        for old, new in import_replacements:
            if old in line:
                lines[i] = line.replace(old, new)
                changed = True
                break

    if changed:
        file_path.write_text('\n'.join(lines) + '\n')
    return changed


def fix_library_entrypoint(root_dir: Path, module_dir: Path, violation: Dict[str, Any]) -> bool:
    """
    Fix library_entrypoint: comment out ALL 'fun main(' in library source.

    Strategy: comment out all main functions across the module in one pass.
    Recursively finds all files with 'fun main(' matching the FQN or path evidence.

    Evidence formats:
      - FQN: "dreamer.main.main" → find all main.kt in package
      - Path: "src/path/to/File.kt: <line>: fun main(" → direct file path
    """
    evidence = violation.get('evidence', '')
    # evidence: "dreamer.main.main" (FQN) or "src/path/to/File.kt: <line>: fun main("

    rel_path: str
    file_paths: list[Path] = []
    # Check if it looks like a FQN (no slashes, dots for package separator)
    if '/' not in evidence and evidence.count('.') >= 2:
        # FQN like "dreamer.main.main" or "borg.trikeshed.openapi.main"
        # Convert to file path: dreamer.main.main -> dreamer/main/Main.kt
        parts = evidence.rsplit('.', 1)
        func_name = parts[-1]  # "main"
        pkg = parts[0]  # "dreamer.main"
        # Try common main file locations (order matters for disambiguation)
        candidates = [
            module_dir / pkg.replace('.', '/') / 'Main.kt',
            module_dir / pkg.replace('.', '/') / 'main.kt',
            module_dir / 'src' / 'jvmMain' / 'kotlin' / pkg.replace('.', '/') / 'Main.kt',
            module_dir / 'src' / 'commonMain' / 'kotlin' / pkg.replace('.', '/') / 'Main.kt',
            module_dir / 'src' / 'posixMain' / 'kotlin' / pkg.replace('.', '/') / 'Main.kt',
            module_dir / 'src' / 'main' / 'kotlin' / pkg.replace('.', '/') / 'Main.kt',
        ]
        for cand in candidates:
            if cand.exists():
                file_paths.append(cand)
        # Fallback: search all .kt files in module for 'fun main('
        # (regardless of whether candidate path was found)
        for kt in module_dir.rglob('*.kt'):
            rel = kt.relative_to(module_dir)
            if '/test/' in str(rel) or 'Test.kt' in rel.name:
                continue
            text = kt.read_text()
            if 'fun main(' in text:
                file_paths.append(kt)
    else:
        # Path-based: "src/path/to/File.kt: <line>: fun main("
        file_parts = evidence.rsplit(':', 1)
        if len(file_parts) == 2:
            rel_path = file_parts[0]
        else:
            rel_path = evidence.strip()
        fp = module_dir / rel_path
        if fp.exists():
            file_paths.append(fp)

    # Deduplicate and filter already-commented
    seen = set()
    final_paths = []
    for fp in file_paths:
        if fp in seen:
            continue
        seen.add(fp)
        content = fp.read_text()
        if '// RLM: library entrypoint' in content:
            continue  # already processed
        final_paths.append(fp)
    file_paths = final_paths

    if not file_paths:
        return False

    # Process ALL found files
    fixed_count = 0
    for file_path in file_paths:
        lines = file_path.read_text().splitlines()
        modified = False
        for i, line in enumerate(lines):
            if 'fun main(' in line:
                lines[i] = '// RLM: library entrypoint commented out - ' + line
                depth = 0
                started = False
                for j in range(i + 1, min(len(lines), i + 50)):
                    depth += lines[j].count('{') - lines[j].count('}')
                    started = True
                    lines[j] = '// RLM: library entrypoint commented out - ' + lines[j]
                    if started and depth <= 0:
                        break
                modified = True

        if modified:
            file_path.write_text('\n'.join(lines) + '\n')
            fixed_count += 1

    return fixed_count > 0


# Violation kind -> fixer function
FIXERS: Dict[str, Callable[[Path, Path, Dict[str, Any]], bool]] = {
    'context_injection_bypass': fix_context_injection_bypass,
    'reactor_field_hold': fix_reactor_field_hold,
    'missing_context_key': fix_missing_context_key,
    'mutable_stdlib_leak': fix_mutable_stdlib_leak,
    'library_entrypoint': fix_library_entrypoint,
    'java.nio.': fix_java_nio,
}


def auto_squash_loop(root_dir: Path, max_iterations: int = 100):
    """Main auto-squash loop."""
    print(f"Starting auto-squash loop in {root_dir}")
    print("=" * 60)

    for iteration in range(max_iterations):
        print(f"\n[Iteration {iteration + 1}/{max_iterations}]")

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

        fixed_count = 0
        for module in failed_modules:
            violations, module_dir = load_violations(root_dir, module)
            print(f"\n  {module}: {len(violations)} violations")

            for v in violations:
                kind = v.get('kind')
                if not kind:
                    continue
                fixer = FIXERS.get(kind)

                if not fixer:
                    print(f"    - {kind}: NO FIXER (skip)")
                    continue

                try:
                    if fixer(root_dir, module_dir, v):
                        print(f"    ✓ {kind}: FIXED")
                        fixed_count += 1
                    else:
                        print(f"    ✗ {kind}: FAILED")
                except Exception as e:
                    print(f"    ✗ {kind}: ERROR - {e}")

        if fixed_count == 0:
            print("\nNo fixes applied - remaining violations need manual intervention")
            break

        print(f"\nFixed {fixed_count} violations this iteration")
        print("Waiting 5 seconds before re-scan...")
        time.sleep(5)

    print("\n" + "=" * 60)
    print("Auto-squash loop complete")


if __name__ == '__main__':
    import sys

    root = Path.cwd()
    if len(sys.argv) > 1:
        root = Path(sys.argv[1])

    auto_squash_loop(root)