"""
tspy.isam.wire_proto — ISAM Wire Protocol

Port of Kotlin WireProto for encoding/decoding RowVec to/from byte buffers.
"""

from __future__ import annotations

from typing import Any

from tspy.algebra import Series
from tspy.cursor import RowVec, _RowVec, ColumnMetaThunk, _row_vec
from tspy.isam.record_meta import RecordMeta


def write_to_buffer(
    row_vec: _RowVec,
    row_buf: bytearray,
    meta: Series[RecordMeta],
) -> bytearray:
    """
    Write a RowVec to a pre-allocated bytearray buffer using RecordMeta offsets.
    
    Args:
        row_vec: RowVec = Series<Join<Any, ColumnMetaThunk>> (values + metadata thunks)
        row_buf: Pre-allocated bytearray of record length
        meta: Series of RecordMeta describing each column
        
    Returns:
        The modified row_buf
    """
    row_data = row_vec  # RowVec is Series<Join<value, thunk>>
    
    # Clear buffer first
    row_buf[:] = b'\x00' * len(row_buf)
    
    for x in range(meta.size):
        col_meta: RecordMeta = meta[x]
        # row_vec[x] = Join<value, thunk>
        col_data = row_data[x][0]  # Get the value from Join
        
        pos = col_meta.begin
        col_bytes = col_meta.encoder(col_data)
        
        # Copy encoded bytes into buffer at column offset
        end_pos = pos + len(col_bytes)
        field_size = col_meta.end - col_meta.begin
        if end_pos <= len(row_buf):
            row_buf[pos:end_pos] = col_bytes
        else:
            # Truncate if buffer too small
            row_buf[pos:] = col_bytes[:len(row_buf) - pos]
        
        # For variable-width fields, ensure null-termination within field bounds
        if col_meta.type.network_size is None and end_pos < col_meta.end:
            if end_pos < len(row_buf):
                row_buf[end_pos] = 0
    
    return row_buf


def read_from_buffer(
    row_buf: bytes,
    meta: Series[RecordMeta],
) -> _RowVec:
    """
    Read a RowVec from a byte buffer using RecordMeta offsets.
    
    Args:
        row_buf: Byte buffer containing encoded record
        meta: Series of RecordMeta describing each column
        
    Returns:
        RowVec with decoded values
    """
    cells = []
    for x in range(meta.size):
        col_meta: RecordMeta = meta[x]
        pos = col_meta.begin
        end = min(col_meta.end, len(row_buf))
        col_bytes = row_buf[pos:end]
        col_value = col_meta.decoder(col_bytes)
        
        # Create cell: Join<value, thunk>
        thunk: ColumnMetaThunk = lambda cm=col_meta: cm
        cells.append((col_value, thunk))
    
    return _row_vec(*cells)