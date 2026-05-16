#!/usr/bin/env python3
"""
Test auto_squash on a single module (htx-client).
"""
import sys
sys.path.insert(0, '/Users/jim/work/TrikeShed/scripts')

from pathlib import Path
from auto_squash_violations import *

root = Path('/Users/jim/work/TrikeShed')

# Load htx-client violations
violations, module_dir = load_violations(root, 'htx-client')
print(f"htx-client violations: {len(violations)}")
print(f"module_dir: {module_dir}")

for v in violations:
    kind = v.get('kind')
    if not kind:
        continue
    print(f"\n{kind}:")
    print(f"  {v.get('evidence', '')}")
    
    # Try to fix
    fixer = FIXERS.get(kind)
    if fixer:
        try:
            result = fixer(root, module_dir, v)
            print(f"  Result: {result}")
        except Exception as e:
            print(f"  Error: {e}")
            import traceback
            traceback.print_exc()
