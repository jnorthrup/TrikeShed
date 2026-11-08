"""Stanza parse of a source into source-bound sentence records (no generative model anywhere).

Each word keeps its surface text, Stanza's lemma, UPOS/XPOS, features, head and relation beside
it; the sentence quote is cut from the source by offset. Lemmas are annotations for matching,
never the statement.
"""
from __future__ import annotations

import hashlib
import json
import os
import time

from .text import VIEW_VERSION, Block, parse_view, shouted_pieces, utf16_offsets, view_to_source

PROCESSORS = 'tokenize,mwt,pos,lemma,depparse,ner'
SCHEMA = 'trikeshed.stanza-parse.v1'


def model_dir() -> str:
    return os.environ.get('TRIKESHED_STANZA_RESOURCES',
                          os.path.expanduser('~/.local/share/TrikeShed/stanza/resources'))


def pipeline(use_gpu: bool = True):
    import stanza
    import torch
    kw = {}
    if use_gpu and not torch.cuda.is_available() and torch.backends.mps.is_available():
        kw['device'] = 'mps'
    return stanza.Pipeline('en', dir=model_dir(), processors=PROCESSORS, download_method=None,
                           use_gpu=use_gpu, logging_level='WARN', **kw)


def _sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def parser_metadata(nlp) -> dict:
    import stanza
    import torch
    models = {}
    for name, proc in nlp.processors.items():
        cfg = getattr(proc, 'config', {}) or {}
        paths = cfg.get('model_path')
        for path in (paths if isinstance(paths, (list, tuple)) else [paths]):
            if isinstance(path, str) and os.path.isfile(path):
                models.setdefault(name, []).append({'file': os.path.relpath(path, model_dir()), 'sha256': _sha256(path)})
    res = os.path.join(model_dir(), 'resources.json')
    meta = {'implementation': 'stanza', 'version': stanza.__version__, 'torch': torch.__version__,
            'processors': PROCESSORS, 'models': models, 'generative': False}
    if os.path.isfile(res):
        meta['resourcesJsonSha256'] = _sha256(res)
    return meta


def parse(nlp, text: str, blocks: list[Block]) -> list[dict]:
    view, starts = parse_view(text, blocks)
    back, block_of = view_to_source(starts)
    u16 = utf16_offsets(text)
    doc = nlp(view)
    sentences = []
    for sent in doc.sentences:
        words = []
        for tok in sent.tokens:
            b, e = back(tok.start_char), back(tok.end_char - 1) + 1
            for w in tok.words:
                # a single-word token keeps its source surface (“COINBASE”), whatever casing the parser read
                surface = text[b:e] if len(tok.words) == 1 else w.text
                words.append({'id': w.id, 'text': surface, 'lemma': w.lemma or w.text, 'upos': w.upos,
                              'xpos': w.xpos, 'feats': w.feats, 'head': w.head, 'deprel': w.deprel,
                              'begin': b, 'end': e, 'ner': tok.ner, 'mwt': len(tok.words) > 1})
        b, e = words[0]['begin'], words[-1]['end']
        ents = [{'text': text[back(x.start_char):back(x.end_char - 1) + 1], 'type': x.type,
                 'begin': back(x.start_char), 'end': back(x.end_char - 1) + 1} for x in sent.ents]
        sentences.append({'index': len(sentences), 'begin': b, 'end': e, 'u16Begin': u16[b], 'u16End': u16[e],
                          'quote': text[b:e], 'section': block_of(sent.tokens[0].start_char).section,
                          'words': words, 'entities': ents})
    return sentences


def parse_source(nlp, meta: dict, source: dict, text: str, blocks: list[Block], cache_dir: str) -> dict:
    """Parse once per (text, parser models) and reuse the record from the cache afterwards."""
    key = hashlib.sha256(json.dumps({'text': hashlib.sha256(text.encode()).hexdigest(),
                                     'blocks': [(b.begin, b.end) for b in blocks],
                                     'models': meta['models'], 'version': meta['version'], 'view': VIEW_VERSION},
                                    sort_keys=True).encode()).hexdigest()[:24]
    path = os.path.join(cache_dir, f"{source['id']}.{key}.parse.json")
    if os.path.isfile(path):
        with open(path) as f:
            return json.load(f)
    t0 = time.time()
    sentences = parse(nlp, text, blocks)
    record = {'schema': SCHEMA, 'source': source, 'parser': meta, 'seconds': round(time.time() - t0, 3),
              'blocks': len(blocks), 'truecasedPieces': sum(len(shouted_pieces(text[b.begin:b.end])) for b in blocks),
              'sentences': sentences}
    os.makedirs(cache_dir, exist_ok=True)
    with open(path, 'w') as f:
        json.dump(record, f, ensure_ascii=False)
    return record
