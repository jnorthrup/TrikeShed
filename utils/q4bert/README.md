# utils/q4bert: a 4-bit BERT that scores a masked position and writes nothing

A BERT encoder with its masked-language-model head, quantized once from a float32 checkpoint already on
disk and run offline. It answers one question about one position: which words does the context put where
this word stands? The curation engine asks it about operator cues (`if`, `may`, `not`, `result`, ...):
`utils/stanza/operators.py` reads an operator off the parse tree, and this lane says whether the context
alone would have predicted that cue. The answer is evidence beside the operator. It decides nothing.

It is an encoder, not a language model that generates: there is no decoding loop, and every reply reports
`outputTokens: 0`. It is still a neural model, with the errors of one.

## Pieces

    q4bert/quant.py       affine group-wise quantization in MLX's packed layout (numpy)
    q4bert/st.py          safetensors read and write, deterministic (numpy)
    q4bert/wordpiece.py   BERT basic + WordPiece tokenization that keeps source offsets (stdlib)
    q4bert/model.py       the forward pass, the masked-cue question, bundle loading (numpy + torch CPU)
    quantize.py           float32 snapshot directory -> bundle directory
    worker.py             the lane as a local worker: JSON lines on stdin and stdout
    fidelity.py           what 4 bits changed on a set of masks, against the float32 checkpoint

Dependencies are numpy and torch. `transformers` and `mlx` are used only by the conformance tests, as
references to compare against.

## Make a bundle

    python quantize.py --source <snapshot dir> --out <bundle dir>

`<snapshot dir>` holds `model.safetensors`, `config.json` and `vocab.txt`. Nothing is downloaded, at build
or at run time: a missing file is an error. Linear weights are quantized at 4 bits in groups of 32 and the
three embedding tables at 8 bits in groups of 64; biases and LayerNorm stay float32. The pooler and the
next-sentence head are left out. The masked-language-model decoder stays tied to the word embeddings.

The bundle is `model.safetensors`, `config.json`, `vocab.txt`, `provenance.json` and `SHA256SUMS`. Nothing
in it carries a date, so the same source gives the same bytes. `open_bundle` checks every file against
`SHA256SUMS` before loading and refuses a bundle in another quantization layout.

The bundle used by `utils/lcnc-demo`, measured on 2026-11-19:

| | |
|---|---|
| source | `nlpaueb/legal-bert-base-uncased`, snapshot `418b58a0`, 440,449,796 bytes of float32 weights |
| bundle | 91,616,224 bytes (20.8%), `model.safetensors` sha256 `6dc270f890b9dfc1…` |
| quantized | 76 tensors, 109,360,128 weights, 6.65 stored bits per weight (the integers plus a float32 scale and bias per group) |
| weight error | relative Frobenius: linear layers mean 0.083, max 0.087; embeddings max 0.009 |
| reproducible | quantizing the same snapshot again gives a byte-identical bundle |

Four bits is a property of the file. `load_bundle` restores dense float32 weights once at load
(`w = q * scale + bias`, what a quantized matmul computes with), so memory and speed at run time are those
of the float32 model: about 45 ms per masked position on 4 CPU threads here, for either.

## Ask it

    -> {"op": "init", "bundle": DIR, "max_tokens": 512, "threads": 4}
    -> {"op": "fill", "classes": {"condition": ["if", "provided", ...], ...},
        "items": [{"key": "op-17-0", "text": SENTENCE, "begin": 0, "end": 2}]}
    <- {"results": [{"key": "op-17-0", "status": "ok", "cue": "if", "p": 0.872146, "rank": 1,
                     "top": [["if", 0.872146], ["or", 0.039616], ...],
                     "classMass": {"condition": 0.873224, ...}, "preferredClass": "condition",
                     "cueClass": "condition", "concordant": true}],
        "usage": {"sequences": 1, "inputTokens": 35, "outputTokens": 0, "seconds": 0.05}}

`begin`/`end` are the cue's code-point span inside `text`; the words covering it become one `[MASK]`.
`p` and `rank` are the cue word's own probability and rank there. `classMass` sums the probability of each
class's words, and `concordant` says whether the heaviest class is the cue's own. A cue the vocabulary
splits into several pieces has no single probability: `p` and `rank` are null and only the classes are
reported.

Refusals are results, not crashes. A sequence over the token limit comes back
`{"status": "refused", "reason": "1322 tokens; limit is 512; truncation is disabled"}` and the worker goes
on serving. Nothing is ever cut to fit.

## What was checked

    python -m unittest discover -s tests          # 31 tests; numpy and torch
    TRIKESHED_MLX_PYTHON=<python with mlx> ...    # adds the MLX comparison

The MLX comparison is skipped without `TRIKESHED_MLX_PYTHON`, the transformers comparison without
`transformers` or the cached checkpoint; neither downloads anything.

- `quant.quantize` equals `mlx.core.quantize` (MLX 0.32.0) bit for bit: packed integers, scales and biases,
  on three seeded sweeps of 60 matrices each, with same-sign, constant, zero and tie-heavy groups mixed in.
  Three details had to be matched and are pinned by tests: an all-negative group's range is extended up
  to zero (`max(w_max, 0)`) while an all-positive group's is not extended down, halves round away from
  zero, and that rounding must not go through `x + 0.5`.
- Tokenization equals `transformers.BertTokenizer` on text with curly quotes, accents, URLs, section signs,
  a no-break space and a zero-width space.
- The float32 forward pass equals `transformers.BertForMaskedLM` to 4.8e-5 in the logits over 18 masked
  positions (transformers 5.19.0, torch 2.14.1).
- A tiny random model goes through quantize, load, fill, refuse and serve in the unit tests. Random weights
  say nothing about language; those tests check contracts.

## What 4 bits changed

`fidelity.py` fills the same masks with the bundle and with the float32 checkpoint it came from. On the 173
operator cues of the demo's retained slice:

| | 4-bit | float32 |
|---|---|---|
| cue word is the top word | 80.9% | 79.8% |
| heaviest class is the cue's class | 86.1% | 86.1% |
| mean probability of the cue word | 0.729 | 0.736 |

Position by position the two agree on the top word for 164 of 173 masks and on the heaviest class for 166.
The cue probability moves by 0.038 on average and by 0.289 at most; KL(float32 || 4-bit) is 0.028 nats on
average, 0.007 at the median, 0.37 at most. On 6 masks the difference flips supported/contested, three
each way, which is why the totals match. This measures agreement with the float32 model on one slice of
one agreement. It is not accuracy, and no labelled set stands behind any number here.

## Boundaries

- The packed layout is the one `mx.quantize` writes and TrikeShed's `MlxQuantized` describes. No Kotlin
  code loads this bundle; the lane runs in Python only.
- The cue classes are a small hand-written lexicon (`legalframes/operators.py: CUE_CLASSES`). A cue outside
  it gets no evidence at all.
- One checkpoint was quantized. Others with the BERT layout and GELU should work; none was tried.
