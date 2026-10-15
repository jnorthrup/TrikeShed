"""Hermes' sentinel content codec, independent of session storage."""

from __future__ import annotations

import json
import logging
from typing import Any

logger = logging.getLogger("hermes_state")
_CONTENT_JSON_PREFIX = "\x00json:"


def _json_or(raw: Any, fallback: Any, warning: str) -> Any:
    """``json.loads(raw)``; on failure log *warning* and return *fallback*."""
    try:
        return json.loads(raw)
    except (json.JSONDecodeError, TypeError):
        logger.warning(warning)
        return fallback


def _decode_content(content: Any) -> Any:
    """Reverse the stored content encoding; return scalars unchanged and malformed markers raw."""
    if isinstance(content, str) and content.startswith(_CONTENT_JSON_PREFIX):
        return _json_or(content[len(_CONTENT_JSON_PREFIX):], content,
            "Failed to decode JSON-encoded message content; returning raw string")
    return content
