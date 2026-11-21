#!/usr/bin/env python3
"""What 4 bits cost on this task: the q4 bundle against the float32 checkpoint it was made from.

    fidelity.py --bundle BUNDLE_DIR --source SNAPSHOT_DIR --masks masks.jsonl --out fidelity.json

Each line of masks.jsonl is a masked-cue request ({"text", "begin", "end", ...}, as utils/stanza/operators.py
writes them). Both models fill every mask; the report compares them position by position: whether the best
whole word agrees, whether the preferred cue class agrees, how far the cue's probability moved, and the
KL divergence of the full vocabulary distribution. It measures agreement with the float32 model on these
masks; it says nothing about either model being right.

The float32 checkpoint is needed only here. The curation lane runs on the bundle alone.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from q4bert.model import Bert, Refused, fill, load_float, open_bundle, read_json  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--bundle', required=True)
    ap.add_argument('--source', required=True)
    ap.add_argument('--masks', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--classes', help='JSON file {class: [words]}; default: the cueClasses of the masks, if any')
    ap.add_argument('--threads', type=int, default=4)
    a = ap.parse_args()
    with open(a.masks, encoding='utf-8') as f:
        masks = [json.loads(line) for line in f if line.strip()]
    classes = read_json(a.classes) if a.classes else {}
    q4, tok, identity, config = open_bundle(os.path.expanduser(a.bundle), a.threads)
    tensors, _ = load_float(os.path.realpath(os.path.expanduser(a.source)))
    f32 = Bert(tensors, config, a.threads)
    limit = config['max_position_embeddings']

    rows, seconds = [], {'q4': 0.0, 'f32': 0.0}
    for m in masks:
        try:
            t0 = time.time()
            a4 = fill(q4, tok, m['text'], m['begin'], m['end'], classes, 5, limit, with_distribution=True)
            t1 = time.time()
            a32 = fill(f32, tok, m['text'], m['begin'], m['end'], classes, 5, limit, with_distribution=True)
            seconds['q4'] += t1 - t0
            seconds['f32'] += time.time() - t1
        except Refused as e:
            rows.append({'operator': m.get('operator'), 'status': 'refused', 'reason': str(e)})
            continue
        p4, p32 = a4.pop('_p'), a32.pop('_p')
        kl = float(np.sum(p32 * (np.log(np.maximum(p32, 1e-300)) - np.log(np.maximum(p4, 1e-300)))))
        rows.append({'operator': m.get('operator'), 'status': 'ok', 'cue': a4['cue'], 'tokens': a4['tokens'],
                     'top1': {'q4': a4['top'][0][0], 'f32': a32['top'][0][0]}, 'sameTop1': a4['top'][0][0] == a32['top'][0][0],
                     'p': {'q4': a4['p'], 'f32': a32['p']}, 'rank': {'q4': a4['rank'], 'f32': a32['rank']},
                     'preferredClass': {'q4': a4['preferredClass'], 'f32': a32['preferredClass']},
                     'samePreferredClass': a4['preferredClass'] == a32['preferredClass'],
                     'concordant': {'q4': a4['concordant'], 'f32': a32['concordant']}, 'kl': round(kl, 6)})
    ok = [r for r in rows if r['status'] == 'ok']
    with_p = [r for r in ok if r['p']['q4'] is not None]
    deltas = [abs(r['p']['q4'] - r['p']['f32']) for r in with_p]
    kls = [r['kl'] for r in ok]

    def rate(key):
        return round(sum(1 for r in ok if r[key]) / len(ok), 4) if ok else None

    def concordance(which):
        judged = [r for r in ok if r['concordant'][which] is not None]
        return round(sum(1 for r in judged if r['concordant'][which]) / len(judged), 4) if judged else None

    report = {'bundle': identity, 'reference': 'float32 checkpoint the bundle was quantized from',
              'masks': len(masks), 'compared': len(ok), 'refused': len(rows) - len(ok),
              'top1Agreement': rate('sameTop1'), 'preferredClassAgreement': rate('samePreferredClass'),
              'cueRank1': {w: round(sum(1 for r in with_p if r['rank'][w] == 1) / len(with_p), 4) if with_p else None for w in ('q4', 'f32')},
              'cueClassConcordance': {'q4': concordance('q4'), 'f32': concordance('f32')},
              'cueProbability': {'meanQ4': round(float(np.mean([r['p']['q4'] for r in with_p])), 6) if with_p else None,
                                 'meanF32': round(float(np.mean([r['p']['f32'] for r in with_p])), 6) if with_p else None,
                                 'meanAbsDelta': round(float(np.mean(deltas)), 6) if deltas else None,
                                 'maxAbsDelta': round(float(np.max(deltas)), 6) if deltas else None},
              'kl': {'mean': round(float(np.mean(kls)), 6) if kls else None,
                     'median': round(float(np.median(kls)), 6) if kls else None,
                     'max': round(float(np.max(kls)), 6) if kls else None, 'direction': 'KL(float32 || q4), nats'},
              'secondsPerMask': {k: round(v / len(ok), 4) if ok else None for k, v in seconds.items()},
              'threads': a.threads, 'scope': 'agreement with the float32 model on these masks; not accuracy',
              'rows': rows}
    with open(a.out, 'w', encoding='utf-8') as f:
        json.dump(report, f, indent=1, ensure_ascii=False)
    print(json.dumps({k: v for k, v in report.items() if k != 'rows'}, indent=1))
    return 0


if __name__ == '__main__':
    sys.exit(main())
