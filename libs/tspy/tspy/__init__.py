"""
tspy — TrikeShed Python: native Python implementation of the PRELOAD.md kernel algebra.

Exports:
    Join, Twin, Series, Cursor, RowVec
    j, α (alpha), left_identity (↺)
    _l, _a, _s, s_ — composition literals
    series_of, series_range, series_repeat
    cursor_from_columns
"""

from .join import Join, Twin, twin
from .series import Series, series_of, series_range, series_repeat
from .cursor import RowVec, Cursor, cursor_from_columns
from .literals import _l, _a, _s, s_
from .operators import j, alpha, left_identity

# Unicode alias for left_identity (accessible via getattr/tspy.↺ but not as direct import)
import sys
this_module = sys.modules[__name__]
this_module.__dict__['↺'] = left_identity
this_module.__dict__['α'] = alpha

__all__ = [
    "Join",
    "Twin",
    "twin",
    "Series",
    "RowVec",
    "Cursor",
    "_l",
    "_a",
    "_s",
    "s_",
    "j",
    "alpha",
    "left_identity",
    "series_of",
    "series_range",
    "series_repeat",
    "cursor_from_columns",
]

__version__ = "0.1.0"