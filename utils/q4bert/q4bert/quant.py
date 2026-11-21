"""Affine group-wise quantization in MLX's packed layout.

A weight matrix [out, in] is cut along `in` into groups of `group_size`. Each group keeps a float32 scale and
bias and stores its weights as `bits`-bit integers q, with w ~ q * scale + bias. The integers are packed
little-end first, 32 / bits to a uint32, giving `weight` [out, in * bits / 32] with `scales` and `biases`
[out, in / group_size]: the layout of mlx.core.quantize and of TrikeShed's MlxQuantized.

The arithmetic reproduces mx.quantize (affine mode, MLX 0.32.0) in float32, as measured against it: the
group's range is taken up to zero from below (max(w_max, 0); the minimum is not moved), the scale's sign
follows the larger-magnitude edge, the scale is re-fit so that edge is hit exactly, and halves round away
from zero. tests/test_mlx_conformance.py compares packed integers, scales and biases with mx.quantize itself
when an MLX interpreter is available.
"""
from __future__ import annotations

import numpy as np


def round_half_away(x: np.ndarray) -> np.ndarray:
    """std::round: halves away from zero (numpy's rint rounds them to even)."""
    t = np.trunc(x)          # x - t is exact in float32; adding 0.5 first would round large values twice
    return np.where(np.abs(x - t) >= np.float32(0.5), t + np.sign(x), t).astype(np.float32)


def quantize(w: np.ndarray, group_size: int, bits: int):
    if w.ndim != 2 or w.shape[1] % group_size or 32 % bits or not 2 <= bits <= 8:
        raise ValueError(f'cannot quantize shape {w.shape} with group_size={group_size}, bits={bits}')
    w = np.ascontiguousarray(w, dtype=np.float32)
    bins = np.float32((1 << bits) - 1)
    flat = w.reshape(-1, group_size)
    w_max = np.maximum(flat.max(axis=1, keepdims=True), np.float32(0))
    w_min = flat.min(axis=1, keepdims=True)
    mask = np.abs(w_min) > np.abs(w_max)
    scales = np.maximum((w_max - w_min) / bins, np.float32(1e-7))
    scales = np.where(mask, scales, -scales)
    edge = np.where(mask, w_min, w_max)
    q0 = round_half_away(edge / scales)
    nonzero = q0 != 0
    scales = np.where(nonzero, edge / np.where(nonzero, q0, np.float32(1)), scales).astype(np.float32)
    biases = np.where(nonzero, edge, np.float32(0)).astype(np.float32)
    q = np.clip(round_half_away((flat - biases) / scales), 0, bins).astype(np.uint32)
    per = 32 // bits
    shifts = np.arange(per, dtype=np.uint32) * np.uint32(bits)
    packed = (q.reshape(w.shape[0], -1, per) << shifts).sum(axis=2, dtype=np.uint32)
    return packed, scales.reshape(w.shape[0], -1), biases.reshape(w.shape[0], -1)


def unpack(packed: np.ndarray, bits: int) -> np.ndarray:
    """The integers q, [out, in]."""
    per = 32 // bits
    shifts = np.arange(per, dtype=np.uint32) * np.uint32(bits)
    return ((packed[..., None] >> shifts) & np.uint32((1 << bits) - 1)).reshape(packed.shape[0], -1)


def dequantize(packed: np.ndarray, scales: np.ndarray, biases: np.ndarray, group_size: int, bits: int) -> np.ndarray:
    q = unpack(packed, bits).astype(np.float32)
    if q.shape[1] != scales.shape[1] * group_size or scales.shape != biases.shape:
        raise ValueError('packed weight, scales and biases disagree on shape')
    out = q.reshape(q.shape[0], -1, group_size) * scales[..., None] + biases[..., None]
    return out.reshape(q.shape[0], -1)


def error(w: np.ndarray, restored: np.ndarray) -> dict:
    """How far the restored matrix is from the original, relative to the original's own size."""
    diff = restored.astype(np.float64) - w.astype(np.float64)
    norm = float(np.linalg.norm(w.astype(np.float64)))
    return {'relativeFrobenius': float(np.linalg.norm(diff)) / norm if norm else 0.0,
            'maxAbs': float(np.abs(diff).max())}


def bits_per_weight(shape, group_size: int, bits: int) -> float:
    """Stored bits per original weight: the packed integers plus a float32 scale and bias per group."""
    return bits + 64.0 / group_size
