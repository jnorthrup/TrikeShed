"""GraalPy `contextvars` twin — restores the `name` attribute CPython guarantees.

GraalPy 25.3's `_contextvars.ContextVar` is an immutable, unsubclassable builtin with no
`name` attribute and a repr that does not carry the name either, so `var.name` — which
CPython documents as a read-only attribute — raises AttributeError. Hermes reads it
(`gateway/session_context.py` builds its channel map from `{var.name: var}`), which takes
`hermes_cli.oneshot` and thirteen tools down with it.

The twin keeps a real `_contextvars.ContextVar` inside each wrapper, so `copy_context()`
and `Context.run()` still carry values across the same underlying storage; only the Python
surface is re-created.
"""
import _contextvars as _c

Context = _c.Context
Token = _c.Token
copy_context = _c.copy_context

__all__ = ["Context", "ContextVar", "Token", "copy_context"]

_MISSING = object()


class ContextVar:
    """`_contextvars.ContextVar` plus the documented read-only `name`."""

    __slots__ = ("_var", "_name")

    def __init__(self, name, *, default=_MISSING):
        if not isinstance(name, str):
            raise TypeError("context variable name must be a str")
        self._name = name
        self._var = _c.ContextVar(name) if default is _MISSING else _c.ContextVar(name, default=default)

    @property
    def name(self):
        return self._name

    def get(self, *default):
        return self._var.get(*default)

    def set(self, value):
        return self._var.set(value)

    def reset(self, token):
        return self._var.reset(token)

    def __class_getitem__(cls, item):
        return cls

    def __hash__(self):
        return hash(self._var)

    def __eq__(self, other):
        return self is other or (isinstance(other, ContextVar) and self._var is other._var)

    def __repr__(self):
        return "<ContextVar name=%r at 0x%x>" % (self._name, id(self))
