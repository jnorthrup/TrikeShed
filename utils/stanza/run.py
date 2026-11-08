#!/usr/bin/env python3
"""Stanza + UD grammar rules + SUMO typing over a manifest of source texts.

    run.py MANIFEST.json OUT_DIR [--overlay proposals.kif] [--cpu]

The manifest lists sources: {"id", "title", "text" (path to UTF-8 text), "layout" ("lines" for
HTML-derived text, "paragraphs" otherwise), optional "begin"/"end" code-point range, "origin"}.
Outputs: frames.jsonl (one frame plus its typed checks per line), gaps.json (findings grouped by
kind and key, with verbatim examples and UTF-16 spans), summary.json, and the same for the
overlay when one is given. Parses are cached under OUT_DIR/cache by text and model hash.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import sys
import time
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from legalframes import frames as fr  # noqa: E402
from legalframes.infer import Context, Definitions, Typer, aggregate, infer_frame  # noqa: E402
from legalframes.parse import parse_source, parser_metadata, pipeline  # noqa: E402
from legalframes.sumo import Lexicon, load_sumo  # noqa: E402
from legalframes.text import line_blocks, paragraph_blocks  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))


def default_paths():
    sumo_root = os.environ.get('TRIKESHED_SUMO_FULL', os.path.join(REPO, 'build/generated/sumo-full/resources/sumo/full'))
    noun = os.environ.get('TRIKESHED_WN_NOUN', os.path.join(REPO, 'build/generated/sumo/resources/sumo/WordNetMappings/WordNetMappings30-noun.txt'))
    extra = os.environ.get('TRIKESHED_WN_EXTRA', os.path.expanduser('~/.local/share/TrikeShed/sumo-wordnet'))
    nltk_dir = os.environ.get('TRIKESHED_NLTK_DATA', os.path.expanduser('~/.local/share/TrikeShed/stanza/nltk_data'))
    return sumo_root, {'n': noun, 'v': os.path.join(extra, 'WordNetMappings30-verb.txt'),
                       'a': os.path.join(extra, 'WordNetMappings30-adj.txt')}, nltk_dir


def sha(path):
    return hashlib.sha256(open(path, 'rb').read()).hexdigest()


def run_inference(sources_frames, sumo, lex):
    typer = Typer(sumo, lex)
    definitions = Definitions()
    for source, fs in sources_frames:
        definitions.add(source, fs)
    results = []
    for source, fs in sources_frames:
        ctx = Context(fs, definitions)
        for f in fs:
            results.append((source, f, infer_frame(f, typer, ctx)))
    return results


def write_outputs(out_dir, suffix, results, meta):
    groups = aggregate(results)
    gaps = []
    for (kind, key), g in sorted(groups.items(), key=lambda kv: (-kv[1]['count'], kv[0])):
        gaps.append({'kind': kind, 'key': key, 'count': g['count'], 'sources': dict(g['sources']),
                     'examples': g['examples'], 'details': g['details']})
    with open(os.path.join(out_dir, f'gaps{suffix}.json'), 'w') as f:
        json.dump(gaps, f, ensure_ascii=False, indent=1, default=str)
    with open(os.path.join(out_dir, f'frames{suffix}.jsonl'), 'w') as f:
        for source, frame, inf in results:
            f.write(json.dumps({'source': source['id'], 'frame': frame, 'inference': inf}, ensure_ascii=False, default=str) + '\n')
    checks = Counter()
    for _, frame, inf in results:
        for c in inf['checks']:
            if 'check' in c:
                checks[(c['check'].get('relation') or c['check']['required'], c['role'], c['check']['status'])] += 1
    summary = {**meta, 'frames': Counter(fr_['rule'] for _, fr_, _ in results),
               'findings': Counter(k for (k, _), g in groups.items() for _ in range(g['count'])),
               'checks': {' / '.join(k): v for k, v in sorted(checks.items())}}
    with open(os.path.join(out_dir, f'summary{suffix}.json'), 'w') as f:
        json.dump(summary, f, ensure_ascii=False, indent=1, default=str)
    return gaps, summary


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('manifest')
    ap.add_argument('out')
    ap.add_argument('--overlay')
    ap.add_argument('--retract', action='append', default=[],
                    help='drop one taxonomy edge for the overlay run, e.g. "subclass FinancialAccount Account"')
    ap.add_argument('--cpu', action='store_true')
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    cache = os.path.join(a.out, 'cache')
    manifest = json.load(open(a.manifest))
    sumo_root, maps, nltk_dir = default_paths()

    t0 = time.time()
    nlp = pipeline(use_gpu=not a.cpu)
    meta_parser = parser_metadata(nlp)
    sources_frames = []
    parse_stats = []
    for src in manifest['sources']:
        text = open(src['text'], encoding='utf-8').read()
        b, e = src.get('begin', 0), src.get('end') or len(text)
        blocks = line_blocks(text, b, e) if src.get('layout') == 'lines' else paragraph_blocks(text, b, e, src.get('section'))
        source = {k: v for k, v in src.items() if k not in ('text',)}
        source['textSha256'] = sha(src['text'])
        rec = parse_source(nlp, meta_parser, source, text, blocks, cache)
        fs = []
        prev = None
        for s in rec['sentences']:
            fs.extend(fr.leading_alias(s, prev, text))
            fs.extend(fr.frames(s, text))
            prev = s
        sources_frames.append((source, fs))
        words = sum(len(s['words']) for s in rec['sentences'])
        parse_stats.append({'id': src['id'], 'blocks': len(blocks), 'sentences': len(rec['sentences']), 'words': words,
                            'parseSeconds': rec['seconds'], 'frames': len(fs)})
        print(f"{src['id']}: {len(blocks)} blocks, {len(rec['sentences'])} sentences, {words} words, "
              f"parse {rec['seconds']}s, {len(fs)} frames", flush=True)

    sumo = load_sumo(sumo_root, cache)
    lex = Lexicon(maps, nltk_dir)
    meta = {'parser': meta_parser, 'sources': parse_stats,
            'sumo': {'root': sumo_root, 'files': len(sumo.files), 'terms': len(sumo.known),
                     'pins': open(os.path.join(sumo_root, 'corpus.pins')).read().split('\n')[0:12] if os.path.isfile(os.path.join(sumo_root, 'corpus.pins')) else None},
            'wordnet': lex.sources, 'generativeModels': 0}
    results = run_inference(sources_frames, sumo, lex)
    gaps, summary = write_outputs(a.out, '', results, meta)
    print(f'inference: {len(results)} frames, {len(gaps)} gap groups, {time.time() - t0:.1f}s', flush=True)

    if a.overlay:
        extended = copy.deepcopy(sumo)
        n = extended.extend(open(a.overlay).read(), os.path.basename(a.overlay))
        retracted = [r for r in a.retract if extended.retract(r)]
        results2 = run_inference(sources_frames, extended, lex)
        meta2 = {**meta, 'overlay': {'file': a.overlay, 'sha256': sha(a.overlay), 'forms': n, 'retracted': retracted}}
        gaps2, _ = write_outputs(a.out, '.overlay', results2, meta2)
        print(f'overlay: {n} forms, {len(gaps2)} gap groups', flush=True)


if __name__ == '__main__':
    main()
