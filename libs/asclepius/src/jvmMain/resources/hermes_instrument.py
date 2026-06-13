import sys

print("[HERMES INSTRUMENT] Module loaded", file=sys.stderr)
sys.stderr.flush()

# ──────────────────────────────────────────────────────────────────────
# IOMementoSlabOp mirror (from IOMementoSlabWire.kt)
# ──────────────────────────────────────────────────────────────────────

class IOMementoSlabOp:
    """Stable enum mirror for IOMemento slab operations.

    Ordinal and name must match JVM IOMementoSlabOp exactly.
    """
    IoBoolean = 0
    IoByte = 1
    IoUByte = 2
    IoShort = 3
    IoUShort = 4
    IoInt = 5
    IoUInt = 6
    IoLong = 7
    IoULong = 8
    IoFloat = 9
    IoDouble = 10
    IoLocalDate = 11
    IoInstant = 12
    IoString = 13
    IoByteArray = 14
    IoNothing = 15

    _NAMES = [
        "IoBoolean", "IoByte", "IoUByte", "IoShort", "IoUShort",
        "IoInt", "IoUInt", "IoLong", "IoULong", "IoFloat",
        "IoDouble", "IoLocalDate", "IoInstant", "IoString",
        "IoByteArray", "IoNothing",
    ]

    _FIXED_SIZES = {
        IoBoolean: 1, IoByte: 1, IoUByte: 1,
        IoShort: 2, IoUShort: 2,
        IoInt: 4, IoUInt: 4,
        IoLong: 8, IoULong: 8,
        IoFloat: 4, IoDouble: 8,
        IoLocalDate: 8, IoInstant: 12,
        IoString: None, IoByteArray: None,
        IoNothing: 0,
    }

    @classmethod
    def name(cls, ordinal: int) -> str:
        return cls._NAMES[ordinal] if 0 <= ordinal < len(cls._NAMES) else "UNKNOWN"

    @classmethod
    def fixed_size(cls, ordinal: int):
        return cls._FIXED_SIZES.get(ordinal)

    @classmethod
    def is_variable(cls, ordinal: int) -> bool:
        return cls._FIXED_SIZES.get(ordinal) is None

    @classmethod
    def frame_size(cls, ordinal: int, value) -> int:
        fs = cls.fixed_size(ordinal)
        if fs is not None:
            return fs
        if ordinal == cls.IoString:
            return 4 + len(value.encode('utf-8'))
        if ordinal == cls.IoByteArray:
            return 4 + len(value)
        return 4