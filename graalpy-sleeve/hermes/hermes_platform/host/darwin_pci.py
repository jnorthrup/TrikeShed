"""macOS PCI display identities measured by the host outside the guest bounds."""

from __future__ import annotations

import json


def display_vendor_ids() -> list[str] | None:
    """Hex PCI vendor ids, or ``None`` when the host has no IOKit measurement."""
    measured = json.loads(host.call("host_facts")).get("display_vendor_ids")
    return None if measured is None else list(measured)
