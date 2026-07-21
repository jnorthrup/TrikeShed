import mlx.core as mx
import mlx.nn as nn
import numpy as np
import pytest
from hrm.apple.hrm import Attention, RoPE

def test_attention_init():
    dim = 64
    n_heads = 4
    attn = Attention(dim, n_heads)
    assert attn.n_heads == n_heads
    assert attn.head_dim == dim // n_heads
    assert pytest.approx(attn.scale) == (dim // n_heads) ** -0.5

def test_rotate_half():
    dim = 4
    n_heads = 1
    attn = Attention(dim, n_heads)
    x = mx.array([[1, 2, 3, 4]])
    expected = mx.array([[-3, -4, 1, 2]])
    rotated = attn._rotate_half(x)
    assert mx.array_equal(rotated, expected)

def test_attention_output_shape():
    batch_size = 2
    seq_len = 8
    dim = 32
    n_heads = 4
    
    attn = Attention(dim, n_heads)
    rope = RoPE(dim // n_heads, seq_len)
    cos, sin = rope(seq_len)
    
    x = mx.random.normal((batch_size, seq_len, dim))
    out = attn(x, cos, sin)
    
    assert out.shape == (batch_size, seq_len, dim)

def test_attention_functional():
    # Test for no NaNs
    dim = 16
    n_heads = 2
    seq_len = 4
    attn = Attention(dim, n_heads)
    rope = RoPE(dim // n_heads, seq_len)
    cos, sin = rope(seq_len)
    
    # Zero input
    x_zeros = mx.zeros((1, seq_len, dim))
    out_zeros = attn(x_zeros, cos, sin)
    assert not mx.isnan(out_zeros).any()
    
    # Large input
    x_large = mx.random.normal((1, seq_len, dim)) * 100.0
    out_large = attn(x_large, cos, sin)
    assert not mx.isnan(out_large).any()

def test_attention_determinism():
    mx.random.seed(42)
    dim = 16
    n_heads = 2
    seq_len = 4
    attn = Attention(dim, n_heads)
    rope = RoPE(dim // n_heads, seq_len)
    cos, sin = rope(seq_len)
    
    x = mx.random.normal((1, seq_len, dim))
    out1 = attn(x, cos, sin)
    out2 = attn(x, cos, sin)
    
    assert mx.array_equal(out1, out2)

def test_rope_impact():
    # Verify that RoPE actually affects the output
    mx.random.seed(42)
    dim = 16
    n_heads = 2
    seq_len = 4
    attn = Attention(dim, n_heads)
    
    x = mx.random.normal((1, seq_len, dim))
    
    # Case 1: Standard RoPE
    rope = RoPE(dim // n_heads, seq_len)
    cos, sin = rope(seq_len)
    out_rope = attn(x, cos, sin)
    
    # Case 2: Zero RoPE (no rotation)
    cos_zero = mx.ones((seq_len, dim // n_heads))
    sin_zero = mx.zeros((seq_len, dim // n_heads))
    out_no_rope = attn(x, cos_zero, sin_zero)
    
    # They should be different
    assert not mx.allclose(out_rope, out_no_rope)
