"""DB-API adapter to the sleeve's real, image-backed host SQLite engine.

SQL, constraints, transactions and scalar functions execute in SQLite. Database
images are published through the VM's userspace storage lease; no guest path is
ever handed to SQLite's native filesystem.
"""
import base64
import json
import os
import threading
from urllib.parse import unquote, urlsplit, parse_qs

apilevel = "2.0"
threadsafety = 3
paramstyle = "qmark"
version = "2.6.0"
version_info = (2, 6, 0)
PARSE_DECLTYPES = 1
PARSE_COLNAMES = 2
LEGACY_TRANSACTION_CONTROL = -1


class Warning(Exception): pass
class Error(Exception): pass
class InterfaceError(Error): pass
class DatabaseError(Error): pass
class DataError(DatabaseError): pass
class OperationalError(DatabaseError): pass
class IntegrityError(DatabaseError): pass
class InternalError(DatabaseError): pass
class ProgrammingError(DatabaseError): pass
class NotSupportedError(DatabaseError): pass


def _call(op, **arguments):
    reply = json.loads(host.call("sqlite3", json.dumps({"op": op, **arguments})))
    if "error" in reply:
        detail = reply["error"]
        error = globals().get(detail.get("kind"), OperationalError)(detail.get("detail") or "SQLite operation failed")
        if detail.get("code") is not None:
            error.sqlite_errorcode = int(detail["code"])
            error.sqlite_errorname = detail.get("name")
        raise error
    return reply["value"]


def _encode(value):
    if value is None:
        return {"t": "null"}
    if isinstance(value, int):
        if not -(1 << 63) <= value < (1 << 63):
            raise OverflowError("Python int too large to convert to SQLite INTEGER")
        return {"t": "int", "v": str(int(value))}
    if isinstance(value, float):
        return {"t": "real", "v": repr(value)}
    if isinstance(value, str):
        return {"t": "text", "v": value}
    if isinstance(value, (bytes, bytearray, memoryview)):
        return {"t": "blob", "v": base64.b64encode(value).decode("ascii")}
    adapter = adapters.get((type(value), PrepareProtocol))
    if adapter is not None:
        return _encode(adapter(value))
    conform = getattr(value, "__conform__", None)
    if conform is not None:
        return _encode(conform(PrepareProtocol))
    raise ProgrammingError(f"type '{type(value).__name__}' is not supported")


def _decode(value):
    kind = value["t"]
    if kind == "null": return None
    if kind == "int": return int(value["v"])
    if kind == "real": return float(value["v"])
    if kind == "blob": return base64.b64decode(value["v"])
    if kind == "text": return value["v"]
    raise InterfaceError("invalid SQLite result type")


_callbacks = {}
_callback_sequence = 0


def __trikeshed_sqlite_callback(function_id, arguments_json):
    """Same-owner reentry target for the host's real SQLite scalar/progress callbacks."""
    arguments = [_decode(value) for value in json.loads(arguments_json)]
    return json.dumps(_encode(_callbacks[function_id](*arguments)))


def _register_callback(callback):
    global _callback_sequence
    _callback_sequence += 1
    key = str(_callback_sequence)
    _callbacks[key] = callback
    return key


class Row:
    def __init__(self, cursor, values):
        self._values = tuple(values)
        self._names = tuple(column[0] for column in cursor.description)

    def keys(self):
        return list(self._names)

    def __len__(self):
        return len(self._values)

    def __iter__(self):
        return iter(self._values)

    def __getitem__(self, key):
        if isinstance(key, str):
            # CPython's sqlite3.Row case folding is ASCII-only.
            def fold(text):
                return "".join(chr(ord(c) + 32) if "A" <= c <= "Z" else c for c in text)
            for at, name in enumerate(self._names):
                if name == key or (name.isascii() and key.isascii() and fold(name) == fold(key)):
                    return self._values[at]
            raise IndexError("No item with that key")
        if not isinstance(key, (int, slice)):
            raise IndexError("Index must be int or string")
        return self._values[key]

    def __eq__(self, other):
        if not isinstance(other, Row): return NotImplemented
        return self._names == other._names and self._values == other._values

    def __hash__(self):
        return hash(self._names) ^ hash(self._values)


class Cursor:
    def __init__(self, connection):
        self.connection = connection
        self.row_factory = connection.row_factory
        self.arraysize = 1
        self.description = None
        self.rowcount = -1
        self.lastrowid = None
        self._rows = []
        self._index = 0
        self._closed = False

    def _check(self):
        self.connection._check()
        if self._closed: raise ProgrammingError("Cannot operate on a closed cursor")

    def execute(self, sql, parameters=()):
        self._check()
        if not isinstance(sql, str): raise TypeError("execute() argument 1 must be str")
        if isinstance(parameters, dict):
            bindings = {str(key): _encode(value) for key, value in parameters.items()}
        else:
            try: bindings = [_encode(value) for value in parameters]
            except TypeError as error: raise ProgrammingError("parameters are of unsupported type") from error
        result = self.connection._request("execute", sql=sql, parameters=bindings,
                                          isolation=self.connection.isolation_level)
        columns = result["columns"]
        self.description = tuple((name, None, None, None, None, None, None) for name in columns) if columns is not None else None
        self.rowcount = int(result["rowcount"])
        self.lastrowid = int(result["lastrowid"])
        self._rows = result["rows"]
        self._index = 0
        return self

    def executemany(self, sql, seq_of_parameters):
        self._check()
        lastrowid = self.lastrowid
        count = 0
        try:
            for parameters in seq_of_parameters:
                self.execute(sql, parameters)
                if self.description is not None:
                    raise ProgrammingError("executemany() can only execute DML statements")
                count += max(0, self.rowcount)
        finally:
            self.lastrowid = lastrowid
            self.rowcount = count
        return self

    def executescript(self, sql_script):
        self._check()
        self.connection._request("script", sql=sql_script)
        self.description = None
        self.rowcount = -1
        self._rows = []
        self._index = 0
        return self

    def fetchone(self):
        self._check()
        if self._index >= len(self._rows): return None
        values = tuple(_decode(value) for value in self._rows[self._index])
        self._index += 1
        if self.connection.text_factory is not str:
            values = tuple(self.connection.text_factory(value.encode("utf-8")) if isinstance(value, str) else value for value in values)
        return values if self.row_factory is None else self.row_factory(self, values)

    def fetchmany(self, size=None):
        self._check()
        if size is None: size = self.arraysize
        rows = []
        while len(rows) < size or size < 0:
            row = self.fetchone()
            if row is None: break
            rows.append(row)
        return rows

    def fetchall(self):
        return self.fetchmany(-1)

    def __iter__(self):
        self._check()
        return self

    def __next__(self):
        value = self.fetchone()
        if value is None: raise StopIteration
        return value

    def close(self):
        self.connection._check()
        self._closed = True
        self._rows = []

    def setinputsizes(self, sizes): self._check()
    def setoutputsize(self, size, column=None): self._check()


class Connection:
    __trikeshed_sqlite__ = True

    def __init__(self, database, timeout=5.0, detect_types=0, isolation_level="", check_same_thread=True,
                 factory=None, cached_statements=128, uri=False, *, autocommit=LEGACY_TRANSACTION_CONTROL):
        if detect_types:
            raise NotSupportedError("declared-type SQLite conversion is not implemented by this adapter")
        if autocommit != LEGACY_TRANSACTION_CONTROL:
            raise NotSupportedError("explicit autocommit mode is not implemented; use isolation_level=None")
        self._id = None
        self._closed = False
        self._owner = threading.get_ident()
        self._same_thread = check_same_thread
        self._isolation = None
        self.isolation_level = isolation_level
        self.row_factory = None
        self.text_factory = str
        self._functions = {}
        self._progress = None
        database = os.fsdecode(os.fspath(database))
        readonly = False
        if uri and database.startswith("file:"):
            parsed = urlsplit(database)
            if parsed.netloc not in ("", "localhost"):
                raise OperationalError("invalid SQLite URI authority")
            options = parse_qs(parsed.query)
            mode = options.get("mode", ["rwc"])[0]
            if mode not in ("ro", "rw", "rwc", "memory"):
                raise OperationalError("invalid SQLite URI mode")
            if set(options) - {"mode", "cache"}:
                raise NotSupportedError("unsupported SQLite URI options")
            if mode == "memory" or parsed.path == ":memory:": database = ":memory:"
            else: database = unquote(parsed.path)
            readonly = mode == "ro"
        if not database: raise NotSupportedError("temporary SQLite files are unavailable; use :memory:")
        if database != ":memory:": database = os.path.abspath(database)
        self.__trikeshed_sqlite_path__ = database
        result = _call("connect", path=database, timeout=timeout, readonly=readonly)
        self._id = int(result["id"])

    def _check(self):
        if self._closed or self._id is None:
            raise ProgrammingError("Cannot operate on a closed database")
        if self._same_thread and threading.get_ident() != self._owner:
            raise ProgrammingError("SQLite objects created in a thread can only be used in that same thread")

    def _request(self, op, **arguments):
        self._check()
        return _call(op, id=self._id, **arguments)

    def __trikeshed_sqlite_lease__(self):
        return self._request("lease")

    @property
    def isolation_level(self): return self._isolation

    @isolation_level.setter
    def isolation_level(self, value):
        if value is not None:
            if not isinstance(value, str): raise TypeError("isolation_level must be str or None")
            value = value.upper()
            if value not in ("", "DEFERRED", "IMMEDIATE", "EXCLUSIVE"):
                raise ValueError("isolation_level string must be '', 'DEFERRED', 'IMMEDIATE', or 'EXCLUSIVE'")
        if value is None and self._id is not None: self.commit()
        self._isolation = value

    @property
    def in_transaction(self): return self._request("stats")["in_transaction"]

    @property
    def total_changes(self): return int(self._request("stats")["total_changes"])

    def cursor(self, factory=Cursor):
        self._check()
        return factory(self)

    def execute(self, sql, parameters=()): return self.cursor().execute(sql, parameters)
    def executemany(self, sql, seq_of_parameters): return self.cursor().executemany(sql, seq_of_parameters)
    def executescript(self, sql_script): return self.cursor().executescript(sql_script)
    def commit(self): self._request("commit")
    def rollback(self): self._request("rollback")

    def close(self):
        if self._closed: return
        self._request("close")
        self._closed = True
        for key in self._functions.values(): _callbacks.pop(key, None)
        if self._progress is not None: _callbacks.pop(self._progress, None)

    def create_function(self, name, narg, func, *, deterministic=False):
        self._check()
        if func is not None and not callable(func): raise TypeError("func must be callable or None")
        key = None if func is None else _register_callback(func)
        try:
            self._request("function", name=name, arity=narg, function=key, deterministic=deterministic)
        except Exception:
            if key is not None: _callbacks.pop(key, None)
            raise
        old = self._functions.pop((name, narg), None)
        if old is not None: _callbacks.pop(old, None)
        if key is not None: self._functions[(name, narg)] = key

    def set_progress_handler(self, progress_handler, n):
        if progress_handler is not None and not callable(progress_handler): raise TypeError("progress handler must be callable")
        key = None if progress_handler is None or n <= 0 else _register_callback(progress_handler)
        try: self._request("progress", function=key, steps=n)
        except Exception:
            if key is not None: _callbacks.pop(key, None)
            raise
        if self._progress is not None: _callbacks.pop(self._progress, None)
        self._progress = key

    def interrupt(self): self._request("interrupt")
    def enable_load_extension(self, enabled, /): raise NotSupportedError("native SQLite extensions are unavailable")
    def load_extension(self, path, /, *, entrypoint=None): raise NotSupportedError("native SQLite extensions are unavailable")
    def serialize(self, *, name="main"):
        if name != "main": raise NotSupportedError("only the main database image is exposed")
        return _decode(self._request("serialize"))

    def __enter__(self):
        self._check()
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        if exc_type is None: self.commit()
        else: self.rollback()
        return False


def connect(database, timeout=5.0, detect_types=0, isolation_level="", check_same_thread=True,
            factory=Connection, cached_statements=128, uri=False, *, autocommit=LEGACY_TRANSACTION_CONTROL):
    return factory(database, timeout=timeout, detect_types=detect_types, isolation_level=isolation_level,
                   check_same_thread=check_same_thread, cached_statements=cached_statements, uri=uri,
                   autocommit=autocommit)


class PrepareProtocol: pass
adapters = {}
converters = {}
def register_adapter(type, adapter): adapters[(type, PrepareProtocol)] = adapter
def register_converter(typename, converter): converters[typename.upper()] = converter
def Binary(value): return memoryview(value)

sqlite_version = _call("version")
sqlite_version_info = tuple(int(part) for part in sqlite_version.split("."))
globals().update(_call("constants"))
