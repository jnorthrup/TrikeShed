"""One summary of this machine and its OS account, for the desktop first-run onboarding.

Hardware and native account facts come from the host's explicit measurements, never from
environment variables such as HOME or LANG, a subprocess or the network. Unmeasured native
account fields remain unavailable. They describe the machine that runs
the Hermes backend; when the desktop app drives a remote backend, that is not the user's laptop.
"""

from __future__ import annotations

import functools
import platform
import re

from hermes_platform.host import facts as host
from hermes_platform.host import products, runtime

# Generic account names that are not a person's name.
_NON_NAMES = frozenset({
    "admin", "administrator", "default", "guest", "me", "owner", "root", "test", "user",
})
_HANDLE_CHARS = re.compile(r"[\d_@/\\]")


# --- account facts -----------------------------------------------------------------


def _posix_account() -> tuple[str, str]:
    """Return (login, full name) only when the host measured its user record."""
    return tuple(host._host_facts().get("account", ("", "")))


def _windows_account() -> tuple[str, str]:
    """Return (login, display name) only when the host measured its user record."""
    return tuple(host._host_facts().get("account", ("", "")))


def _account() -> tuple[str, str]:
    try:
        return _windows_account() if host.os_family() == "win32" else _posix_account()
    except (AttributeError, ImportError, KeyError, OSError):
        return "", ""


def full_name_from(login: str, full: str) -> str | None:
    """A real full name only; a login handle is never offered as the user's name."""
    name = " ".join(full.split())
    if not (2 <= len(name) <= 40) or name.lower() in _NON_NAMES or name.lower() == login.lower():
        return None
    # Digits or underscores ("p14", "CD_01.05") or an all-lowercase cased name mark a handle.
    if _HANDLE_CHARS.search(name) or name == name.lower() != name.upper():
        return None
    return name


def _darwin_locale() -> str:
    """First preferred UI language, or empty when the host has not measured it."""
    return host._host_facts().get("locale", "")


def _windows_locale() -> str:
    return host._host_facts().get("locale", "")


def _linux_locale() -> str:
    """System locale from its config file; the shell's LANG is deliberately not read."""
    for path in ("/etc/locale.conf", "/etc/default/locale"):
        try:
            with open(path, encoding="utf-8-sig") as handle:
                for line in handle:
                    key, _, value = line.strip().partition("=")
                    if key == "LANG" and value:
                        return value.strip("\"'").split(".", 1)[0].replace("_", "-")
        except OSError:
            continue
    return ""


def _locale() -> str:
    try:
        if host.os_family() == "darwin":
            return _darwin_locale()
        if host.os_family() == "win32":
            return _windows_locale()
        return _linux_locale()
    except (AttributeError, OSError, TypeError, ValueError):
        return ""


# --- the summary ----------------------------------------------------------------------


def _machine_kind(os_family: str, nvidia: str | None) -> str:
    if nvidia in ("rtx_spark", "dgx_spark"):
        return "Spark"
    return {"darwin": "Mac", "win32": "PC"}.get(os_family, "computer")


def _os_release(os_family: str) -> str:
    # On a Mac platform.release() is the Darwin kernel version (25.x), not the macOS version (26.x).
    return (platform.mac_ver()[0] if os_family == "darwin" else "") or platform.release()


@functools.cache
def summary() -> dict:
    """This machine and account as measured. ``machine`` leaves out a key that was not measured;
    ``locale`` and ``full_name`` are None when the OS has none to give."""
    os_family, arch, gpu, cpu = host.os_family(), host.native_arch(), host.gpu_class(), host.cpu_model()
    ram = host.ram_total_bytes()
    nvidia = products.nvidia_platform()
    machine = {
        "os_family": os_family,
        "os_release": _os_release(os_family),
        "native_arch": arch,
        "cpu_model": cpu,
        "ram_gb": round(ram / 2**30) if ram else None,
        "gpu_class": None if gpu == "unknown" else gpu,
        "vendor": host.cpu_vendor(),
        "wsl": runtime.is_wsl(),
        "container": runtime.is_container(),
    }
    return {
        "machine": {key: value for key, value in machine.items() if value not in (None, "")},
        "machine_kind": _machine_kind(os_family, nvidia),
        "has_nvidia_gpu": gpu == "nvidia",
        "nvidia_platform": nvidia,
        "locale": _locale() or None,
        "full_name": full_name_from(*_account()),
    }
