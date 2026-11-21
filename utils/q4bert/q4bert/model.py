"""BERT encoder and masked-language-model head over dense float32 weights.

The weights come either from a q4 bundle (packed integers dequantized once at load: w = q * scale + bias,
which is what a quantized matmul computes with) or from the float32 checkpoint the bundle was made from
(the reference for fidelity). The forward pass is the published architecture, written out: embeddings and
LayerNorm, per layer self-attention, residual LayerNorm, GELU feed-forward, residual LayerNorm; the head is
dense, GELU, LayerNorm and the decoder tied to the word embeddings. tests/test_hf_conformance.py compares the
logits with transformers' BertForMaskedLM when it is installed.

An encoder scores a masked position; it writes no text. Sequences over the token limit are refused, never cut.
"""
from __future__ import annotations

import hashlib
import json
import math
import os

import numpy as np

from . import quant, st
from .wordpiece import WordPiece

WEIGHTS = 'model.safetensors'
UNUSED = ('bert.pooler.', 'cls.seq_relationship.')
BUFFERS = ('position_ids', 'token_type_ids')


class Refused(ValueError):
    """An input this lane will not process; the reason is the message."""


def sha256(path) -> str:
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def read_json(path):
    with open(path, encoding='utf-8') as f:
        return json.load(f)


def canonical(name: str) -> str:
    return name.replace('LayerNorm.gamma', 'LayerNorm.weight').replace('LayerNorm.beta', 'LayerNorm.bias')


def wanted(name: str) -> bool:
    return name != '__metadata__' and not name.startswith(UNUSED) and not name.endswith(BUFFERS)


def check_config(config: dict) -> None:
    if config.get('model_type', 'bert') != 'bert' or config.get('hidden_act', 'gelu') != 'gelu':
        raise ValueError('only BERT with GELU is supported')
    if config.get('position_embedding_type') not in (None, 'absolute'):
        raise ValueError('only absolute position embeddings are supported')
    if config['hidden_size'] % config['num_attention_heads']:
        raise ValueError('hidden size is not a multiple of the head count')


def tie_decoder(tensors: dict) -> dict:
    """The decoder is the word-embedding matrix; a checkpoint that stores it separately must agree."""
    decoder = tensors.pop('cls.predictions.decoder.weight', None)
    if decoder is not None and not np.array_equal(decoder, tensors['bert.embeddings.word_embeddings.weight']):
        raise ValueError('decoder weight is not tied to the word embeddings')
    bias = tensors.pop('cls.predictions.decoder.bias', None)
    if bias is not None:
        if 'cls.predictions.bias' in tensors and not np.array_equal(bias, tensors['cls.predictions.bias']):
            raise ValueError('two different decoder biases')
        tensors['cls.predictions.bias'] = bias
    return tensors


def load_float(source: str):
    """(tensors, config) of the float32 checkpoint as published."""
    config = read_json(os.path.join(source, 'config.json'))
    check_config(config)
    raw = st.load(os.path.join(source, WEIGHTS))
    tensors = {canonical(k): np.ascontiguousarray(v, dtype=np.float32) for k, v in raw.items() if wanted(k)}
    return tie_decoder(tensors), config


def load_bundle(bundle: str):
    """(tensors, config, identity) of a q4 bundle; packed weights are dequantized here, once."""
    config = read_json(os.path.join(bundle, 'config.json'))
    check_config(config)
    if config.get('q4bert', {}).get('layout') != 'mlx-affine' or config.get('quantization', {}).get('mode') != 'affine':
        raise ValueError('not a q4bert bundle in the mlx-affine layout')
    table = config['quantization']['tensors']
    raw = st.load(os.path.join(bundle, WEIGHTS))
    tensors = {}
    for name, a in raw.items():
        if name.endswith(('.scales', '.biases')):
            continue
        if name in table:
            base = name[:-len('.weight')]
            q = table[name]
            if a.dtype != np.uint32:
                raise ValueError(f'{name}: quantized weight is not packed uint32')
            tensors[name] = quant.dequantize(a, raw[base + '.scales'], raw[base + '.biases'], q['group_size'], q['bits'])
        else:
            if a.dtype != np.float32:
                raise ValueError(f'{name}: unexpected dtype {a.dtype}')
            tensors[name] = a
    digest = sha256(os.path.join(bundle, WEIGHTS))
    q = config['quantization']
    identity = f"q4bert:{config['q4bert']['name']}:b{q['bits']}g{q['group_size']}:{digest[:16]}"
    return tensors, config, identity


class Bert:
    def __init__(self, tensors: dict, config: dict, threads: int = 4):
        import torch
        self.torch = torch
        torch.set_num_threads(threads)
        self.config = config
        self.hidden, self.heads, self.layers = config['hidden_size'], config['num_attention_heads'], config['num_hidden_layers']
        self.eps = float(config.get('layer_norm_eps', 1e-12))
        self.positions = config['max_position_embeddings']
        self.t = {k: torch.from_numpy(np.ascontiguousarray(v)) for k, v in tensors.items()}
        need = ['bert.embeddings.word_embeddings.weight', 'bert.embeddings.position_embeddings.weight',
                'bert.embeddings.token_type_embeddings.weight', 'cls.predictions.transform.dense.weight', 'cls.predictions.bias']
        need += [f'bert.encoder.layer.{self.layers - 1}.output.dense.weight']
        missing = [n for n in need if n not in self.t]
        if missing:
            raise ValueError(f'checkpoint lacks {missing}')

    def _norm(self, x, prefix):
        return self.torch.nn.functional.layer_norm(x, (self.hidden,), self.t[prefix + '.weight'], self.t[prefix + '.bias'], self.eps)

    def _linear(self, x, prefix):
        return x @ self.t[prefix + '.weight'].T + self.t[prefix + '.bias']

    def encode(self, ids: list[int]):
        """Last-layer states, [tokens, hidden]."""
        torch = self.torch
        n = len(ids)
        if n > self.positions:
            raise Refused(f'{n} tokens; the model has {self.positions} positions')
        with torch.inference_mode():
            e = 'bert.embeddings.'
            x = self.t[e + 'word_embeddings.weight'][torch.tensor(ids)] + self.t[e + 'position_embeddings.weight'][:n] \
                + self.t[e + 'token_type_embeddings.weight'][0]
            x = self._norm(x, e + 'LayerNorm')
            width = self.hidden // self.heads
            for i in range(self.layers):
                p = f'bert.encoder.layer.{i}.'

                def heads(t):
                    return t.view(n, self.heads, width).transpose(0, 1)
                q, k, v = (heads(self._linear(x, p + 'attention.self.' + name)) for name in ('query', 'key', 'value'))
                attention = torch.softmax(q @ k.transpose(1, 2) / math.sqrt(width), dim=-1)
                context = (attention @ v).transpose(0, 1).reshape(n, self.hidden)
                x = self._norm(self._linear(context, p + 'attention.output.dense') + x, p + 'attention.output.LayerNorm')
                inner = torch.nn.functional.gelu(self._linear(x, p + 'intermediate.dense'))
                x = self._norm(self._linear(inner, p + 'output.dense') + x, p + 'output.LayerNorm')
            return x

    def logits(self, ids: list[int], position: int) -> np.ndarray:
        """Vocabulary logits of the masked-language-model head at one position."""
        torch = self.torch
        with torch.inference_mode():
            row = self.encode(ids)[position]
            h = torch.nn.functional.gelu(self._linear(row, 'cls.predictions.transform.dense'))
            h = self._norm(h, 'cls.predictions.transform.LayerNorm')
            out = h @ self.t['bert.embeddings.word_embeddings.weight'].T + self.t['cls.predictions.bias']
            return out.numpy().astype(np.float64)


def probabilities(logits: np.ndarray) -> np.ndarray:
    z = logits - logits.max()
    e = np.exp(z)
    return e / e.sum()


def place(tok: WordPiece, text: str, begin: int, end: int, max_tokens: int):
    """(masked ids, mask position, cue word, token count): the words covering [begin, end) become one [MASK]."""
    ids, words = tok.encode(text)
    hit = [w for w in words if w[0] < end and w[1] > begin]
    if not hit or hit[0][0] != begin or hit[-1][1] != end:
        raise Refused('the cue span does not cover whole words of the text')
    first, count = hit[0][2], sum(w[3] for w in hit)
    masked = ids[:first] + [tok.mask] + ids[first + count:]
    if len(masked) > max_tokens:
        raise Refused(f'{len(masked)} tokens; limit is {max_tokens}; truncation is disabled')
    cue = text[begin:end].lower() if tok.lower else text[begin:end]
    return masked, first, cue, len(masked)


def fill(model: Bert, tok: WordPiece, text: str, begin: int, end: int, classes: dict, k: int = 5,
         max_tokens: int = 512, with_distribution: bool = False) -> dict:
    """What the masked-language-model head puts where the cue stood: the cue's own probability and rank, the
    best whole-word substitutes, and the probability mass of each cue class. Evidence about a slot, no decision."""
    masked, position, cue, tokens = place(tok, text, begin, end, max_tokens)
    p = probabilities(model.logits(masked, position))
    cue_id = tok.vocab.get(cue)
    order = np.argsort(-p, kind='stable')
    top = [[tok.words[i], round(float(p[i]), 6)] for i in order if tok.whole(int(i))][:k]
    mass, missing = {}, {}
    for name, words in classes.items():
        known = [w for w in words if w in tok.vocab]
        mass[name] = round(float(sum(p[tok.vocab[w]] for w in known)), 6)
        if len(known) != len(words):
            missing[name] = [w for w in words if w not in tok.vocab]
    preferred = max(sorted(mass), key=lambda c: mass[c]) if mass else None
    cue_class = next((c for c, words in classes.items() if cue in words), None)
    out = {'status': 'ok', 'cue': cue, 'tokens': tokens, 'position': position,
           'p': round(float(p[cue_id]), 6) if cue_id is not None else None,
           'rank': int((p > p[cue_id]).sum()) + 1 if cue_id is not None else None,
           'top': top, 'classMass': mass, 'preferredClass': preferred, 'cueClass': cue_class,
           'concordant': preferred == cue_class if cue_class else None}
    if missing:
        out['classWordsNotInVocabulary'] = missing
    if with_distribution:
        out['_p'] = p
    return out


def open_bundle(bundle: str, threads: int = 4):
    """(model, tokenizer, identity, config) with the bundle's own files checked against its SHA256SUMS."""
    with open(os.path.join(bundle, 'SHA256SUMS'), encoding='utf-8') as f:
        sums = [line.split() for line in f if line.strip()]
    if {name for _, name in sums} != {WEIGHTS, 'config.json', 'vocab.txt', 'provenance.json'}:
        raise ValueError('SHA256SUMS does not list exactly the bundle files')
    for digest, name in sums:
        if sha256(os.path.join(bundle, name)) != digest:
            raise ValueError(f'{name} does not match SHA256SUMS')
    tensors, config, identity = load_bundle(bundle)
    tok = WordPiece.from_file(os.path.join(bundle, 'vocab.txt'), lower=config['q4bert']['lower_case'])
    if len(tok.words) != config['vocab_size']:
        raise ValueError('vocabulary size differs from the model config')
    return Bert(tensors, config, threads), tok, identity, config
