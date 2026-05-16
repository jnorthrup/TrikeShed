import re, os
from collections import Counter

forbidden = re.compile(r'\b(MutableList|MutableMap|MutableSet|ArrayList|HashMap|HashSet)\b')
entrypoint = re.compile(r'^\s*fun\s+main\s*\(', re.MULTILINE)
blocked = re.compile(r'\bjava\.util\.(List|Map|Set)\b')

root = '/Users/jim/work/TrikeShed/libs'
violations = []
entrypoints = []

for dirpath, dirnames, filenames in os.walk(root):
    dirnames[:] = [d for d in dirnames if d != 'build']
    for fn in filenames:
        if not fn.endswith('.kt'):
            continue
        fp = os.path.join(dirpath, fn)
        rel = os.path.relpath(fp, '/Users/jim/work/TrikeShed')
        with open(fp) as f:
            text = f.read()
            lines = text.splitlines()

        for m in forbidden.finditer(text):
            line_no = text[:m.start()].count('\n') + 1
            parts = rel.split('/')
            module = parts[1] if len(parts) > 1 else 'unknown'
            violations.append({
                'module': module,
                'file': rel,
                'line': line_no,
                'match': m.group(0),
                'context': lines[line_no-1].strip() if line_no <= len(lines) else '?'
            })

        for m in blocked.finditer(text):
            line_no = text[:m.start()].count('\n') + 1
            parts = rel.split('/')
            module = parts[1] if len(parts) > 1 else 'unknown'
            violations.append({
                'module': module,
                'file': rel,
                'line': line_no,
                'match': m.group(0),
                'context': lines[line_no-1].strip() if line_no <= len(lines) else '?'
            })

        if '/test/' not in rel and entrypoint.search(text):
            parts = rel.split('/')
            module = parts[1] if len(parts) > 1 else 'unknown'
            for m in entrypoint.finditer(text):
                line_no = text[:m.start()].count('\n') + 1
                entrypoints.append({
                    'module': module,
                    'file': rel,
                    'line': line_no,
                    'context': lines[line_no-1].strip() if line_no <= len(lines) else '?'
                })

mod_counts = Counter(v['module'] for v in violations)
ep_counts = Counter(v['module'] for v in entrypoints)

print('=== MUTABLE STDLIB LEAKS BY MODULE ===')
for mod, cnt in mod_counts.most_common():
    print(f'  {mod}: {cnt}')
print(f'  TOTAL: {sum(mod_counts.values())}')

print()
print('=== LIBRARY ENTRYPOINTS BY MODULE ===')
for mod, cnt in ep_counts.most_common():
    print(f'  {mod}: {cnt}')
print(f'  TOTAL: {sum(ep_counts.values())}')

print()
print('=== ALL MUTABLE LEAK DETAILS ===')
for v in sorted(violations, key=lambda x: (x['module'], x['file'], x['line'])):
    print(f"  {v['module']:20s} {v['file']:70s} :{v['line']:4d}  {v['match']:18s}  # {v['context'][:100]}")

print()
print('=== ALL ENTRYPOINT DETAILS ===')
for e in sorted(entrypoints, key=lambda x: (x['module'], x['file'], x['line'])):
    print(f"  {e['module']:20s} {e['file']:70s} :{e['line']:4d}  # {e['context'][:100]}")