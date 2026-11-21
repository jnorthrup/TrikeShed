"""A 4-bit BERT for masked-token evidence: quantize once, run offline, generate nothing.

    st          safetensors read/write (numpy only)
    quant       affine group-wise quantization in MLX's packed layout
    wordpiece   BERT basic + WordPiece tokenization with source offsets
    model       encoder forward pass and the masked-language-model head over dequantized weights
"""

__version__ = "0.1.0"
