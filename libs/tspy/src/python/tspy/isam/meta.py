"""
tspy.isam.meta — ISAM IO Mementos (type evidence for column encoding/decoding)

Port of Kotlin IOMemento enum with encoder/decoder functions.
"""

from __future__ import annotations

from enum import Enum
from typing import Any, Callable, Optional
import struct

from ...algebra import Series
from ...cursor import ColumnMeta, TypeMemento, ColumnMetaThunk


class IOMemento(Enum):
    """
    Standard IO mementos — fixed-width types enable O(1) random access.
    Each variant carries network_size (None = variable width) and encoder/decoder factories.
    """
    IoBoolean = "bool"
    IoByte = "byte"
    IoUByte = "ubyte"
    IoShort = "short"
    IoUShort = "ushort"
    IoInt = "int"
    IoUInt = "uint"
    IoLong = "long"
    IoULong = "ulong"
    IoFloat = "float"
    IoDouble = "double"
    IoLocalDate = "localdate"  # epoch days as int64
    IoInstant = "instant"     # epoch seconds (8) + nanos (4)
    IoString = "string"       # null-terminated or length-prefixed
    IoCharSeries = "chars"
    IoByteArray = "bytes"
    IoNothing = "nothing"
    IoArray = "array"
    IoObject = "object"

    # Fixed network sizes (None = variable)
    _SIZES = {
        IoBoolean: 1,
        IoByte: 1,
        IoUByte: 1,
        IoShort: 2,
        IoUShort: 2,
        IoInt: 4,
        IoUInt: 4,
        IoLong: 8,
        IoULong: 8,
        IoFloat: 4,
        IoDouble: 8,
        IoLocalDate: 8,
        IoInstant: 12,
        IoString: None,
        IoCharSeries: None,
        IoByteArray: None,
        IoNothing: 0,
        IoArray: None,
        IoObject: None,
    }

    @property
    def network_size(self) -> Optional[int]:
        return self._SIZES[self]

    @property
    def kind(self) -> int:
        ns = self.network_size
        if ns is None:
            return 2  # variable width
        if ns <= 8:
            return 0  # primitive scalar
        return 1  # container

    @property
    def type_name(self) -> str:
        return self.value

    def create_encoder(self, size: int) -> Callable[[Any], bytes]:
        """Create encoder for this type with given field size."""
        match self:
            case IOMemento.IoBoolean:
                return lambda v: b'\x01' if v else b'\x00'
            case IOMemento.IoByte:
                return lambda v: struct.pack('>b', v)
            case IOMemento.IoUByte:
                return lambda v: struct.pack('>B', v)
            case IOMemento.IoShort:
                return lambda v: struct.pack('>h', v)
            case IOMemento.IoUShort:
                return lambda v: struct.pack('>H', v)
            case IOMemento.IoInt:
                return lambda v: struct.pack('>i', v)
            case IOMemento.IoUInt:
                return lambda v: struct.pack('>I', v)
            case IOMemento.IoLong:
                return lambda v: struct.pack('>q', v)
            case IOMemento.IoULong:
                return lambda v: struct.pack('>Q', v)
            case IOMemento.IoFloat:
                return lambda v: struct.pack('>f', v)
            case IOMemento.IoDouble:
                return lambda v: struct.pack('>d', v)
            case IOMemento.IoLocalDate:
                from datetime import date
                return lambda v: struct.pack('>q', v.toordinal() - date(1970, 1, 1).toordinal() if isinstance(v, date) else int(v))
            case IOMemento.IoInstant:
                return lambda v: struct.pack('>qi', int(v.timestamp()), int((v.timestamp() % 1) * 1_000_000_000))
            case IOMemento.IoString:
                return lambda v: v.encode('utf-8') + b'\x00'
            case IOMemento.IoCharSeries:
                return lambda v: v.encode('utf-8')
            case IOMemento.IoByteArray:
                return lambda v: v if isinstance(v, bytes) else bytes(v)
            case IOMemento.IoNothing:
                return lambda v: b''
            case _:
                raise NotImplementedError(f"Encoder not implemented for {self}")

    def create_decoder(self, size: int) -> Callable[[bytes], Any]:
        """Create decoder for this type with given field size."""
        match self:
            case IOMemento.IoBoolean:
                return lambda b: b[0] != 0
            case IOMemento.IoByte:
                return lambda b: struct.unpack('>b', b)[0]
            case IOMemento.IoUByte:
                return lambda b: struct.unpack('>B', b)[0]
            case IOMemento.IoShort:
                return lambda b: struct.unpack('>h', b)[0]
            case IOMemento.IoUShort:
                return lambda b: struct.unpack('>H', b)[0]
            case IOMemento.IoInt:
                return lambda b: struct.unpack('>i', b)[0]
            case IOMemento.IoUInt:
                return lambda b: struct.unpack('>I', b)[0]
            case IOMemento.IoLong:
                return lambda b: struct.unpack('>q', b)[0]
            case IOMemento.IoULong:
                return lambda b: struct.unpack('>Q', b)[0]
            case IOMemento.IoFloat:
                return lambda b: struct.unpack('>f', b)[0]
            case IOMemento.IoDouble:
                return lambda b: struct.unpack('>d', b)[0]
            case IOMemento.IoLocalDate:
                from datetime import date, timedelta
                epoch = date(1970, 1, 1)
                return lambda b: epoch + timedelta(days=struct.unpack('>q', b)[0])
            case IOMemento.IoInstant:
                import datetime
                return lambda b: datetime.datetime.fromtimestamp(
                    struct.unpack('>q', b[:8])[0] + struct.unpack('>i', b[8:12])[0] / 1_000_000_000,
                    tz=datetime.timezone.utc
                )
            case IOMemento.IoString:
                return lambda b: b.rstrip(b'\x00').decode('utf-8')
            case IOMemento.IoCharSeries:
                return lambda b: b.decode('utf-8')
            case IOMemento.IoByteArray:
                return lambda b: b
            case IOMemento.IoNothing:
                return lambda b: None
            case _:
                raise NotImplementedError(f"Decoder not implemented for {self}")


# Convenience singletons matching Kotlin
IoBoolean = IOMemento.IoBoolean
IoByte = IOMemento.IoByte
IoUByte = IOMemento.IoUByte
IoShort = IOMemento.IoShort
IoUShort = IOMemento.IoUShort
IoInt = IOMemento.IoInt
IoUInt = IOMemento.IoUInt
IoLong = IOMemento.IoLong
IoULong = IOMemento.IoULong
IoFloat = IOMemento.IoFloat
IoDouble = IOMemento.IoDouble
IoLocalDate = IOMemento.IoLocalDate
IoInstant = IOMemento.IoInstant
IoString = IOMemento.IoString
IoCharSeries = IOMemento.IoCharSeries
IoByteArray = IOMemento.IoByteArray
IoNothing = IOMemento.IoNothing
IoArray = IOMemento.IoArray
IoObject = IOMemento.IoObject


def iomemento_from_name(name: str) -> IOMemento:
    """Parse IOMemento from type name string (e.g., 'IoInt', 'IoString')."""
    try:
        return IOMemento[name]
    except KeyError:
        # Try with Io prefix
        if not name.startswith('Io'):
            name = 'Io' + name
        return IOMemento[name]