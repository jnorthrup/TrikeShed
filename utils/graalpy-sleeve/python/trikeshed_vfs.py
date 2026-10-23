"""Owned host leases and atomic publication for the durable guest filesystem.

Paths name the Graal VFS namespace. A lease is an actual userspace file lease,
held by the VM owner until release or VM close; it is not a guest descriptor.
"""

import json
import os
from trikeshed_host import call as _host_call

VFS = True
_owner_id = None


def owner():
    """Identity of the actual host lease owner, stable for this VM lifetime."""
    global _owner_id
    if _owner_id is None:
        value = _host_call("vfs_lease", "owner", "")
        if not isinstance(value, str) or not value:
            raise RuntimeError("VFS lease owner identity is unavailable")
        _owner_id = value
    return _owner_id


def owner_alive(owner_id):
    """Whether an actual VM lease owner in this host process is still open."""
    if not isinstance(owner_id, str) or not owner_id:
        raise ValueError("VFS lease owner identity is required")
    value = _host_call("vfs_lease", "owner_alive", owner_id)
    if not isinstance(value, bool):
        raise RuntimeError("invalid VFS lease owner liveness")
    return value


def _path(path):
    return os.path.abspath(os.fspath(path))


class ExclusiveLease:
    def __init__(self, path):
        self.path = _path(path)
        self._handle = None

    def acquire(self):
        if self._handle is not None:
            raise RuntimeError("VFS lease is already held")
        handle = _host_call("vfs_lease", "acquire", self.path)
        if handle is None:
            raise BlockingIOError("VFS lease is held: " + self.path)
        if not isinstance(handle, str) or not handle:
            raise RuntimeError("invalid VFS lease handle")
        self._handle = handle
        return self

    def release(self):
        handle = self._handle
        if handle is None:
            return
        if _host_call("vfs_lease", "release", handle) is not True:
            raise RuntimeError("VFS lease release was not acknowledged")
        self._handle = None


def publish_json(path, data, **kwargs):
    """Publish one real CAS file image; its owning Btrfs commit supplies durability."""
    kwargs.setdefault("ensure_ascii", False)
    text = json.dumps(data, **kwargs)
    try:
        text.encode("utf-8")
    except UnicodeEncodeError:
        kwargs["ensure_ascii"] = True
        text = json.dumps(data, **kwargs)
    if _host_call("vfs_lease", "publish", _path(path), text) is not True:
        raise OSError("VFS JSON publication was not acknowledged: " + _path(path))


def process_id():
    pid = json.loads(_host_call("host_facts")).get("process_id")
    if isinstance(pid, bool) or not isinstance(pid, int) or pid <= 0:
        raise RuntimeError("host process identity is unavailable")
    return pid


def process_start_time(pid):
    value = _host_call("process_start", int(pid))
    if value is not None and (isinstance(value, bool) or not isinstance(value, (float, int))):
        raise RuntimeError("invalid host process start time")
    return value


def process_alive(pid):
    value = _host_call("process_alive", int(pid))
    if not isinstance(value, bool):
        raise RuntimeError("invalid host process liveness")
    return value
