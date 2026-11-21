"""A tiny random BERT through the whole path: quantize, open, fill, refuse, serve. Needs numpy and torch.

Random weights say nothing about language; these tests check the plumbing and the contracts.
"""
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, '..')
sys.path.insert(0, ROOT)

try:
    import torch  # noqa: F401
    TORCH = True
except Exception:   # the encoder runs on torch; without it only test_core applies
    TORCH = False

from q4bert import st  # noqa: E402

VOCAB = ['[PAD]', '[UNK]', '[CLS]', '[SEP]', '[MASK]', 'if', 'unless', 'when', 'you', 'we', 'agree', 'object', 'may',
         'shall', 'will', 'terminate', 'close', 'the', 'account', ',', '.', 'not', 'and', 'or', 'un', '##less']
CONFIG = {'model_type': 'bert', 'hidden_size': 64, 'num_hidden_layers': 2, 'num_attention_heads': 4,
          'intermediate_size': 128, 'max_position_embeddings': 16, 'type_vocab_size': 2, 'vocab_size': len(VOCAB),
          'hidden_act': 'gelu', 'layer_norm_eps': 1e-12, 'pad_token_id': 0}
CLASSES = {'condition': ['if'], 'exception': ['unless'], 'temporal': ['when'], 'possibility': ['may'], 'necessity': ['shall']}


def tiny_checkpoint(directory):
    rng = np.random.default_rng(11)
    h, inner, v = CONFIG['hidden_size'], CONFIG['intermediate_size'], CONFIG['vocab_size']

    def w(*shape):
        return rng.normal(0, 0.2, size=shape).astype(np.float32)
    t = {'bert.embeddings.word_embeddings.weight': w(v, h), 'bert.embeddings.position_embeddings.weight': w(16, h),
         'bert.embeddings.token_type_embeddings.weight': w(2, h), 'bert.embeddings.LayerNorm.weight': np.ones(h, np.float32),
         'bert.embeddings.LayerNorm.bias': np.zeros(h, np.float32), 'bert.embeddings.position_ids': np.arange(16, dtype=np.int64)[None],
         'cls.predictions.transform.dense.weight': w(h, h), 'cls.predictions.transform.dense.bias': w(h),
         'cls.predictions.transform.LayerNorm.weight': np.ones(h, np.float32),
         'cls.predictions.transform.LayerNorm.bias': np.zeros(h, np.float32), 'cls.predictions.bias': w(v),
         'bert.pooler.dense.weight': w(h, h), 'bert.pooler.dense.bias': w(h),
         'cls.seq_relationship.weight': w(2, h), 'cls.seq_relationship.bias': w(2)}
    for i in range(CONFIG['num_hidden_layers']):
        p = f'bert.encoder.layer.{i}.'
        for name, shape in (('attention.self.query', (h, h)), ('attention.self.key', (h, h)), ('attention.self.value', (h, h)),
                            ('attention.output.dense', (h, h)), ('intermediate.dense', (inner, h)), ('output.dense', (h, inner))):
            t[p + name + '.weight'], t[p + name + '.bias'] = w(*shape), w(shape[0])
        for name in ('attention.output.LayerNorm', 'output.LayerNorm'):
            t[p + name + '.weight'], t[p + name + '.bias'] = np.ones(h, np.float32), np.zeros(h, np.float32)
    st.save(os.path.join(directory, 'model.safetensors'), t)
    Path(directory, 'config.json').write_text(json.dumps(CONFIG), encoding='utf-8')
    Path(directory, 'vocab.txt').write_text('\n'.join(VOCAB) + '\n', encoding='utf-8')
    Path(directory, 'tokenizer_config.json').write_text(json.dumps({'do_lower_case': True}), encoding='utf-8')


def quantize(source, out):
    done = subprocess.run([sys.executable, os.path.join(ROOT, 'quantize.py'), '--source', source, '--out', out, '--name', 'tiny/bert'],
                          capture_output=True, text=True)
    assert done.returncode == 0, done.stderr
    return done.stdout


@unittest.skipUnless(TORCH, 'torch is not installed')
class TinyModelTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.source, cls.bundle = os.path.join(cls.tmp.name, 'source'), os.path.join(cls.tmp.name, 'bundle')
        os.makedirs(cls.source)
        tiny_checkpoint(cls.source)
        cls.log = quantize(cls.source, cls.bundle)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def open(self):
        from q4bert.model import open_bundle
        return open_bundle(self.bundle, threads=1)

    def test_bundle_holds_packed_weights_and_omits_unused_heads(self):
        raw = st.load(os.path.join(self.bundle, 'model.safetensors'))
        self.assertEqual(raw['bert.encoder.layer.0.intermediate.dense.weight'].dtype, np.uint32)
        self.assertEqual(raw['bert.encoder.layer.0.intermediate.dense.weight'].shape, (128, 64 * 4 // 32))
        self.assertEqual(raw['bert.embeddings.word_embeddings.weight'].shape, (len(VOCAB), 64 * 8 // 32))
        self.assertEqual(raw['bert.encoder.layer.0.intermediate.dense.bias'].dtype, np.float32)
        self.assertFalse(any(k.startswith(('bert.pooler', 'cls.seq_relationship')) or k.endswith('position_ids') for k in raw))
        config = json.loads(Path(self.bundle, 'config.json').read_text(encoding='utf-8'))
        self.assertEqual(config['quantization']['tensors']['bert.embeddings.word_embeddings.weight'], {'bits': 8, 'group_size': 64})
        provenance = json.loads(Path(self.bundle, 'provenance.json').read_text(encoding='utf-8'))
        self.assertEqual(provenance['quantization']['calibration'], 'none (round to nearest)')
        self.assertNotIn('date', json.dumps(provenance).lower())

    def test_quantizing_again_gives_the_same_bytes(self):
        again = os.path.join(self.tmp.name, 'again')
        quantize(self.source, again)
        for name in ('model.safetensors', 'config.json', 'provenance.json', 'SHA256SUMS'):
            self.assertEqual(Path(again, name).read_bytes(), Path(self.bundle, name).read_bytes(), name)

    def test_fill_reports_the_cue_its_rank_and_class_mass(self):
        from q4bert.model import fill
        model, tok, identity, _ = self.open()
        self.assertTrue(identity.startswith('q4bert:tiny/bert:b4g32:'))
        text = 'If you agree, we may terminate the account.'
        r = fill(model, tok, text, 0, 2, CLASSES)
        self.assertEqual((r['status'], r['cue'], r['cueClass'], r['tokens'], r['position']), ('ok', 'if', 'condition', 12, 1))
        self.assertTrue(0.0 < r['p'] < 1.0 and 1 <= r['rank'] <= len(VOCAB))
        self.assertEqual(len(r['top']), 5)
        self.assertTrue(all(w.isalpha() for w, _ in r['top']))
        self.assertAlmostEqual(r['classMass']['condition'], r['p'], places=6)     # one word in the class
        self.assertEqual(r['concordant'], r['preferredClass'] == 'condition')
        again = fill(model, tok, text, 0, 2, CLASSES)
        self.assertEqual(r, again)                                                 # same input, same numbers

    def test_a_cue_of_several_pieces_is_one_mask_without_its_own_probability(self):
        from q4bert.model import fill, place
        model, tok, _, _ = self.open()
        vocab = dict(tok.vocab)
        del tok.vocab['unless']                    # now “unless” is un + ##less
        try:
            masked, position, cue, n = place(tok, 'unless you object.', 0, 6, 16)
            self.assertEqual((masked.count(tok.mask), n, cue), (1, 6, 'unless'))
            r = fill(model, tok, 'unless you object.', 0, 6, CLASSES)
            self.assertIsNone(r['p'])
            self.assertIsNone(r['rank'])
            self.assertEqual(r['classWordsNotInVocabulary'], {'exception': ['unless']})
        finally:
            tok.vocab.clear()
            tok.vocab.update(vocab)

    def test_over_the_token_limit_is_refused_not_truncated(self):
        from q4bert.model import Refused, fill
        model, tok, _, _ = self.open()
        text = 'if ' + 'you agree , ' * 6 + 'we close the account.'
        with self.assertRaisesRegex(Refused, r'26 tokens; limit is 16; truncation is disabled'):    # 24 words + [CLS] [SEP]
            fill(model, tok, text, 0, 2, CLASSES, max_tokens=16)
        with self.assertRaisesRegex(Refused, 'limit is 8'):
            fill(model, tok, 'If you agree, we may terminate the account.', 0, 2, CLASSES, max_tokens=8)

    def test_a_span_that_cuts_a_word_is_refused(self):
        from q4bert.model import Refused, fill
        model, tok, _, _ = self.open()
        with self.assertRaisesRegex(Refused, 'whole words'):
            fill(model, tok, 'If you agree.', 0, 1, CLASSES)

    def test_a_changed_bundle_file_is_refused(self):
        from q4bert.model import open_bundle
        broken = os.path.join(self.tmp.name, 'broken')
        quantize(self.source, broken)
        with open(os.path.join(broken, 'vocab.txt'), 'a') as f:
            f.write('extra\n')
        with self.assertRaisesRegex(ValueError, 'vocab.txt does not match SHA256SUMS'):
            open_bundle(broken)

    def test_a_bundle_in_another_quantization_layout_is_refused(self):
        from q4bert.model import load_bundle
        other = os.path.join(self.tmp.name, 'other-layout')
        quantize(self.source, other)
        config = json.loads(Path(other, 'config.json').read_text(encoding='utf-8'))
        config['quantization']['mode'] = 'mxfp4'
        Path(other, 'config.json').write_text(json.dumps(config), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'mlx-affine layout'):
            load_bundle(other)

    def test_a_missing_bundle_is_an_error_not_a_download(self):
        from q4bert.model import open_bundle
        with self.assertRaises(FileNotFoundError):
            open_bundle(os.path.join(self.tmp.name, 'absent'))

    def test_four_bits_move_the_logits_but_not_far(self):
        from q4bert.model import Bert, load_float, probabilities
        model, tok, _, config = self.open()
        tensors, _ = load_float(self.source)
        reference = Bert(tensors, config, threads=1)
        ids, _ = tok.encode('if you agree , we may close the account .')
        ids[1] = tok.mask
        p4, p32 = probabilities(model.logits(ids, 1)), probabilities(reference.logits(ids, 1))
        self.assertAlmostEqual(float(p4.sum()), 1.0, places=9)
        self.assertGreater(float(np.abs(p4 - p32).max()), 0.0)
        self.assertLess(float(np.abs(p4 - p32).max()), 0.2)

    def test_worker_serves_fills_and_refusals_and_generates_nothing(self):
        proc = subprocess.Popen([sys.executable, os.path.join(ROOT, 'worker.py')], stdin=subprocess.PIPE,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

        def ask(payload):
            proc.stdin.write(json.dumps(payload) + '\n')
            proc.stdin.flush()
            return json.loads(proc.stdout.readline())
        try:
            self.assertIn('init first', ask({'op': 'fill', 'items': []})['error'])
            init = ask({'op': 'init', 'bundle': self.bundle, 'max_tokens': 12, 'threads': 1})
            self.assertEqual((init['available'], init['loaded'], init['error'], init['maxTokens']), (True, True, None, 12))
            self.assertFalse(init['model']['generative'])
            self.assertEqual(init['model']['quantization'], {'bits': 4, 'group_size': 32, 'mode': 'affine'})
            reply = ask({'op': 'fill', 'id': 'a', 'classes': CLASSES, 'items': [
                {'key': 1, 'text': 'If you agree, we may close the account.', 'begin': 0, 'end': 2},
                {'key': 2, 'text': 'if ' + 'you agree , ' * 6 + 'we close the account.', 'begin': 0, 'end': 2},
                {'key': 3, 'text': 'If you agree.', 'begin': 1, 'end': 2}]})
            self.assertEqual([r['status'] for r in reply['results']], ['ok', 'refused', 'refused'])
            self.assertIn('limit is 12', reply['results'][1]['reason'])
            self.assertEqual(reply['usage'], {'sequences': 1, 'inputTokens': 12, 'outputTokens': 0, 'seconds': reply['usage']['seconds']})
            self.assertEqual(reply['identity'], init['identity'])
            self.assertIn('unknown op', ask({'op': 'generate'})['error'])
            self.assertEqual(ask({'op': 'fill', 'id': 'b', 'classes': CLASSES, 'items': [
                {'key': 1, 'text': 'We may close the account.', 'begin': 3, 'end': 6}]})['results'][0]['cue'], 'may')
        finally:
            proc.stdin.close()
            proc.wait(timeout=30)
            proc.stdout.close()
            proc.stderr.close()
        self.assertEqual(proc.returncode, 0)


if __name__ == '__main__':
    unittest.main()
