"""Conformance against the reference implementations, when they are present on this machine.

    TRIKESHED_MLX_PYTHON=<python with mlx>   compares quant.quantize / dequantize with mlx.core, bit for bit
    Q4BERT_SOURCE=<snapshot dir>             compares tokenization and float32 logits with transformers
                                             (default: the cached nlpaueb/legal-bert-base-uncased snapshot)

Both are skipped when their reference is absent; nothing is downloaded.
"""
import glob
import json
import os
import subprocess
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, '..')
sys.path.insert(0, ROOT)

MLX_PYTHON = os.environ.get('TRIKESHED_MLX_PYTHON')
SOURCE = os.environ.get('Q4BERT_SOURCE') or next(iter(glob.glob(os.path.expanduser(
    '~/.cache/huggingface/hub/models--nlpaueb--legal-bert-base-uncased/snapshots/*/'))), None)

MLX_SWEEP = r'''
import json, sys
import numpy as np
import mlx.core as mx
from q4bert import quant
rng = np.random.default_rng(int(sys.argv[1]))
weights = integers = groups = fits = 0
for trial in range(int(sys.argv[2])):
    g = int(rng.choice([32, 64])); b = int(rng.choice([4, 8])); o = int(rng.integers(4, 40)); i = g * int(rng.integers(1, 30))
    w = rng.normal(0, rng.choice([1e-4, 0.02, 0.05, 1.0, 30.0]), size=(o, i)).astype(np.float32)
    for r in range(o):                      # same-sign, zero, constant, narrow and tie-heavy groups
        kind = rng.integers(0, 9); sl = slice(0, g)
        if kind == 0: w[r, sl] = np.abs(w[r, sl])
        elif kind == 1: w[r, sl] = -np.abs(w[r, sl])
        elif kind == 2: w[r, sl] = 0
        elif kind == 3: w[r, sl] = np.float32(rng.normal())
        elif kind == 4: w[r, sl] = np.float32(0.1) + np.linspace(0, 0.04, g, dtype=np.float32)
        elif kind == 5: w[r, sl] = -np.float32(0.1) - np.linspace(0, 0.04, g, dtype=np.float32)
        elif kind == 6: w[r, sl] = np.round(w[r, sl] * 8) / 8
    mq, ms, mb = [np.array(a) for a in mx.quantize(mx.array(w), group_size=g, bits=b)]
    nq, ns, nb = quant.quantize(w, g, b)
    weights += w.size; groups += ms.size
    integers += int((quant.unpack(mq, b) != quant.unpack(nq, b)).sum()) + int((mq != nq).sum())
    fits += int(((ms != ns) | (mb != nb)).sum())
    restored = np.array(mx.dequantize(mx.array(mq), mx.array(ms), mx.array(mb), group_size=g, bits=b))
    assert np.abs(restored - quant.dequantize(mq, ms, mb, g, b)).max() <= 1e-6 * max(1.0, float(np.abs(w).max()))
print(json.dumps({"mlx": getattr(mx, "__version__", None), "weights": weights, "groups": groups,
                  "integerMismatches": integers, "scaleOrBiasMismatches": fits}))
'''

TEXTS = ['If you do not agree with any such modification, your sole and exclusive remedy is to terminate your use of '
         'the Services and close your account (as defined below).',
         'Coinbase, Inc. ("Coinbase," “we,” “us,” and “our”). This User Agreement governs your use — see '
         'https://help.coinbase.com §3.1(a)!',
         'Café naïve résumé façade; the lessee’s co-operation re-enters 1,000.50 USD/EUR & 18 U.S.C. § 1030.',
         'IN NO EVENT SHALL COINBASE BE LIABLE\tfor\u00a0any indirect damages\u200b.']


@unittest.skipUnless(MLX_PYTHON and os.path.exists(MLX_PYTHON), 'TRIKESHED_MLX_PYTHON is not set')
class MlxConformance(unittest.TestCase):
    def test_quantize_matches_mx_quantize_bit_for_bit(self):
        for seed in (20261119, 1, 2):
            done = subprocess.run([MLX_PYTHON, '-c', MLX_SWEEP, str(seed), '60'], cwd=ROOT, capture_output=True, text=True)
            self.assertEqual(done.returncode, 0, done.stderr[-2000:])
            report = json.loads(done.stdout.strip().splitlines()[-1])
            self.assertGreater(report['weights'], 500_000)
            self.assertEqual((report['integerMismatches'], report['scaleOrBiasMismatches']), (0, 0), report)


def transformers_available():
    try:
        import torch  # noqa: F401
        import transformers  # noqa: F401
        return True
    except Exception:
        return False


@unittest.skipUnless(SOURCE and transformers_available(), 'no cached checkpoint or no transformers')
class TransformersConformance(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        os.environ.update(HF_HUB_OFFLINE='1', TRANSFORMERS_OFFLINE='1')
        from q4bert.wordpiece import WordPiece
        cls.tok = WordPiece.from_file(os.path.join(SOURCE, 'vocab.txt'))

    def test_tokenization_matches_bert_tokenizer(self):
        from transformers import BertTokenizer
        reference = BertTokenizer(os.path.join(SOURCE, 'vocab.txt'), do_lower_case=True)
        for text in TEXTS:
            self.assertEqual(self.tok.encode(text)[0], reference(text)['input_ids'], text)

    def test_float32_logits_match_bert_for_masked_lm(self):
        import numpy as np
        import torch
        import transformers
        from q4bert.model import Bert, load_float
        transformers.logging.set_verbosity_error()
        tensors, config = load_float(SOURCE)
        mine = Bert(tensors, config)
        reference = transformers.BertForMaskedLM.from_pretrained(SOURCE, local_files_only=True).eval()
        worst = 0.0
        for text in TEXTS[:3]:
            ids, words = self.tok.encode(text)
            for begin, end, first, count in words[:6]:
                masked = ids[:first] + [self.tok.mask] + ids[first + count:]
                a = mine.logits(masked, first)
                with torch.inference_mode():
                    b = reference(input_ids=torch.tensor([masked])).logits[0, first].numpy().astype(np.float64)
                self.assertEqual(int(a.argmax()), int(b.argmax()))
                worst = max(worst, float(np.abs(a - b).max()))
        self.assertLess(worst, 1e-3)


if __name__ == '__main__':
    unittest.main()
