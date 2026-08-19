#!/usr/bin/env python3
"""Project doc/concepts.md (merged into README.md) into Narsese judgements and
compare information metrics. Methodology mirrors borg.trikeshed.narsese types:
NarseseBag (angular = FNV-1a of subject+predicate), EvidenceCoord/TruthCoord
(w+ = confidence*1000, f = w+/(w++w-), c = w/(w+k), k=1)."""
import re
import math
import gzip
import collections

text = open('README.md', 'rb').read().decode('utf-8')


def H(items):
    if not items:
        return 0.0
    f = collections.Counter(items)
    n = len(items)
    return -sum((c / n) * math.log2(c / n) for c in f.values())


def fnv1a(s):
    h = -3750763034362895579 & 0xFFFFFFFFFFFFFFFF
    for c in s:
        h ^= ord(c)
        h = (h * 1099511628211) & 0xFFFFFFFFFFFFFFFF
    return h - (1 << 64) if h >= (1 << 63) else h


def hamming(a, b):
    return bin((a ^ b) & 0xFFFFFFFFFFFFFFFF).count('1')


def atom(s):
    s = re.sub(r'[^0-9A-Za-z]', ' ', s)
    s = '_'.join(s.split())
    return s[:40].lower() or 'x'


lines = text.splitlines()
stack = {}
judgements = []
cur_table_hdr = None
cur_heading = 'concepts'
in_code = False
code_lines = 0

for ln in lines:
    if ln.lstrip().startswith('```'):
        in_code = not in_code
        continue
    if in_code:
        code_lines += 1
        continue
    m = re.match(r'^(#{1,6})\s+(.*)$', ln)
    if m:
        lvl, title = len(m.group(1)), m.group(2).strip()
        a = atom(title)
        pl = max([l for l in stack if l < lvl], default=None)
        parent = stack[pl] if pl is not None else 'trikeshed_concept_map'
        stack = {l: v for l, v in stack.items() if l < lvl}
        stack[lvl] = a
        judgements.append((a, parent, 0.90, 'heading'))
        cur_heading = a
        cur_table_hdr = None
        continue
    m = re.match(r'^\|(.+)\|\s*$', ln)
    if m:
        cells = [c.strip() for c in m.group(1).split('|')]
        if re.match(r'^[-\s:]+$', ''.join(cells)):
            continue
        if cur_table_hdr is None:
            cur_table_hdr = cells
            continue
        if cells == cur_table_hdr:
            continue
        row_atom = atom(cells[0]) if cells and cells[0] else 'row'
        for hcell, cell in zip(cur_table_hdr[1:], cells[1:]):
            if not cell:
                continue
            judgements.append((row_atom, atom(hcell + '_' + cell), 0.80, 'table'))
        continue
    m = re.match(r'^\s*[-*]\s+(.*)$', ln)
    if m:
        body = re.sub(r'[*_`]', '', m.group(1)).strip()
        mm = re.split(r'\s+\u2014\s+|:\s+', body, maxsplit=1)
        if len(mm) == 2 and 0 < len(mm[0]) < 32 and 0 < len(mm[1]) < 44:
            judgements.append((atom(mm[0]), atom(mm[1]), 0.60, 'bullet-split'))
        else:
            judgements.append((atom(body[:36]), cur_heading, 0.47, 'bullet-heading'))
        continue

nal_min = ''.join('<%s --> %s>.\n' % (s, p) for s, p, c, k in judgements)
nal_full = ''.join('<%s --> %s>. %%1.00;%.2f%%\n' % (s, p, c) for s, p, c, k in judgements)


def metrics(name, s):
    b = s.encode()
    gz = len(gzip.compress(b))
    vocab = len(set(re.findall(r'\w+', s)))
    print("%s: bytes=%d stmts=%d H=%.3fb/B gzip=%d x%.2f vocab=%d"
          % (name, len(b), len(judgements), H(b), gz, len(b) / gz, vocab))


print("=== NARSESE PROJECTION (doc/concepts.md content -> NAL judgements) ===")
metrics('NAL-min      ', nal_min)
metrics('NAL-full     ', nal_full)
print("kinds: %s" % dict(collections.Counter(k for *_, k in judgements)))

headings_n = len(re.findall(r'^#{1,6}\s+', text, re.M))
bullets_n = len(re.findall(r'^\s*[-*]\s+\S', text, re.M))
trows_n = len([r for r in re.findall(r'^\|.*\|$', text, re.M)
               if not re.match(r'^\|[-\s:|]+\|?$', r.strip())])
print("markdown: headings=%d bullets=%d table_rows=%d total=%d"
      % (headings_n, bullets_n, trows_n, headings_n + bullets_n + trows_n))
print("code lines dropped: %d" % code_lines)

bag = {}
for s, p, c, kind in judgements:
    a = fnv1a(s + p)
    bag[a] = (s, p, c, kind)
keys = list(bag)
dupes = len(judgements) - len(keys)
print()
print("=== manifold (NarseseBag) ===")
print("signals=%d distinct_angular=%d revise-merges=%d"
      % (len(judgements), len(keys), dupes))
if keys:
    pairs = [hamming(x, y) for i, x in enumerate(keys) for y in keys[i + 1:]]
    print("min pairwise hamming=%d/64  avg popcount=%.1f"
          % (min(pairs),
             sum(bin(k & 0xFFFFFFFFFFFFFFFF).count('1') for k in keys) / len(keys)))
    k0 = keys[0]
    near = [k for k in keys if hamming(k0, k) <= 8]
    tag = 'clustered' if len(near) > 4 else 'sparse/near-orthogonal'
    print("recallNear(k0, 8): %d hits -> %s" % (len(near), tag))
print("expectation by kind: heading c=.90->E=.95 | table c=.80->E=.90 | "
      "split c=.60->E=.80 | heading-bullet c=.47->E=.735")
print("evidence per kind (EvidenceCoord): heading w+=900 | table w+=800 | "
      "bullet-split w+=600 | bullet-heading w+=470")

open('nal_projection.txt', 'w').write(nal_full)
print()
print("sample heading judgements:")
for s, p, c, k in [j for j in judgements if j[3] == 'heading'][:4]:
    print("  <%s --> %s>. %%1.00;%.2f%%" % (s, p, c))
print("sample table judgements:")
for s, p, c, k in [j for j in judgements if j[3] == 'table'][:3]:
    print("  <%s --> %s>. %%1.00;%.2f%%" % (s, p, c))
print("sample bullet judgements:")
for s, p, c, k in [j for j in judgements if j[3].startswith('bullet')][:3]:
    print("  <%s --> %s>. %%1.00;%.2f%%" % (s, p, c))
