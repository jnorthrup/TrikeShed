"""
tspy.isam.meta_file — ISAM Metafile Reader/Writer

Port of Kotlin IsamMetaFileReader for parsing metafile format.
"""

from __future__ import annotations

import re
from typing import Any, Dict, List, Optional, Tuple

from ..algebra import Series, s_
from ..cursor import ColumnMeta, ColumnMetaThunk
from .meta import IOMemento, iomemento_from_name
from .record_meta import RecordMeta
from .wire_proto import read_from_buffer, write_to_buffer


class IsamMetaFileReader:
    """
    Reads ISAM metafile and creates Series<RecordMeta> constraints.
    
    Metafile format:
    ```
    # format:  coords WS .. EOL names WS .. EOL TypeMemento WS .. [EOL groups]
    # last coord is the recordlen
    0 12 12 24 24 32 32 40 40 48 48 56 56 64 64 72 72 76 76 84 84 92
    Open_time Close_time Open High Low Close Volume Quote_asset_volume Number_of_trades Taker_buy_base_asset_volume Taker_buy_quote_asset_volume
    IoInstant IoInstant IoDouble IoDouble IoDouble IoDouble IoDouble IoDouble IoInt IoDouble IoDouble
    0:0 1-4,6-7:2 15,16:varchars
    ```
    """
    
    def __init__(self, metafile_filename: str):
        self.metafile_filename = metafile_filename
        self._constraints: Optional[Series[RecordMeta]] = None
        self._recordlen: Optional[int] = None
    
    @property
    def recordlen(self) -> int:
        if self._recordlen is None:
            self._load()
        return self._recordlen
    
    @property
    def constraints(self) -> Series[RecordMeta]:
        if self._constraints is None:
            self._load()
        return self._constraints
    
    def _load(self) -> None:
        """Load and parse metafile."""
        with open(self.metafile_filename, 'r') as f:
            lines = [line.rstrip('\n') for line in f if not line.strip().startswith('#')]
        
        if len(lines) < 3:
            raise ValueError("Metafile must have at least 3 lines: coords, names, types")
        
        # Parse coords line (pairs of begin/end)
        coords_str = lines[0].strip()
        coords = [int(x) for x in coords_str.split()]
        if len(coords) % 2 != 0:
            raise ValueError("Coords must be pairs of begin end")
        
        # Parse names line
        names = lines[1].strip().split()
        
        # Parse types line
        types = lines[2].strip().split()
        
        # Parse groups line (optional)
        groups_line = lines[3].strip() if len(lines) > 3 else ""
        group_series = self._parse_groups_line(groups_line, len(names)) if groups_line else \
            Series(len(names), lambda idx: (idx, "0"))
        
        # Build RecordMeta series
        record_metas = []
        for idx in range(len(names)):
            name = names[idx]
            type_name = types[idx] if idx < len(types) else "IoString"
            
            begin = coords[2 * idx]
            end = coords[2 * idx + 1]
            
            group_id, group_name = group_series[idx]
            
            io_memento = iomemento_from_name(type_name)
            decoder = io_memento.create_decoder(end - begin)
            encoder = io_memento.create_encoder(end - begin)
            
            record_meta = RecordMeta(
                name=name,
                type=io_memento,
                begin=begin,
                end=end,
                decoder=decoder,
                encoder=encoder,
                group_id=group_id,
                group_name=group_name,
            )
            record_metas.append(record_meta)
        
        self._constraints = s_(*record_metas)
        self._recordlen = self._constraints[-1].end if self._constraints.size > 0 else 0
    
    def _parse_groups_line(self, line: str, col_count: int) -> Series[Tuple[int, str]]:
        """
        Parse groups line per EBNF:
        groups    := (groupSpec WS)* groupSpec
        groupSpec := colList ':' groupName
        colList   := (colSpec ',')* colSpec
        colSpec   := colIdx | colIdx '-' colIdx
        groupName := number | [A-Za-z][A-Za-z0-9_\-@]*
        """
        col_to_group: Dict[int, str] = {}
        mentioned_groups: List[str] = []
        
        tokens = line.strip().split()
        for token in tokens:
            colon_idx = token.rfind(':')
            if colon_idx < 0:
                continue
            col_list_str = token[:colon_idx]
            group_name = token[colon_idx + 1:]
            
            if group_name not in mentioned_groups:
                mentioned_groups.append(group_name)
            
            # Expand colList
            col_specs = col_list_str.split(',')
            for spec in col_specs:
                spec = spec.strip()
                if '-' in spec:
                    lo_str, hi_str = spec.split('-', 1)
                    lo, hi = int(lo_str), int(hi_str)
                    for k in range(lo, hi + 1):
                        col_to_group[k] = group_name
                else:
                    col_to_group[int(spec)] = group_name
        
        implicit_name = str(len(mentioned_groups))
        return Series(col_count, lambda idx: (idx, col_to_group.get(idx, implicit_name)))
    
    @classmethod
    def write(
        cls,
        metafilename: str,
        record_metas: Series[ColumnMeta],
        varchars: Dict[str, int],
    ) -> Series[RecordMeta]:
        """Write metafile from RecordMeta series."""
        result = cls._sanitize(record_metas, varchars)
        
        lines = [
            "# format:  coords WS .. EOL names WS .. EOL TypeMemento WS .. [EOL]",
            "# last coord is the recordlen",
            " ".join(f"{rm.begin} {rm.end}" for rm in result),
            " ".join(rm.name for rm in result),
            " ".join(rm.type.name for rm in result),
        ]
        
        # Emit groups line if more than one distinct group_id
        distinct_groups = set(rm.group_id for rm in result)
        if len(distinct_groups) > 1:
            max_group_id = max(distinct_groups)
            
            # Build group_name -> sorted column indices (excluding max group)
            by_group: Dict[str, List[int]] = {}
            for idx, rm in enumerate(result):
                if rm.group_id != max_group_id:
                    by_group.setdefault(rm.group_name, []).append(idx)
            
            group_tokens = []
            for gname, cols in by_group.items():
                group_tokens.append(f"{cls._build_col_list(cols)}:{gname}")
            
            if group_tokens:
                lines.append(" ".join(group_tokens))
        
        with open(metafilename, 'w') as f:
            f.write('\n'.join(lines) + '\n')
        
        return result
    
    @staticmethod
    def _build_col_list(cols: List[int]) -> str:
        """Convert sorted column indices to compact notation (e.g., [0,1,2,5] -> '0-2,5')."""
        if not cols:
            return ""
        sorted_cols = sorted(cols)
        sb = []
        start = prev = sorted_cols[0]
        
        def flush():
            if start == prev:
                sb.append(str(start))
            else:
                sb.append(f"{start}-{prev}")
        
        for c in sorted_cols[1:]:
            if c == prev + 1:
                prev = c
            else:
                flush()
                start = prev = c
        flush()
        
        return ",".join(sb)
    
    @classmethod
    def _sanitize(cls, record_metas: Series[ColumnMeta], varchars: Dict[str, int]) -> Series[RecordMeta]:
        """Convert generic ColumnMeta to RecordMeta with computed offsets."""
        # Check if already valid RecordMeta with offsets
        needs_recalc = any(
            not isinstance(rm, RecordMeta) or 
            (min(rm.begin, rm.end) < 0 and rm.child is None)
            for rm in record_metas
        )
        
        if not needs_recalc:
            return record_metas  # type: ignore
        
        # Recalculate offsets
        offset = 0
        result = []
        for col in record_metas:
            name = str(col.name)
            col_type = col.type if isinstance(col.type, IOMemento) else iomemento_from_name(col.type.__class__.__name__)
            length = col_type.network_size or varchars.get(name)
            
            if length is None:
                raise ValueError(f"No network size for column {name}")
            
            group_id = getattr(col, 'group_id', 0)
            group_name = getattr(col, 'group_name', str(group_id))
            
            record_meta = RecordMeta(
                name=name,
                type=col_type,
                begin=offset,
                end=offset + length,
                group_id=group_id,
                group_name=group_name,
            )
            offset += length
            result.append(record_meta)
        
        return s_(*result)
    
    def __repr__(self) -> str:
        return f"IsamMetaFileReader(metafile='{self.metafile_filename}', recordlen={self.recordlen}, constraints={self.constraints})"