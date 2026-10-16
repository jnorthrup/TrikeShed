package borg.trikeshed.graal.subvm

/** The Python context API over GraalPy's real context storage, including its missing variable names. */
object PythonContextVars {
    const val MODULE = "contextvars"
    val source = """
        import _contextvars as _native
        from collections.abc import Mapping
        from types import GenericAlias

        __all__ = ['Context', 'ContextVar', 'Token', 'copy_context']
        _MISSING = object()
        # GraalPy does not reject a token reset in a different native Context.
        # Carry identity in that same storage; copies receive a distinct owner.
        _identity = _native.ContextVar('trikeshed.context.identity', default=_MISSING)

        def _owner():
            owner = _identity.get()
            if owner is _MISSING:
                owner = object()
                _identity.set(owner)
            return owner

        class _Value:
            __slots__ = ('variable', 'value')
            def __init__(self, variable, value):
                self.variable, self.value = variable, value

        def _value(value):
            return value.value if isinstance(value, _Value) else value

        def _key(variable):
            return variable._variable if isinstance(variable, ContextVar) else variable

        class ContextVar:
            __slots__ = ('_variable', '_name')
            def __init__(self, name, *, default=_MISSING):
                if not isinstance(name, str):
                    raise TypeError('context variable name must be a str')
                self._name = name
                self._variable = (_native.ContextVar(name) if default is _MISSING else
                                  _native.ContextVar(name, default=_Value(self, default)))
            @property
            def name(self): return self._name
            def get(self, *default):
                if len(default) > 1: raise TypeError('get accepts at most one default')
                if default:
                    return _value(self._variable.get(_Value(self, default[0])))
                return _value(self._variable.get())
            def set(self, value):
                return Token._from(self, self._variable.set(_Value(self, value)))
            def reset(self, token):
                if not isinstance(token, Token): raise TypeError('a Token was expected')
                if token._context is not _owner():
                    raise ValueError('Token was created in a different Context')
                self._variable.reset(token._token)
            def __class_getitem__(cls, item): return GenericAlias(cls, item)
            def __repr__(self): return '<ContextVar name=%r at 0x%x>' % (self._name, id(self))

        class Token:
            __slots__ = ('_variable', '_token', '_context')
            # GraalPy's native marker is a raw host Object, not an interop value.
            # Keep that implementation sentinel from escaping through root pointcuts.
            MISSING = object()
            def __new__(cls): raise RuntimeError('Tokens can only be created by ContextVar.set')
            @classmethod
            def _from(cls, variable, token):
                result = object.__new__(cls)
                result._variable, result._token = variable, token
                result._context = _owner()
                return result
            @property
            def var(self): return self._variable
            @property
            def old_value(self):
                value = self._token.old_value
                return self.MISSING if value is _native.Token.MISSING else _value(value)
            def __enter__(self): return self
            def __exit__(self, *args): self.var.reset(self)
            def __class_getitem__(cls, item): return GenericAlias(cls, item)

        class Context(Mapping):
            __slots__ = ('_context',)
            def __init__(self):
                self._context = _native.Context()
                self._context.run(_identity.set, object())
            @classmethod
            def _from(cls, context):
                result = object.__new__(cls)
                result._context = context
                context.run(_identity.set, object())
                return result
            def __len__(self): return len(self._context) - int(_identity in self._context)
            def __iter__(self):
                for variable, value in self._context.items():
                    if variable is not _identity:
                        yield value.variable if isinstance(value, _Value) else variable
            def __getitem__(self, variable): return _value(self._context[_key(variable)])
            def __contains__(self, variable): return _key(variable) in self._context
            def get(self, variable, default=None): return _value(self._context.get(_key(variable), default))
            def run(self, callable, *args, **kwargs): return self._context.run(callable, *args, **kwargs)
            def copy(self): return Context._from(self._context.copy())

        def copy_context(): return Context._from(_native.copy_context())
    """.trimIndent()
}
