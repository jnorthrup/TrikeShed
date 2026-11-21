"""safetensors, read and written with numpy alone.

Layout: u64 little-endian header length, a JSON header mapping each tensor to dtype, shape and byte range,
then the raw little-endian buffers. Writing is deterministic: tensors in name order, compact sorted header,
no timestamps, so the same tensors give the same file.
"""
from __future__ import annotations

import json
import struct

import numpy as np

DTYPES = {'F32': np.float32, 'F16': np.float16, 'F64': np.float64, 'U32': np.uint32, 'I32': np.int32,
          'I64': np.int64, 'U8': np.uint8, 'I8': np.int8, 'U16': np.uint16, 'I16': np.int16, 'BOOL': np.bool_}
NAMES = {np.dtype(v): k for k, v in DTYPES.items()}
MAX_HEADER = 100 * 1024 * 1024


def read_header(path) -> tuple[dict, int]:
    with open(path, 'rb') as f:
        raw = f.read(8)
        if len(raw) != 8:
            raise ValueError(f'{path}: not a safetensors file')
        n = struct.unpack('<Q', raw)[0]
        if not 2 <= n <= MAX_HEADER:
            raise ValueError(f'{path}: header length {n} out of bounds')
        header = json.loads(f.read(n).decode('utf-8'))
    return header, 8 + n


def load(path, names=None) -> dict:
    """Tensors by name (all, or those in `names`), copied into memory in native little-endian layout."""
    header, base = read_header(path)
    out = {}
    with open(path, 'rb') as f:
        for name, info in header.items():
            if name == '__metadata__' or (names is not None and name not in names):
                continue
            dtype = np.dtype(DTYPES[info['dtype']]).newbyteorder('<')
            begin, end = info['data_offsets']
            count = int(np.prod(info['shape'], dtype=np.int64)) if info['shape'] else 1
            if end - begin != count * dtype.itemsize:
                raise ValueError(f'{path}: {name} byte range does not match its shape')
            f.seek(base + begin)
            out[name] = np.fromfile(f, dtype=dtype, count=count).reshape(info['shape'])
    return out


def metadata(path) -> dict:
    return read_header(path)[0].get('__metadata__') or {}


def save(path, tensors: dict, meta: dict | None = None) -> None:
    header, offset, order = {}, 0, sorted(tensors)
    for name in order:
        a = tensors[name]
        if a.dtype not in NAMES:
            raise ValueError(f'{name}: dtype {a.dtype} has no safetensors name')
        header[name] = {'dtype': NAMES[a.dtype], 'shape': list(a.shape), 'data_offsets': [offset, offset + a.nbytes]}
        offset += a.nbytes
    if meta:
        header['__metadata__'] = {str(k): str(v) for k, v in sorted(meta.items())}
    raw = json.dumps(header, sort_keys=True, separators=(',', ':')).encode('utf-8')
    raw += b' ' * (-len(raw) % 8)
    with open(path, 'wb') as f:
        f.write(struct.pack('<Q', len(raw)))
        f.write(raw)
        for name in order:
            f.write(np.ascontiguousarray(tensors[name]).astype(tensors[name].dtype.newbyteorder('<'), copy=False).tobytes())
