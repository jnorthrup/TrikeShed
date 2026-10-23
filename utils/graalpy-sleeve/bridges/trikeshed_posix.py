"""Native positional reads for Hermes' retained SQLite-header descriptors."""

import ctypes
import errno
import importlib
import operator
import os
import sys

_pread = None


def pread(fd, length, offset):
    """Call libc pread without seeking, reopening, or closing the caller's fd."""
    fd, length, offset = operator.index(fd), operator.index(length), operator.index(offset)
    if not -(1 << 31) <= fd < (1 << 31):
        raise OverflowError("file descriptor does not fit in C int")
    if length < 0:
        raise ValueError("negative read length")
    if length > sys.maxsize or not -(1 << 63) <= offset < (1 << 63):
        raise OverflowError("positional read argument exceeds native range")
    buffer = ctypes.create_string_buffer(length)
    while True:
        size = _pread(fd, buffer, length, offset)
        if size >= 0:
            return buffer.raw[:size]
        error = ctypes.get_errno()
        if error != errno.EINTR:
            raise OSError(error, os.strerror(error))


class _Posix:
    def __getattr__(self, name):
        return pread if name == "pread" else getattr(os, name)


def install():
    """Adapt only the donor header-probe module when stock os lacks pread."""
    global _pread
    if hasattr(os, "pread"):
        return False
    import __graalpython__
    if __graalpython__.posix_module_backend() != "native":
        raise RuntimeError("native pread requires GraalPy's native POSIX backend")
    if sys.platform not in ("darwin", "linux") or ctypes.sizeof(ctypes.c_void_p) != 8:
        raise NotImplementedError("native pread requires a supported 64-bit POSIX ABI")
    if _pread is None:
        library = ctypes.CDLL(None, use_errno=True)
        _pread = library.pread
        _pread.argtypes = [ctypes.c_int, ctypes.c_void_p, ctypes.c_size_t, ctypes.c_longlong]
        _pread.restype = ctypes.c_ssize_t
    module = importlib.import_module("hermes_state_dbfile")
    if isinstance(module.os, _Posix):
        return True
    if module.os is not os:
        raise RuntimeError("SQLite header probe already has a different OS adapter")
    module.os = _Posix()
    return True
