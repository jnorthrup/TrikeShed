"""
tspy.isam.record_meta — ISAM Record Metadata

Port of Kotlin RecordMeta data class with begin/end offsets, encoder/decoder.
"""

from __future__ import annotations

from typing import Any, Callable, Optional
from dataclasses import dataclass, field

from tspy.cursor import ColumnMeta, TypeMemento, ColumnMetaThunk
from tspy.isam.meta import IOMemento, iomemento_from_name


@dataclass(frozen=True)
class RecordMeta(ColumnMeta):
    """
    RecordMeta describes a column of an ISAM record.
    
    Extends ColumnMeta with fixed offsets and encoder/decoder functions.
    """
    name: str
    type: IOMemento
    begin: int = -1
    end: int = -1
    decoder: Callable[[bytes], Any] = field(default=None, repr=False)
    encoder: Callable[[Any], bytes] = field(default=None, repr=False)
    child: Optional['RecordMeta'] = None
    group_id: int = 0
    group_name: str = "0"

    def __post_init__(self):
        # Auto-create encoder/decoder if not provided
        if self.decoder is None:
            object.__setattr__(self, 'decoder', self.type.create_decoder(self.end - self.begin))
        if self.encoder is None:
            object.__setattr__(self, 'encoder', self.type.create_encoder(self.end - self.begin))

    @property
    def size(self) -> int:
        return self.end - self.begin

    @classmethod
    def from_column_meta(cls, col: ColumnMeta, begin: int, end: int, 
                          group_id: int = 0, group_name: str = "0") -> 'RecordMeta':
        """Create RecordMeta from generic ColumnMeta with computed offsets."""
        iomemento = col.type if isinstance(col.type, IOMemento) else iomemento_from_name(col.type.__class__.__name__)
        return cls(
            name=col.name,
            type=iomemento,
            begin=begin,
            end=end,
            child=col.child,
            group_id=group_id,
            group_name=group_name,
        )

    def __repr__(self) -> str:
        return (f"RecordMeta(name='{self.name}', type={self.type.name}, "
                f"begin={self.begin}, end={self.end}, group_id={self.group_id}, group_name='{self.group_name}')")