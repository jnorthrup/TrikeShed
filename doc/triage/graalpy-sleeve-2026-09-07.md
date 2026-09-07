# GraalPy sleeve triage — 2026-09-07

Run the Hermes Python corpus inside the no-native GraalPy guest, catalogue what breaks, and grow the
polyglot twin until the breaks are either ported or withheld in-language.

Reproduce:

```
java -cp <jvm classpath> borg.trikeshed.hermes.HermesPythonPortCli \
  --entry hermes_cli.main --sleeve graalpy-sleeve/hermes \
  --report build/reports/hermes-python-port.json
```

The console form is `--command ":status"` (or `--console` for a live VT220 session), and the daemon
boots the same waist behind `--hermes-console`.

## Where it started

`boot GraalPy sleeve` in the VT220 panel:

```
inventory 1597 modules · 1438 ready · 102 native-blocked · 57 transitive
Failed to load bundled provider plugin anthropic: module 'socket' has no attribute '_GLOBAL_DEFAULT_TIMEOUT'
Failed to load bundled provider plugin commandcode: module 'socket' has no attribute '_GLOBAL_DEFAULT_TIMEOUT'
boot failed: DEAD: java.lang.IllegalStateException: Creating threads is not allowed.
```

Two failure *kinds* hide behind those three lines, and only one of them looks like a failure.

**Withheld capability, uncatchable.** `hermes_logging.setup_logging()` starts a
`logging.handlers.QueueListener` worker thread at `hermes_cli/main.py:627`. Under
`allowCreateThread(false)` GraalPy raises a host `IllegalStateException` from
`ThreadModuleBuiltins.startThread`. Python cannot catch a host exception — a guest
`except BaseException` around the call is stepped straight over — and `InProcessIsolate.classify`
maps it to `GuestFailure.DEAD`, which fails closed and *closes the context*. One library's background
worker downs the VM, and nothing downstream of it is ever observed.

**Absent attribute, catchable.** `socket._GLOBAL_DEFAULT_TIMEOUT` is a CPython sentinel bound in
class bodies (`http.client.HTTPConnection.__init__`), so `import urllib.request` raised at import and
Hermes' own plugin loader swallowed it per-plugin. Every network provider was quietly gone.

## What was built

`graalpy-sleeve/hermes/trikeshed_guest_prelude.py`, imported at the import waist by
`HermesPythonPort.importInVm` before the entry module — stdlib `threading` copies
`_thread.start_joinable_thread` into a module global at import (threading.py:35), so a twin installed
after that import is never seen.

| twin | shape | why |
| --- | --- | --- |
| `_thread.start_new_thread` / `start_new` / `start_joinable_thread` | withheld | raise `RuntimeError("can't start new thread")`, exactly what CPython raises when the OS refuses. Callers degrade; the isolate lives. |
| `logging.handlers.QueueListener` + `QueueHandler.emit` | ported | withholding alone is not enough here: the listener's caller swallows the RuntimeError, boot "succeeds", and every record piles up in a `SimpleQueue` nobody drains (measured: depth grows, file handler never fires). The twin drains to the same handlers under the same `respect_handler_level` rule, on the only thread there is. |
| `socket` constants, `_GLOBAL_DEFAULT_TIMEOUT`, `htons`/`ntohs`/`htonl`/`ntohl` | ported | data and arithmetic carry no capability |
| `socket.socketpair`, `create_server`, `inet_*`, `getnameinfo` … | withheld | every verb that reaches the wire names the userspace.nio seam |
| `ssl` exception hierarchy, `TLSVersion`, `SSLContext` configuration surface | ported/withheld | `asyncio.sslproto` binds `SSLWantReadError` and friends at import; `http.client._create_https_context` reads `context.post_handshake_auth`. Configuration is real and inspectable; anything that would speak TLS fails closed. |
| `contextvars.ContextVar` | ported | GraalPy 25.3's `_contextvars.ContextVar` is immutable, unsubclassable, has no `name`, and its repr does not carry the name either. CPython documents `name` as read-only. `gateway/session_context.py:62` builds `{var.name: var}` — this alone took down `hermes_cli.oneshot` and thirteen tools. A wrapper holding a real ContextVar keeps `copy_context()` working. |

`HermesPythonPort` also gained PEP 420 namespace packages: a directory with no `__init__.py` never
enters the inventory, and the guest has no filesystem for `PathFinder` to walk, so the importer
synthesises the spec from the set of known module-name prefixes.

## Measured effect

Every ready module imported one at a time inside the guest, context restarted whenever one killed it:

| | before | after |
| --- | ---: | ---: |
| imports cleanly | 1075 | **1255** |
| fails | 361 | 182 |
| kills the isolate | 1 (`plugins.google_meet`) | **0** |

`hermes_cli.main` imports (`"imported": true`), the VT220 console reaches `state=ready`, and
`:hermes say hello in five words` runs a real turn that fails closed with
`hermes -z: agent failed: No module named 'requests'` — a Python error the operator can read, not a
dead isolate.

## What is left, ranked

1. **HTTP client (138 module misses; `httpx` 73, `requests` 32).** The next transitive break, and the
   one standing between the console and a working Hermes turn. Not staged into the guest VFS, and
   both reach banned `socket` regardless. Needs a host delegate — userspace.nio / ModelMux — behind a
   guest `requests`/`httpx` waist. **Wants a ruling: which host seam, and what carries credentials
   across it.**
2. **`sqlite3` (23 modules).** `hermes_cli.kanban*`, `cron.*`, `projects_cmd`, `tui_gateway.ws`. The
   banlist already names the replacement — "project persistence through the TrikeShed blackboard/CAS"
   — which means a pure-guest DB-API 2.0 waist over the blackboard. Largest single port remaining.
3. **Pure-Python packages absent from the guest (`rich` 13, `acp` 7, `prompt_toolkit` 3, `fire` 3,
   `snowballstemmer` 2, `wcwidth`, `mem0`, `discord`, `websockets`).** No polyglot obstacle at all —
   they are simply not staged, because the inventory walks the Hermes checkout and excludes `venv`.
   A provisioning decision, not a porting one.
4. **Package data is not staged.** `importInVm` writes only `.py` into the guest VFS, so
   `importlib.resources.files("hermes_cli.local_runtime")/"catalog.json"` raises
   `FileNotFoundError: Can't open orphan path`. One module today; it will recur.
5. **Hyphenated plugin directories (39 modules).** `plugins/model-providers/anthropic/__init__.py`
   yields the un-importable name `plugins.model-providers.anthropic`. Hermes loads these by path
   through its own plugin loader, so they were never modules — but the inventory counts them READY,
   which overstates the headline number. **Wants a ruling: drop them, or carry them under a
   path-loaded status of their own.**
6. **Correct fail-closed, no action.** `hermes_cli.pty_bridge` (GraalPy C API / JEP 454), `PIL`
   (3 modules), `aiohttp` (1).
7. **Not polyglot at all.** Eight `evals`/`scripts` modules read `sys.argv[1]` or a results directory
   at module scope; `plugins.platforms.google_chat` fails a Hermes `Platform` enum. These break the
   same way under CPython when imported rather than run.
