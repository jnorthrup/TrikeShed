#!/usr/bin/env python3
"""Render OUT_DIR/report.md from a run: sources, findings pinned vs overlay, evidence, traces.

    report.py OUT_DIR [--traces traces.json]

traces.json: [{"label": "...", "source": "coinbase-ua", "match": "regex over the sentence quote"}].
Every quote is copied from frames.jsonl, which cut it from the source by offset.
"""
import argparse
import json
import os
import re
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

MEANING = {
    'bearer-type': 'The bearer of a duty or right does not type as SUMO requires (holdsObligation and holdsRight take a CognitiveAgent).',
    'grantee-type': 'The party granted a right does not type as confersRight requires (arg 2 CognitiveAgent).',
    'assenting-party-type': 'The party who agrees, represents or acknowledges does not type as confersObligation requires.',
    'role-holder-type': 'A role noun (custodian, intermediary) is a SUMO SocialRole/Position attribute, documented for CognitiveAgents, held here by an organization.',
    'possessed-type': 'The thing owned, held or transferred does not type as possesses requires (arg 2 Object).',
    'incoherent-class': 'The selected SUMO class has disjoint ancestors, so nothing can be an instance of it.',
    'relation-gap': 'The clause needs a relation SUMO does not have.',
    'constitutive-rule': 'A modal norm about a non-agent: it fixes legal effect or status rather than binding anyone; SUMO has no counts-as relation.',
    'lexical-gap': 'No WordNet 3.0 entry and no SUMO termFormat for the term as written; typing fell back to its head word.',
    'defined-term-gap': 'A term the document defines has no WordNet entry.',
    'event-sense-gap': 'No WordNet sense of the verb maps to the SUMO process class the clause needs.',
    'event-first-sense-misfit': 'The verb’s first WordNet sense maps elsewhere; a later sense fits.',
    'noun-sense-gap': 'Every WordNet sense of the noun belongs to another register (custody of a child, a janitor).',
    'act-without-process-class': 'The granting verb maps to an attribute (Permission), not a process class.',
    'bearer-unexpressed': 'Agentless passive or missing subject: the clause does not say who bears the norm.',
    'grantee-unexpressed': 'The authorization names an act, not the party authorized.',
    'status-disjoint': 'A copular claim joins two classes SUMO declares disjoint.',
}
ORDER = ['bearer-type', 'grantee-type', 'assenting-party-type', 'role-holder-type', 'incoherent-class', 'possessed-type',
         'relation-gap', 'constitutive-rule', 'event-sense-gap', 'noun-sense-gap', 'act-without-process-class',
         'lexical-gap', 'defined-term-gap', 'status-disjoint', 'event-first-sense-misfit', 'bearer-unexpressed',
         'grantee-unexpressed']


def esc(t):
    return re.sub(r'\s+', ' ', t).replace('|', '\\|')


def load(out, suffix=''):
    with open(os.path.join(out, f'summary{suffix}.json')) as f:
        summary = json.load(f)
    with open(os.path.join(out, f'gaps{suffix}.json')) as f:
        gaps = json.load(f)
    rows = [json.loads(l) for l in open(os.path.join(out, f'frames{suffix}.jsonl'))]
    return summary, gaps, rows


def trace_lines(r):
    f, inf = r['frame'], r['inference']
    reading = inf.get('reading') or f.get('reading') or ''
    out = [f"*{f['rule']}*{(' · ' + reading) if reading else ''}{(' · ' + inf['formula']) if inf.get('formula') else ''}"]
    for c in inf['checks']:
        if 'check' in c and c['check'].get('best'):
            b = c['check']['best']
            out.append(f"  - {c['role']} “{esc(c['node']['quote'])}” via `{esc(c['typing']['via'])}` → **{b['term']}**"
                       f" ({b.get('selectedBy', '')}): {c['check']['status']} for `{c['check'].get('relation', '')}` arg"
                       f" {c['check'].get('arg', '')} = {c['check']['required']}"
                       + (f" (axiom {c['check']['axiom']})" if c['check'].get('axiom') else '')
                       + (f"; disjoint at {b['witness']['where']} ({' ⊥ '.join(b['witness']['between'])})" if b.get('witness') else ''))
        elif c.get('process'):
            p = c['process']
            first = p['first'] or {}
            out.append(f"  - {c['role']} `{p['lemma']}`: {p['status']}; first sense {first.get('synset')} → {first.get('term')}"
                       + (f"; match {p['match']['synset']} → {p['match']['term']}" if p.get('match') else ''))
    for fd in inf['findings']:
        out.append(f"  - finding **{fd['kind']}** `{fd['key']}`")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('out')
    ap.add_argument('--traces')
    a = ap.parse_args()
    summary, gaps, rows = load(a.out)
    overlay = os.path.isfile(os.path.join(a.out, 'summary.overlay.json'))
    summary2, rows2 = {'findings': {}, 'overlay': {'retracted': []}}, []
    if overlay:
        summary2, gaps2, rows2 = load(a.out, '.overlay')
    L = ['# Stanza + grammar rules + SUMO: legal frames and taxonomy gaps', '']
    p = summary['parser']
    L += [f"Parser: Stanza {p['version']} ({p['processors']}), torch {p['torch']}; generative models: {summary['generativeModels']}.",
          f"SUMO: {summary['sumo']['files']} KIF files, {summary['sumo']['terms']} terms ({esc(' '.join(x for x in (summary['sumo']['pins'] or []) if 'ref' in x))}).",
          'WordNet 3.0 → SUMO mappings: ' + ', '.join(f"{v['file']} {v['sha256'][:12]}" for v in summary['wordnet'].values()) + '.', '',
          '| source | sentences | words | frames | parse s |', '|---|---:|---:|---:|---:|']
    for s in summary['sources']:
        L.append(f"| {s['id']} | {s['sentences']} | {s['words']} | {s['frames']} | {s['parseSeconds']} |")
    L += ['', '## Findings', '', '| finding | pinned SUMO | + overlay' + (f" (retracted: {', '.join(summary2['overlay']['retracted'])})" if overlay else '') + ' | meaning |', '|---|---:|---:|---|']
    for k in ORDER:
        n = summary['findings'].get(k, 0)
        if n or (overlay and summary2['findings'].get(k)):
            L.append(f"| {k} | {n} | {summary2['findings'].get(k, 0) if overlay else ''} | {MEANING.get(k, '')} |")
    by = defaultdict(list)
    for g in gaps:
        by[g['kind']].append(g)
    for k in ORDER:
        gs = sorted(by.get(k, []), key=lambda g: -g['count'])
        if not gs:
            continue
        L += ['', f'### {k} ({sum(g["count"] for g in gs)} in {len(gs)} keys)', '', MEANING.get(k, ''), '']
        for g in gs[:12 if k != 'lexical-gap' else 0]:
            ex = g['examples'][0]
            L.append(f"- **{g['key']}** ×{g['count']} ({', '.join(f'{s} {n}' for s, n in g['sources'].items())}): "
                     f"“{esc(ex['quote'])[:260]}” — {ex['source']}{(' ' + ex['section']) if ex.get('section') else ''}, "
                     f"UTF-16 [{ex['u16Begin']}, {ex['u16End']})" + (f"; span “{esc(ex['span'])}”" if ex.get('span') else ''))
        if k == 'lexical-gap':
            L.append(', '.join(f"`{g['key']}` {g['count']}" for g in gs[:150]))
    if a.traces:
        L += ['', '## Traces', '']
        for t in json.load(open(a.traces)):
            pat = re.compile(t['match'])
            hits = [r for r in rows if r['source'] == t['source'] and pat.search(r['frame']['quote'])]
            if not hits:
                L += [f"### {t['label']}", '', '(no frame matched)', '']
                continue
            q = hits[0]['frame']
            L += [f"### {t['label']}", '', f"> {esc(q['quote'])}", '',
                  f"{t['source']}{(' ' + q['section']) if q.get('section') else ''}, UTF-16 [{q['u16Begin']}, {q['u16End']})", '']
            for r in hits[:6]:
                L += trace_lines(r)
            if overlay:
                hits2 = [r for r in rows2 if r['source'] == t['source'] and pat.search(r['frame']['quote'])]
                after = sorted({f"{fd['kind']} `{fd['key']}`" for r in hits2[:6] for fd in r['inference']['findings']})
                L += ['', '  With the overlay: ' + ('; '.join(after) if after else 'no findings') + '.']
            L.append('')
    with open(os.path.join(a.out, 'report.md'), 'w') as f:
        f.write('\n'.join(L) + '\n')
    print(os.path.join(a.out, 'report.md'), len(L), 'lines')


if __name__ == '__main__':
    main()
