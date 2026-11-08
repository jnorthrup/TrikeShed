#!/usr/bin/env python3
"""Print frames.jsonl compactly: one line per frame, its readings, roles and findings.

    show.py OUT_DIR [--source ID] [--rule RULE] [--findings-only]
"""
import argparse
import json
import os


def roles(f):
    q = lambda xs: [x['quote'] for x in xs]
    r = f['rule']
    if r == 'modal-action':
        return f"{f['modal']} subj={q(f['subjects'])} obj={q(f['objects'])}{' passive' if f['passive'] else ''}"
    if r in ('grant', 'assent'):
        return (f"subj={q(f['subjects'])} obj={q(f['objects'])} to={(f.get('complement') or {}).get('full', '')[:70]!r}"
                f" that={(f.get('content') or {}).get('quote', '')[:70]!r}")
    if r == 'copular':
        return f"{f['subjectFull'][:60]!r} IS {f['predicate']['quote']!r} {[(o['case'], o['node']['quote']) for o in f['obliques']]}"
    if r == 'norm-predicate':
        return f"{f['predicate']} subj={q(f['subjects'])} to={(f.get('complement') or {}).get('full', '')[:70]!r}"
    if r == 'property-event':
        return f"{f['verb']} agent={q(f['agent'])} theme={q(f['theme'])} {[(o['case'], o['node']['quote']) for o in f['obliques']][:4]}"
    if r == 'property-relation':
        return f"{f['noun']} args={[(a['case'], a['node']['quote']) for a in f['arguments']]} holders={q(f['holders'])}"
    if r == 'definition':
        return f"{f['term']!r} genus={[g['quote'] for g in f['genus']]}"
    if r == 'alias':
        return f"{f['term']!r} = {f['aliases']}"
    if r == 'treat-as':
        return f"{f['theme']['quote']!r} AS {f['asClass']['quote']!r}"
    return ''


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('out')
    ap.add_argument('--source')
    ap.add_argument('--rule')
    ap.add_argument('--findings-only', action='store_true')
    ap.add_argument('--file', default='frames.jsonl')
    a = ap.parse_args()
    for line in open(os.path.join(a.out, a.file)):
        r = json.loads(line)
        f, inf = r['frame'], r['inference']
        if (a.source and r['source'] != a.source) or (a.rule and f['rule'] != a.rule):
            continue
        if a.findings_only and not inf['findings']:
            continue
        reading = inf.get('reading') or f.get('reading') or ''
        print(f"[{r['source']} {f.get('section') or ''}] {f['rule']} {reading} @{f['headNode']['text']}: {roles(f)}")
        for c in inf['checks']:
            if 'check' in c and c['check'].get('best'):
                b = c['check']['best']
                print(f"     · {c['role']} {c['node']['quote']!r} via {c['typing']['via'][:80]} → {b['term']} "
                      f"[{c['check']['status']} vs {c['check'].get('relation', '')}#{c['check'].get('arg', '')} {c['check']['required']}]")
        for fd in inf['findings']:
            print(f"     -> {fd['kind']}: {fd['key']} {fd.get('status', '')} {str(fd.get('detail', ''))[:110]}")


if __name__ == '__main__':
    main()
