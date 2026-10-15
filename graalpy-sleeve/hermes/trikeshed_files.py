"""Read-only file operations on the mounted Graal VFS.

The host imports this capability after mounting files and establishing the guest
working directory. Paths here name the guest namespace; this is not a live host
workspace or a local shell environment.
"""

import base64
import hashlib
import os
import stat

from tools.file_operations import ShellFileOperations, describe_binary_file
from tools.binary_extensions import has_binary_extension
from tools.file_operations_common import (
    PatchResult, ReadResult, SearchResult, WriteResult, _strip_bom,
)

VFS = True
_UNAVAILABLE = "Graal VFS file operations provide mounted-file reads; shell execution, writes, patches and search are unavailable"


class VfsFileOperations(ShellFileOperations):
    """Use the donor's native paginated reader against actual guest stat/open."""

    def __init__(self, *, cwd):
        super().__init__(None, cwd=cwd)

    def _native_read_enabled(self):
        return True

    def _expand_path(self, path):
        return os.path.expanduser(path)

    def _exec(self, command, cwd=None, timeout=None, stdin_data=None):
        raise NotImplementedError(_UNAVAILABLE)

    def _read_file_missing(self, path, offset, limit):
        return ReadResult(error=f"File not found: {path}", not_found=True)

    def _read_file_sequential(self, path, offset, limit):
        return ReadResult(error=f"Failed to read mounted file '{path}'; shell fallback is unavailable")

    def _read_binary_file(self, path, offset, limit, file_size, sample_bytes):
        return ReadResult(
            is_binary=True, file_size=file_size, error=describe_binary_file(sample_bytes, file_size),
        )

    def _read_bytes(self, path, max_bytes=None):
        path = self._expand_path(path)
        full = path if os.path.isabs(path) else os.path.join(self.cwd, path)
        try:
            metadata = os.stat(full)
            if not stat.S_ISREG(metadata.st_mode):
                return None, self._not_regular_error(path)
            if max_bytes is not None and metadata.st_size > max_bytes:
                return None, ReadResult(
                    file_size=metadata.st_size,
                    error=f"File is too large ({metadata.st_size:,} bytes, limit is {max_bytes:,})",
                )
            with open(full, "rb") as stream:
                data = stream.read() if max_bytes is None else stream.read(max_bytes + 1)
            if max_bytes is not None and len(data) > max_bytes:
                return None, ReadResult(
                    file_size=len(data),
                    error=f"File is too large (more than {max_bytes:,} bytes, limit is {max_bytes:,})",
                )
            return data, None
        except (FileNotFoundError, NotADirectoryError):
            if os.path.islink(full):
                return None, self._not_regular_error(path)
            return None, ReadResult(error=f"File not found: {path}", not_found=True)
        except OSError as error:
            return None, ReadResult(error=f"Failed to read mounted file '{path}': {error}")

    def read_file_raw(self, path):
        data, error = self._read_bytes(path)
        if error is not None:
            return error
        size = len(data)
        if self._is_image(path):
            return ReadResult(is_image=True, is_binary=True, file_size=size)
        if has_binary_extension(path) or self._is_likely_binary_bytes(data[:1000]):
            return ReadResult(is_binary=True, file_size=size, error=describe_binary_file(data[:1000], size))
        content, _ = _strip_bom(data.decode("utf-8", "surrogateescape"))
        return ReadResult(content=content, file_size=size, _content_sha256=hashlib.sha256(data).hexdigest())

    def read_file_bytes(self, path, max_bytes=None):
        data, error = self._read_bytes(path, max_bytes)
        if error is not None:
            return error
        return ReadResult(
            base64_content=base64.b64encode(data).decode("ascii"), file_size=len(data), is_binary=True,
        )

    def write_file(self, path, content, pre_content=None):
        return WriteResult(error=_UNAVAILABLE)

    def patch_replace(self, path, old_string, new_string, replace_all=False):
        return PatchResult(error=_UNAVAILABLE)

    def patch_v4a(self, patch_content):
        return PatchResult(error=_UNAVAILABLE)

    def delete_file(self, path):
        return WriteResult(error=_UNAVAILABLE)

    def move_file(self, src, dst):
        return WriteResult(error=_UNAVAILABLE)

    def search(self, pattern, path=".", target="content", file_glob=None, limit=50,
               offset=0, output_mode="content", context=0, order="discovery"):
        return SearchResult(error=_UNAVAILABLE)
