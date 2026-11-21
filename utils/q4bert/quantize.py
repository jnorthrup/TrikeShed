#!/usr/bin/env python3
"""Make a 4-bit BERT bundle from a float32 checkpoint already on disk. Nothing is downloaded.

    quantize.py --source SNAPSHOT_DIR --out BUNDLE_DIR [--name NAME]
                [--bits 4 --group 32 --embed-bits 8 --embed-group 64]

SNAPSHOT_DIR holds model.safetensors, config.json and vocab.txt (a Hugging Face snapshot directory). Every
2-D weight is quantized group-wise in MLX's packed layout: linear layers at --bits/--group, the three
embedding tables at --embed-bits/--embed-group. Biases and LayerNorm stay float32. The pooler and the
next-sentence head are left out; the masked-language-model head is kept, its decoder tied to the word
embeddings.

BUNDLE_DIR receives model.safetensors, config.json (the source config plus the quantization table),
vocab.txt, provenance.json (source hashes, per-tensor reconstruction error, stored bits per weight) and
SHA256SUMS. The same source gives the same bytes: nothing in the bundle carries a date.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from q4bert import __version__, quant, st  # noqa: E402
from q4bert.model import WEIGHTS, check_config, load_float, read_json, sha256  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--source', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--name')
    ap.add_argument('--bits', type=int, default=4)
    ap.add_argument('--group', type=int, default=32)
    ap.add_argument('--embed-bits', type=int, default=8)
    ap.add_argument('--embed-group', type=int, default=64)
    a = ap.parse_args()
    source = os.path.realpath(os.path.expanduser(a.source))
    out = os.path.expanduser(a.out)
    tensors, config = load_float(source)
    check_config(config)
    os.makedirs(out, exist_ok=True)

    packed, table, rows = {}, {}, []
    weights = stored_bits = 0
    for name in sorted(tensors):
        w = tensors[name]
        if w.ndim != 2:
            packed[name] = w
            continue
        embedding = '.embeddings.' in name
        bits, group = (a.embed_bits, a.embed_group) if embedding else (a.bits, a.group)
        q, scales, biases = quant.quantize(w, group, bits)
        base = name[:-len('.weight')]
        packed[name], packed[base + '.scales'], packed[base + '.biases'] = q, scales, biases
        table[name] = {'bits': bits, 'group_size': group}
        err = quant.error(w, quant.dequantize(q, scales, biases, group, bits))
        rows.append({'tensor': name, 'shape': list(w.shape), 'bits': bits, 'group_size': group,
                     'relativeFrobenius': round(err['relativeFrobenius'], 6), 'maxAbs': round(err['maxAbs'], 6)})
        weights += w.size
        stored_bits += w.size * quant.bits_per_weight(w.shape, group, bits)

    st.save(os.path.join(out, WEIGHTS), packed, {'format': 'q4bert', 'layout': 'mlx-affine'})
    shutil.copyfile(os.path.join(source, 'vocab.txt'), os.path.join(out, 'vocab.txt'))
    tok_config = {}
    if os.path.isfile(os.path.join(source, 'tokenizer_config.json')):
        tok_config = read_json(os.path.join(source, 'tokenizer_config.json'))
    name = a.name or os.path.basename(os.path.dirname(os.path.dirname(source))).replace('models--', '').replace('--', '/')
    bundle_config = {**config, 'quantization': {'bits': a.bits, 'group_size': a.group, 'mode': 'affine', 'tensors': table},
                     'q4bert': {'version': __version__, 'name': name, 'layout': 'mlx-affine',
                                'lower_case': bool(tok_config.get('do_lower_case', True)),
                                'omitted': ['bert.pooler', 'cls.seq_relationship']}}
    with open(os.path.join(out, 'config.json'), 'w', encoding='utf-8') as f:
        json.dump(bundle_config, f, indent=1, sort_keys=True)
    source_files = {n: sha256(os.path.join(source, n)) for n in (WEIGHTS, 'config.json', 'vocab.txt')}
    linear = [r for r in rows if '.embeddings.' not in r['tensor']]
    provenance = {
        'tool': f'q4bert {__version__}', 'name': name,
        'source': {'snapshot': os.path.basename(source), 'files': source_files,
                   'bytes': os.path.getsize(os.path.join(source, WEIGHTS)),
                   'license': 'see the source model card; not retained in the offline snapshot'},
        'quantization': {'mode': 'affine, group-wise, MLX packed layout', 'linear': {'bits': a.bits, 'group_size': a.group},
                         'embeddings': {'bits': a.embed_bits, 'group_size': a.embed_group},
                         'float32': 'biases, LayerNorm', 'calibration': 'none (round to nearest)'},
        'quantizedWeights': weights, 'storedBitsPerWeight': round(stored_bits / weights, 4),
        'bytes': os.path.getsize(os.path.join(out, WEIGHTS)),
        'relativeFrobenius': {'linearMean': round(float(np.mean([r['relativeFrobenius'] for r in linear])), 6),
                              'linearMax': max(r['relativeFrobenius'] for r in linear),
                              'embeddingsMax': max(r['relativeFrobenius'] for r in rows if '.embeddings.' in r['tensor'])},
        'tensors': rows,
        'scope': 'reconstruction error of weights; task fidelity is measured separately (fidelity.py)'}
    with open(os.path.join(out, 'provenance.json'), 'w', encoding='utf-8') as f:
        json.dump(provenance, f, indent=1, sort_keys=True)
    with open(os.path.join(out, 'SHA256SUMS'), 'w', encoding='utf-8') as f:
        for n in sorted((WEIGHTS, 'config.json', 'vocab.txt', 'provenance.json')):
            f.write(f'{sha256(os.path.join(out, n))}  {n}\n')
    print(f"{name}: {len(rows)} tensors quantized, {weights:,} weights at {provenance['storedBitsPerWeight']} stored bits each")
    print(f"  {provenance['source']['bytes']:,} -> {provenance['bytes']:,} bytes "
          f"({provenance['bytes'] / provenance['source']['bytes']:.1%}); relative Frobenius error "
          f"linear mean {provenance['relativeFrobenius']['linearMean']}, max {provenance['relativeFrobenius']['linearMax']}; "
          f"embeddings max {provenance['relativeFrobenius']['embeddingsMax']}")
    print(f"  {out}  sha256 {sha256(os.path.join(out, WEIGHTS))[:16]}")
    return 0


if __name__ == '__main__':
    sys.exit(main())
