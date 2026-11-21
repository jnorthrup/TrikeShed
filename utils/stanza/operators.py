#!/usr/bin/env python3
"""Operator logic over a finished run.py pass: constructions in the parse tree, weighed two ways.

    operators.py MANIFEST.json OUT_DIR

Reads the parse run.py cached under OUT_DIR/cache (no parser is loaded and nothing is re-parsed), rebuilds
the frames and their SUMO inference exactly as run.py does, and writes

    operators.jsonl             one operator candidate per line: construction, cue, operands, SUMO checks,
                                deterministic and ontological weight, what stays unresolved
    logic.jsonl                 one composed form per sentence
    masks.jsonl                 one masked-cue request per operator whose cue word has a lexical class, for a
                                masked-language-model lane; that lane decides nothing here
    operators.summary.json      counts, weight means, parser and SUMO identity, the weight levels

This step loads no model: its input is the parser's cached tree. The same inputs give the same bytes.
"""
from __future__ import annotations

import glob
import hashlib
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from legalframes import frames as fr  # noqa: E402
from legalframes import operators as ops  # noqa: E402
from legalframes.infer import Context, Definitions, Typer, infer_frame  # noqa: E402
from legalframes.sumo import Lexicon, load_sumo  # noqa: E402
from legalframes.text import utf16_offsets  # noqa: E402
from run import default_paths, sha  # noqa: E402

SCHEMA = 'trikeshed.stanza-operators.v1'


def cached_parse(cache: str, source_id: str, text_sha: str) -> dict:
    """The parse of this exact text. A cache from another text, or two parses of this one, is an error."""
    hits = []
    for path in sorted(glob.glob(os.path.join(cache, f'{glob.escape(source_id)}.*.parse.json'))):
        with open(path, encoding='utf-8') as f:
            rec = json.load(f)
        if rec['source'].get('textSha256') == text_sha:
            hits.append((path, rec))
    if len(hits) != 1:
        raise SystemExit(f'{source_id}: expected one cached parse of this text under {cache}, found {len(hits)}; '
                         f'run run.py on this manifest first')
    return hits[0][1]


def dump(row) -> str:
    return json.dumps(row, ensure_ascii=False, sort_keys=True, default=str)


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    manifest_path, out = sys.argv[1], sys.argv[2]
    cache = os.path.join(out, 'cache')
    manifest = json.load(open(manifest_path, encoding='utf-8'))
    sumo_root, maps, nltk_dir = default_paths()
    t0 = time.time()

    loaded = []
    for src in manifest['sources']:
        text = open(src['text'], encoding='utf-8').read()
        rec = cached_parse(cache, src['id'], sha(src['text']))
        source = {k: v for k, v in src.items() if k != 'text'}
        source['textSha256'] = rec['source']['textSha256']
        fs, prev = [], None
        for s in rec['sentences']:
            fs.extend(fr.leading_alias(s, prev, text))
            fs.extend(fr.frames(s, text))
            prev = s
        loaded.append((source, text, rec, fs))

    sumo = load_sumo(sumo_root, cache)
    lex = Lexicon(maps, nltk_dir)
    typer = Typer(sumo, lex)
    definitions = Definitions()
    for source, _, _, fs in loaded:
        definitions.add(source, fs)

    all_ops, logic_rows, mask_rows, per_source = [], [], [], []
    for source, text, rec, fs in loaded:
        ctx = Context(fs, definitions)
        u16 = utf16_offsets(text)
        by_sentence = {}
        for f in fs:
            if f['rule'] in ('modal-action', 'norm-predicate'):
                by_sentence.setdefault(f['sentence'], []).append((f, infer_frame(f, typer, ctx)))
        n = 0
        for s in rec['sentences']:
            found, logic = ops.extract(s, text, typer, ctx, by_sentence.get(s['index'], ()), u16)
            for op in found:
                op['source'] = source['id']
                assert text[op['cue']['begin']:op['cue']['end']] == op['cue']['quote']
                for o in op['operands']:
                    assert text[o['begin']:o['end']] == o['quote']
            all_ops.extend(found)
            logic_rows.append({'source': source['id'], **logic, 'u16Begin': s['u16Begin'], 'u16End': s['u16End']})
            mask_rows.extend({'source': source['id'], **m} for m in ops.masks(found, s))
            n += len(found)
        per_source.append({'id': source['id'], 'sentences': len(rec['sentences']), 'operators': n,
                           'parties': {k: v['name'] for k, v in sorted(ctx.parties.items())},
                           'textSha256': source['textSha256']})
        print(f"{source['id']}: {len(rec['sentences'])} sentences, {n} operators", flush=True)

    body = '\n'.join(dump(op) for op in all_ops) + '\n'
    with open(os.path.join(out, 'operators.jsonl'), 'w', encoding='utf-8') as f:
        f.write(body)
    with open(os.path.join(out, 'logic.jsonl'), 'w', encoding='utf-8') as f:
        f.write('\n'.join(dump(r) for r in logic_rows) + '\n')
    with open(os.path.join(out, 'masks.jsonl'), 'w', encoding='utf-8') as f:
        f.write(''.join(dump(r) + '\n' for r in mask_rows))
    pins = os.path.join(sumo_root, 'corpus.pins')
    summary = {'schema': SCHEMA, **ops.summarize(all_ops), 'sources': per_source, 'masks': len(mask_rows),
               'operatorsSha256': hashlib.sha256(body.encode('utf-8')).hexdigest(),
               'parser': loaded[0][2]['parser'] if loaded else None,
               'sumo': {'files': len(sumo.files), 'terms': len(sumo.known),
                        'pins': open(pins).read().split('\n')[0:12] if os.path.isfile(pins) else None},
               'wordnet': lex.sources, 'cueClasses': ops.CUE_CLASSES, 'generativeModels': 0, 'models': 0}
    with open(os.path.join(out, 'operators.summary.json'), 'w', encoding='utf-8') as f:
        json.dump(summary, f, ensure_ascii=False, indent=1, sort_keys=True, default=str)
    print(f"operators: {len(all_ops)} candidates, {len(mask_rows)} masks, sha256 {summary['operatorsSha256'][:16]}, "
          f"{time.time() - t0:.1f}s", flush=True)


if __name__ == '__main__':
    main()
