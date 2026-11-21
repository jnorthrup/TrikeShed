"""Quantizer, safetensors and tokenizer tests: numpy only, no model files.

    cd utils/q4bert && <python with numpy> -m unittest discover -s tests
"""
import os
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from q4bert import quant, st  # noqa: E402
from q4bert.wordpiece import WordPiece, basic  # noqa: E402


class QuantTests(unittest.TestCase):
    def setUp(self):
        self.rng = np.random.default_rng(3)

    def test_packed_layout(self):
        w = self.rng.normal(0, 0.05, size=(6, 128)).astype(np.float32)
        for bits, group in ((4, 32), (8, 64)):
            q, scales, biases = quant.quantize(w, group, bits)
            self.assertEqual((q.dtype, q.shape), (np.uint32, (6, 128 * bits // 32)))
            self.assertEqual((scales.dtype, scales.shape, biases.shape), (np.float32, (6, 128 // group), (6, 128 // group)))
            self.assertLessEqual(int(quant.unpack(q, bits).max()), (1 << bits) - 1)
            self.assertEqual(quant.unpack(q, bits).shape, w.shape)

    def test_first_weight_is_the_low_bits(self):
        w = np.zeros((1, 32), np.float32)
        w[0, 0], w[0, 1] = 1.5, -1.5                 # the group's range: q = 0 at one edge, 15 at the other
        q, scales, biases = quant.quantize(w, 32, 4)
        ints = quant.unpack(q, 4)[0]
        self.assertEqual(int(q[0, 0] & 0xF), int(ints[0]))
        self.assertEqual(int((q[0, 0] >> 4) & 0xF), int(ints[1]))
        self.assertEqual({int(ints[0]), int(ints[1])}, {0, 15})

    def test_error_is_half_a_step_inside_the_fitted_range_and_one_step_at_its_far_edge(self):
        """The re-fit makes the larger edge exact; the other edge can then fall up to one step outside the
        integer range and is clipped to it."""
        w = self.rng.normal(0, 0.05, size=(8, 96)).astype(np.float32)
        q, scales, biases = quant.quantize(w, 32, 4)
        restored = quant.dequantize(q, scales, biases, 32, 4)
        step = np.repeat(np.abs(scales), 32, axis=1)
        position = (w - np.repeat(biases, 32, axis=1)) / np.repeat(scales, 32, axis=1)    # before rounding
        inside = (position >= 0) & (position <= 15)
        error = np.abs(restored - w)
        self.assertTrue(np.all(error[inside] <= step[inside] / 2 + 1e-6))
        self.assertTrue(np.all(error <= step + 1e-6))
        self.assertGreater(float(inside.mean()), 0.95)
        self.assertLess(quant.error(w, restored)['relativeFrobenius'], 0.12)

    def test_zero_survives_exactly_when_the_group_spans_it(self):
        w = self.rng.normal(0, 0.05, size=(4, 64)).astype(np.float32)
        w[:, ::7] = 0.0
        q, scales, biases = quant.quantize(w, 32, 4)
        restored = quant.dequantize(q, scales, biases, 32, 4)
        self.assertTrue(np.all(np.abs(restored[:, ::7]) < 1e-8))

    def test_all_negative_group_is_scaled_up_to_zero(self):
        """Measured against mx.quantize 0.32.0: the range is extended to zero from below, not from above."""
        w = np.linspace(-0.14, -0.10, 32, dtype=np.float32)[None, :]
        _, scales, biases = quant.quantize(w, 32, 4)
        self.assertAlmostEqual(float(scales[0, 0]), 0.14 / 15, places=7)
        self.assertAlmostEqual(float(biases[0, 0]), -0.14, places=7)
        _, scales, biases = quant.quantize(-w, 32, 4)
        self.assertAlmostEqual(float(scales[0, 0]), -0.14 / 53, places=7)      # 52.5 rounds away from zero

    def test_halves_round_away_from_zero_also_when_large(self):
        x = np.array([0.5, 1.5, 2.5, -0.5, -2.5, 2.4, 14963453.0], np.float32)
        self.assertEqual(quant.round_half_away(x).tolist(), [1.0, 2.0, 3.0, -1.0, -3.0, 2.0, 14963453.0])

    def test_constant_and_zero_groups(self):
        w = np.concatenate([np.full((1, 32), 0.0123, np.float32), np.zeros((1, 32), np.float32)])
        q, scales, biases = quant.quantize(w, 32, 4)
        restored = quant.dequantize(q, scales, biases, 32, 4)
        np.testing.assert_allclose(restored, w, atol=1e-6)

    def test_refuses_shapes_it_cannot_pack(self):
        for shape, group, bits in (((4, 30), 32, 4), ((4, 32), 32, 3), ((32,), 32, 4)):
            with self.assertRaises(ValueError):
                quant.quantize(np.zeros(shape, np.float32), group, bits)
        with self.assertRaises(ValueError):
            quant.dequantize(np.zeros((1, 4), np.uint32), np.zeros((1, 2), np.float32), np.zeros((1, 2), np.float32), 32, 4)

    def test_stored_bits_count_the_scale_and_bias(self):
        self.assertEqual(quant.bits_per_weight((768, 768), 32, 4), 6.0)
        self.assertEqual(quant.bits_per_weight((30522, 768), 64, 8), 9.0)


class SafetensorsTests(unittest.TestCase):
    def test_round_trip_is_exact_and_deterministic(self):
        tensors = {'b.weight': np.arange(12, dtype=np.uint32).reshape(3, 4),
                   'a.scales': np.linspace(-1, 1, 6, dtype=np.float32).reshape(3, 2), 'c': np.float32(2.5).reshape(())}
        with tempfile.TemporaryDirectory() as d:
            one, two = os.path.join(d, 'one.safetensors'), os.path.join(d, 'two.safetensors')
            st.save(one, tensors, {'format': 'q4bert'})
            st.save(two, dict(reversed(list(tensors.items()))), {'format': 'q4bert'})
            self.assertEqual(Path(one).read_bytes(), Path(two).read_bytes())
            back = st.load(one)
            self.assertEqual(sorted(back), sorted(tensors))
            for k, v in tensors.items():
                self.assertEqual(back[k].dtype, v.dtype)
                np.testing.assert_array_equal(back[k], v)
            self.assertEqual(st.metadata(one), {'format': 'q4bert'})
            self.assertEqual(list(st.load(one, names={'c'})), ['c'])
            header, base = st.read_header(one)
            self.assertEqual(base % 8, 0)

    def test_refuses_a_file_that_is_not_safetensors(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, 'x')
            Path(p).write_bytes(b'\xff' * 8 + b'{}')
            with self.assertRaises(ValueError):
                st.read_header(p)


VOCAB = ['[PAD]', '[UNK]', '[CLS]', '[SEP]', '[MASK]', 'if', 'you', 'agree', ',', 'we', 'may', 'terminate', '.',
         'un', '##less', 'cafe', '"', 'co', '-', 'operation', '##s', 'the', '(', ')']


class WordPieceTests(unittest.TestCase):
    def setUp(self):
        self.tok = WordPiece({w: i for i, w in enumerate(VOCAB)})

    def test_basic_words_keep_their_source_offsets(self):
        text = 'If you agree,\twe may “terminate”.'
        words = basic(text)
        self.assertEqual([w for w, _, _ in words], ['if', 'you', 'agree', ',', 'we', 'may', '“', 'terminate', '”', '.'])
        for word, b, e in words:
            self.assertEqual(text[b:e].lower(), word)

    def test_accents_are_stripped_and_control_characters_vanish(self):
        self.assertEqual(basic('Café\u200b co\u00adoperation'), [('cafe', 0, 4), ('cooperation', 6, 18)])
        self.assertEqual(basic('a\u00a0b'), [('a', 0, 1), ('b', 2, 3)])          # a no-break space separates

    def test_greedy_longest_pieces_and_unknown_words(self):
        self.assertEqual(self.tok.pieces('unless'), [13, 14])
        self.assertEqual(self.tok.pieces('operations'), [19, 20])
        self.assertEqual(self.tok.pieces('zebra'), [1])
        self.assertEqual(self.tok.pieces('x' * 101), [1])

    def test_encode_maps_words_to_piece_ranges(self):
        ids, words = self.tok.encode('Unless you agree.')
        self.assertEqual(ids, [2, 13, 14, 6, 7, 12, 3])
        self.assertEqual(words, [(0, 6, 1, 2), (7, 10, 3, 1), (11, 16, 4, 1), (16, 17, 5, 1)])

    def test_whole_words_exclude_pieces_specials_and_punctuation(self):
        self.assertEqual([w for i, w in enumerate(VOCAB) if self.tok.whole(i)],
                         ['if', 'you', 'agree', 'we', 'may', 'terminate', 'un', 'cafe', 'co', 'operation', 'the'])

    def test_a_vocabulary_without_a_mask_is_refused(self):
        with self.assertRaises(ValueError):
            WordPiece({'[CLS]': 0, '[SEP]': 1, '[UNK]': 2})


if __name__ == '__main__':
    unittest.main()
