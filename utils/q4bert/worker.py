#!/usr/bin/env python3
"""The q4 BERT lane as a local worker: newline-delimited JSON on stdin and stdout, logs on stderr.

    worker.py

    -> {"op": "init", "bundle": DIR, "max_tokens": 512, "threads": 4}
    <- {"available": true, "loaded": true, "identity": "q4bert:...", "model": {...}, "maxTokens": 512,
        "runtime": {"python", "numpy", "torch"}, "egress": {...}, "error": null}

    -> {"op": "fill", "id": ..., "classes": {class: [words]}, "k": 5,
        "items": [{"key": ..., "text": SENTENCE, "begin": B, "end": E}]}
    <- {"id": ..., "results": [{"key": ..., "status": "ok", "cue": ..., "p": ..., "rank": ..., "top": [...],
                                "classMass": {...}, "preferredClass": ..., "cueClass": ..., "concordant": ...}
                               | {"key": ..., "status": "refused", "reason": ...}],
        "usage": {"sequences": n, "inputTokens": N, "outputTokens": 0, "seconds": s}}

Each item masks the words covering [begin, end) of its text and reports what the masked-language-model head
puts there. An item the lane will not process (over the token limit, span not on word boundaries) comes back
refused with its reason; the worker keeps serving. A request that cannot be read gets {"error": ...}.
Nothing is generated: outputTokens is always 0. The bundle is read from disk; nothing is fetched.
"""
from __future__ import annotations

import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

MAX_ITEMS = 256
MAX_TEXT = 1 << 20


def egress():
    """The egress ledger of this process, when the host started it behind one."""
    guard = sys.modules.get('egress_guard')
    return guard.snapshot() if guard else {'guard': 'absent'}


def versions():
    import numpy
    import torch
    return {'python': '%d.%d.%d' % sys.version_info[:3], 'numpy': numpy.__version__, 'torch': torch.__version__}


def main() -> int:
    out = os.fdopen(os.dup(sys.stdout.fileno()), 'w', encoding='utf-8')
    sys.stdout = sys.stderr           # anything a library prints must not enter the protocol stream
    state = {}

    def reply(obj):
        out.write(json.dumps(obj, ensure_ascii=False, separators=(',', ':'), allow_nan=False) + '\n')
        out.flush()

    for line in sys.stdin:
        if not line.strip():
            continue
        req = None
        try:
            req = json.loads(line)
            op = req.get('op')
            if op == 'init':
                from q4bert.model import open_bundle
                t0 = time.time()
                threads = int(req.get('threads', 4))
                model, tok, identity, config = open_bundle(req['bundle'], threads)
                limit = int(req.get('max_tokens') or config['max_position_embeddings'])
                if not 3 <= limit <= config['max_position_embeddings']:
                    raise ValueError(f"max_tokens must be within 3..{config['max_position_embeddings']}")
                state.update(model=model, tok=tok, identity=identity, limit=limit)
                reply({'available': True, 'loaded': True, 'identity': identity, 'maxTokens': limit, 'threads': threads,
                       'model': {'name': config['q4bert']['name'], 'layers': config['num_hidden_layers'],
                                 'hidden': config['hidden_size'], 'vocabulary': config['vocab_size'],
                                 'quantization': {k: v for k, v in config['quantization'].items() if k != 'tensors'},
                                 'generative': False},
                       'runtime': versions(), 'loadSeconds': round(time.time() - t0, 3), 'egress': egress(),
                       'error': None})
            elif op == 'fill':
                if not state:
                    raise ValueError('init first')
                from q4bert.model import Refused, fill
                items = req['items']
                if not isinstance(items, list) or len(items) > MAX_ITEMS:
                    raise ValueError(f'a fill carries at most {MAX_ITEMS} items')
                classes, k = req.get('classes') or {}, int(req.get('k', 5))
                t0, results, tokens = time.time(), [], 0
                for item in items:
                    try:
                        text = item['text']
                        if not isinstance(text, str) or not text or len(text) > MAX_TEXT:
                            raise Refused('text is empty or too long')
                        r = fill(state['model'], state['tok'], text, int(item['begin']), int(item['end']), classes, k,
                                 state['limit'])
                        tokens += r['tokens']
                    except Refused as e:
                        r = {'status': 'refused', 'reason': str(e)}
                    results.append({'key': item.get('key'), **r})
                reply({'id': req.get('id'), 'identity': state['identity'], 'results': results,
                       'usage': {'sequences': sum(1 for r in results if r['status'] == 'ok'), 'inputTokens': tokens,
                                 'outputTokens': 0, 'seconds': round(time.time() - t0, 3)},
                       'egress': egress()})
            else:
                raise ValueError(f'unknown op {op!r}')
        except Exception as e:   # the protocol answers every line; the reason goes back to the caller
            reply({'id': req.get('id') if isinstance(req, dict) else None, 'error': f'{type(e).__name__}: {e}'})
    return 0


if __name__ == '__main__':
    sys.exit(main())
