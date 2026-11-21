"""BERT's basic + WordPiece tokenization, keeping where every word came from.

The rules are those of the original BERT tokenizer for an uncased vocabulary: drop control characters, split
on whitespace, lower-case, strip accents (NFD without combining marks), make every punctuation mark and CJK
character its own token, then cut each word greedily into the longest vocabulary pieces. Each word keeps its
[begin, end) offsets in the source text, so a caller can point at a word by its span and get its pieces.
tests/test_hf_conformance.py compares the piece ids with transformers' BertTokenizer when it is installed.
"""
from __future__ import annotations

import unicodedata

MAX_WORD_CHARS = 100


def _whitespace(ch: str) -> bool:
    return ch in ' \t\n\r' or unicodedata.category(ch) == 'Zs'


def _control(ch: str) -> bool:
    return ch not in '\t\n\r' and unicodedata.category(ch).startswith('C')


def _punctuation(ch: str) -> bool:
    cp = ord(ch)
    if 33 <= cp <= 47 or 58 <= cp <= 64 or 91 <= cp <= 96 or 123 <= cp <= 126:
        return True
    return unicodedata.category(ch).startswith('P')


def _cjk(cp: int) -> bool:
    return (0x4E00 <= cp <= 0x9FFF or 0x3400 <= cp <= 0x4DBF or 0x20000 <= cp <= 0x2A6DF or 0x2A700 <= cp <= 0x2B73F
            or 0x2B740 <= cp <= 0x2B81F or 0x2B820 <= cp <= 0x2CEAF or 0xF900 <= cp <= 0xFAFF or 0x2F800 <= cp <= 0x2FA1F)


def basic(text: str, lower: bool = True) -> list[tuple[str, int, int]]:
    """Words as (normalized text, begin, end) with offsets into `text`."""
    words: list[tuple[str, int, int]] = []
    cur: list[str] = []
    start = end = 0

    def flush():
        nonlocal cur
        if cur:
            words.append((''.join(cur), start, end))
            cur = []

    for i, ch in enumerate(text):
        cp = ord(ch)
        if cp == 0 or cp == 0xFFFD or _control(ch):
            continue
        if _whitespace(ch):
            flush()
            continue
        if _cjk(cp):
            flush()
            words.append((ch, i, i + 1))
            continue
        out = ch
        if lower:
            out = ''.join(c for c in unicodedata.normalize('NFD', ch.lower()) if unicodedata.category(c) != 'Mn')
        for c in out:
            if _punctuation(c):
                flush()
                words.append((c, i, i + 1))
            else:
                if not cur:
                    start = i
                cur.append(c)
                end = i + 1
    flush()
    return words


class WordPiece:
    def __init__(self, vocab: dict[str, int], lower: bool = True, unk: str = '[UNK]'):
        self.vocab, self.lower, self.unk = vocab, lower, unk
        for special in ('[CLS]', '[SEP]', '[MASK]', unk):
            if special not in vocab:
                raise ValueError(f'vocabulary lacks {special}')
        self.cls, self.sep, self.mask, self.unk_id = vocab['[CLS]'], vocab['[SEP]'], vocab['[MASK]'], vocab[unk]
        self.words = [''] * (max(vocab.values()) + 1)
        for w, i in vocab.items():
            self.words[i] = w

    @classmethod
    def from_file(cls, path, lower: bool = True) -> 'WordPiece':
        with open(path, encoding='utf-8') as f:
            vocab = {line.rstrip('\n'): i for i, line in enumerate(f)}
        return cls(vocab, lower)

    def pieces(self, word: str) -> list[int]:
        if len(word) > MAX_WORD_CHARS:
            return [self.unk_id]
        out, start = [], 0
        while start < len(word):
            end, found = len(word), None
            while start < end:
                piece = word[start:end] if start == 0 else '##' + word[start:end]
                if piece in self.vocab:
                    found = self.vocab[piece]
                    break
                end -= 1
            if found is None:
                return [self.unk_id]
            out.append(found)
            start = end
        return out

    def encode(self, text: str):
        """(ids with [CLS] and [SEP], words): each word is (begin, end, first piece index, piece count)."""
        ids, words = [self.cls], []
        for word, begin, end in basic(text, self.lower):
            p = self.pieces(word)
            words.append((begin, end, len(ids), len(p)))
            ids.extend(p)
        ids.append(self.sep)
        return ids, words

    def whole(self, i: int) -> bool:
        """A whole lower-case word: no ## continuation, no bracketed special, letters only."""
        w = self.words[i]
        return bool(w) and w.isascii() and w.isalpha() and w.islower()
