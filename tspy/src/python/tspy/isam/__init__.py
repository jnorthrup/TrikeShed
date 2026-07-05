"""
tspy.isam — Indexed Sequential Access Method (ISAM) for Python

Tuple-native ISAM implementation using TrikeShed cursor algebra.

    from tspy.isam import IsamDataFile, IsamMetaFileReader, RecordMeta, IOMemento

    # Write data
    meta = IsamMetaFileReader.write("data.meta", record_metas, varchars)
    IsamDataFile.write(cursor, "data.bin", varchars)

    # Read data
    with IsamDataFile("data.bin") as isam:
        for row in isam:
            print(row[0][0])  # First column value
"""

from .meta import (
    IOMemento,
    iomemento_from_name,
    IoBoolean, IoByte, IoUByte, IoShort, IoUShort,
    IoInt, IoUInt, IoLong, IoULong, IoFloat, IoDouble,
    IoLocalDate, IoInstant, IoString, IoCharSeries, IoByteArray,
    IoNothing, IoArray, IoObject,
)

from .record_meta import RecordMeta
from .meta_file import IsamMetaFileReader
from .data_file import IsamDataFile
from .wire_proto import write_to_buffer, read_from_buffer

__all__ = [
    # Meta
    'IOMemento', 'iomemento_from_name',
    'IoBoolean', 'IoByte', 'IoUByte', 'IoShort', 'IoUShort',
    'IoInt', 'IoUInt', 'IoLong', 'IoULong', 'IoFloat', 'IoDouble',
    'IoLocalDate', 'IoInstant', 'IoString', 'IoCharSeries', 'IoByteArray',
    'IoNothing', 'IoArray', 'IoObject',
    # Record
    'RecordMeta',
    # Meta file
    'IsamMetaFileReader',
    # Data file
    'IsamDataFile',
    # Wire proto
    'write_to_buffer', 'read_from_buffer',
]