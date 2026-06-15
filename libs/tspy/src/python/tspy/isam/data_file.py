"""
tspy.isam.data_file — ISAM Data File Reader/Writer

Port of Kotlin IsamDataFile for reading/writing binary ISAM data files.
"""

from __future__ import annotations

import os
from typing import Any, Callable, Dict, Iterable, Optional

from ..algebra import Series, s_
from ..cursor import RowVec, _RowVec, Cursor, _Cursor, row_cell, ColumnMeta, row_vec, cursor
from .meta import IOMemento
from .record_meta import RecordMeta
from .meta_file import IsamMetaFileReader
from .wire_proto import write_to_buffer, read_from_buffer


class IsamDataFile:
    """
    ISAM Data File - implements Cursor = Series<RowVec> interface.
    
    Reads fixed-length records from a binary data file using RecordMeta from metafile.
    """
    
    def __init__(
        self,
        datafile_filename: str,
        metafile_filename: Optional[str] = None,
        meta: Optional[IsamMetaFileReader] = None,
    ):
        self.datafile_filename = datafile_filename
        self.metafile_filename = metafile_filename or f"{datafile_filename}.meta"
        
        if meta is not None:
            self._meta = meta
        else:
            self._meta = IsamMetaFileReader(self.metafile_filename)
        
        self._file: Optional[Any] = None
        self._recordlen = self._meta.recordlen
        self._size = self._compute_size()
    
    def _compute_size(self) -> int:
        """Compute number of records in data file."""
        if not os.path.exists(self.datafile_filename):
            return 0
        file_size = os.path.getsize(self.datafile_filename)
        return file_size // self._recordlen if self._recordlen > 0 else 0
    
    @property
    def size(self) -> int:
        return self._size
    
    @property
    def meta(self) -> IsamMetaFileReader:
        return self._meta
    
    @property
    def constraints(self) -> Series[RecordMeta]:
        return self._meta.constraints
    
    def open(self) -> None:
        """Open data file for reading."""
        if self._file is None:
            self._file = open(self.datafile_filename, 'rb')
    
    def close(self) -> None:
        """Close data file."""
        if self._file is not None:
            self._file.close()
            self._file = None
    
    def __enter__(self) -> 'IsamDataFile':
        self.open()
        return self
    
    def __exit__(self, exc_type, exc_val, exc_tb) -> None:
        self.close()
    
    # Cursor interface: Cursor = Series<RowVec>
    def __getitem__(self, index: int) -> _RowVec:
        """Get row by index."""
        if self._file is None:
            self.open()
        
        if not 0 <= index < self._size:
            raise IndexError(f"Row index {index} out of range [0, {self._size})")
        
        self._file.seek(index * self._recordlen)
        row_buf = self._file.read(self._recordlen)
        if len(row_buf) < self._recordlen:
            row_buf = row_buf.ljust(self._recordlen, b'\x00')
        
        return read_from_buffer(row_buf, self.constraints)
    
    def __iter__(self):
        """Iterate over all rows."""
        self.open()
        for i in range(self._size):
            yield self[i]
    
    def __len__(self) -> int:
        return self._size
    
    # Cursor range view
    def range(self, start: int, end: int) -> '_Cursor':
        """Return range view as Cursor."""
        count = max(0, min(end, self._size) - start)
        rows = [self[start + i] for i in range(count)]
        return cursor(*rows)
    
    # Lazy projection (alpha)
    def alpha(self, xform: Callable[[_RowVec], Any]) -> Series:
        """Lazy map over rows."""
        from ..algebra import Series
        return Series(self._size, lambda i: xform(self[i]))
    
    @classmethod
    def write(
        cls,
        cursor: _Cursor,
        datafilename: str,
        varchars: Dict[str, int] = {},
        metafile_filename: Optional[str] = None,
    ) -> IsamMetaFileReader:
        """
        Write cursor to ISAM data file and metafile.
        
        Args:
            cursor: Cursor = Series<RowVec> to write
            datafilename: Output data file path
            varchars: Variable-length column sizes {col_name: size}
            metafile_filename: Optional custom metafile path
            
        Returns:
            IsamMetaFileReader for the written files
        """
        metafile = metafile_filename or f"{datafilename}.meta"
        
        # Sanitize and write metafile
        meta = IsamMetaFileReader.write(metafile, cursor[0].meta() if hasattr(cursor[0], 'meta') else 
                                        Series(cursor[0].size, lambda c: cursor[0][c][1]()), varchars)
        
        recordlen = meta[-1].end
        
        # Write data file
        with open(datafilename, 'wb') as f:
            row_buf = bytearray(recordlen)
            for row in cursor:
                write_to_buffer(row, row_buf, meta)
                f.write(row_buf)
        
        return meta
    
    @classmethod
    def append(
        cls,
        rows: Iterable[_RowVec],
        datafilename: str,
        varchars: Dict[str, int] = {},
        transform: Optional[Callable[[_RowVec], _RowVec]] = None,
    ) -> IsamMetaFileReader:
        """
        Append rows to existing ISAM file (creates if not exists).
        
        Args:
            rows: Iterable of RowVec to append
            datafilename: Data file path
            varchars: Variable-length column sizes
            transform: Optional transform to apply to each row before writing
            
        Returns:
            IsamMetaFileReader
        """
        metafile = f"{datafilename}.meta"
        
        # Read existing meta or create from first row
        if os.path.exists(metafile):
            meta_reader = IsamMetaFileReader(metafile)
            meta = meta_reader.constraints
        else:
            # Need first row to infer schema
            rows_list = list(rows)
            if not rows_list:
                raise ValueError("Cannot append empty rows without existing metafile")
            
            first_row = rows_list[0]
            # Build ColumnMeta from first row's thunks
            col_metas = [first_row[c][1]() for c in range(first_row.size)]
            meta = IsamMetaFileReader.write(metafile, s_(*col_metas), varchars)
            rows = rows_list  # Use collected rows
        
        recordlen = meta[-1].end
        
        # Append to data file
        with open(datafilename, 'ab') as f:
            row_buf = bytearray(recordlen)
            for row in rows:
                if transform:
                    row = transform(row)
                write_to_buffer(row, row_buf, meta)
                f.write(row_buf)
        
        return IsamMetaFileReader(metafile)
    
    def __repr__(self) -> str:
        return f"IsamDataFile(datafile='{self.datafile_filename}', size={self._size}, recordlen={self._recordlen})"