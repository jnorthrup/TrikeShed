"""Terminal transport and receipts around the unmodified HermesCLI.run()."""

import collections
import codecs
import gc
import importlib.util
import json
import os
from pathlib import Path
import select
import subprocess
import sys
import threading
import time
import traceback

_cli = None
_chat = None
_thread = None
_feeder = None
_input_stop = threading.Event()
_closing = False
_pipe = None
_failure = None
_pending = collections.deque()
_condition = threading.Condition()
_receipt = threading.local()
_size = (32, 100)
_source = None


def verify_source(root, lock_path):
    root, lock_path = Path(root).resolve(), Path(lock_path).resolve()
    try:
        lock = json.loads(lock_path.read_text(encoding="utf-8"))
        repository, revision = lock["repository"], lock["revision"]
        if not isinstance(repository, str) or not repository:
            raise ValueError("repository must name the curated fork")
        if (not isinstance(revision, str) or len(revision) != 40 or
                any(character not in "0123456789abcdef" for character in revision)):
            raise ValueError("revision must be an exact 40-character Git commit")
    except (OSError, ValueError, TypeError, KeyError) as failure:
        raise RuntimeError(f"Hermes source lock unavailable or invalid at {lock_path}: {failure}") from failure
    expected = f"{repository}@{revision}"

    def git(*arguments):
        try:
            result = subprocess.run(["git", "--no-optional-locks", "-C", str(root), *arguments],
                                    stdin=subprocess.DEVNULL, capture_output=True, text=True,
                                    timeout=10, check=False)
        except (OSError, subprocess.TimeoutExpired) as failure:
            raise RuntimeError(f"Hermes Git verification failed at {root}; expected {expected}: {failure}") from failure
        if result.returncode:
            raise RuntimeError(f"Hermes Git verification failed at {root}; expected {expected}: "
                               f"{result.stderr.strip() or result.stdout.strip()}")
        return result.stdout.strip()

    actual_root = Path(git("rev-parse", "--show-toplevel")).resolve()
    if actual_root != root:
        raise RuntimeError(f"Hermes source root is {root}, Git root is {actual_root}; expected {expected}")
    actual_repository = git("remote", "get-url", "origin")
    if actual_repository != repository:
        raise RuntimeError(f"Hermes source repository is {actual_repository}; expected {repository}")
    actual_revision = git("rev-parse", "HEAD")
    if actual_revision != revision:
        raise RuntimeError(f"Hermes source revision is {actual_revision}; expected {revision} ({repository})")
    dirty = git("status", "--porcelain", "--untracked-files=no")
    if dirty:
        raise RuntimeError(f"Hermes tracked source differs from {expected}:\n{dirty}")
    return {"repository": repository, "revision": revision}


def _feed(pipe):
    global _failure
    try:
        decoder = codecs.getincrementaldecoder(sys.stdin.encoding or "utf-8")(errors="replace")
        descriptor = sys.stdin.fileno()
        while not _input_stop.is_set():
            if not select.select([descriptor], [], [], 0.1)[0]:
                continue
            data = os.read(descriptor, 4096)
            if not data:
                raise EOFError("terminal input stream closed")
            text = decoder.decode(data)
            pipe.send_text(text)
    except Exception as failure:
        retiring = _closing or _input_stop.is_set() or getattr(_cli, "_should_exit", False)
        if retiring and isinstance(failure, (EOFError, OSError, ValueError)):
            if not _input_stop.is_set():
                pipe.close()
            return
        _failure = f"Hermes terminal input failed: {failure}"
        # EOF wakes the real application's reader, so it owns its normal shutdown.
        pipe.close()
        print(_failure, file=sys.stderr, flush=True)
        try:
            _host_call("sleeve_cli_closed", getattr(_cli, "session_id", None), _failure)
        except Exception as reporting:
            print(f"{_failure}; host unavailable: {reporting}", file=sys.stderr, flush=True)


def _stop_input(pipe):
    _input_stop.set()
    while _feeder.is_alive():
        # The application has returned; drain its pipe to release a pending write.
        pipe.read_keys()
        _feeder.join(0.1)


def _join_workers():
    """Join this adapter's input and the actual CLI's bound-method workers."""
    _input_stop.set()
    deadline = time.monotonic() + 20
    current = threading.current_thread()
    workers = [thread for thread in threading.enumerate()
               if thread is not current and
               (thread is _feeder or getattr(getattr(thread, "_target", None), "__self__", None) is _cli)]
    for thread in workers:
        thread.join(max(0, deadline - time.monotonic()))
    remaining = [thread.name for thread in workers if thread.is_alive()]
    if remaining:
        raise RuntimeError("HermesCLI workers did not finish shutdown: " + ", ".join(remaining))


def _threads():
    result = []
    for thread in threading.enumerate():
        target = getattr(thread, "_target", None)
        result.append({"name": thread.name, "daemon": thread.daemon,
                       "target": getattr(target, "__qualname__", None)})
    return result


def _observe_chat(*args, **kwargs):
    receipt = getattr(_receipt, "current", None)
    if receipt is not None:
        receipt["turn_attempted"] = True
    return _chat(*args, **kwargs)


def _turn_result(previous, result):
    if result is previous or not isinstance(result, dict):
        return None
    return {key: result[key] for key in (
        "completed", "failed", "partial", "interrupted", "error", "failure_reason", "api_calls",
    ) if key in result}


def _observe(line):
    with _condition:
        receipt = _pending.popleft() if _pending and _pending[0]["line"] == line else None
    terminal_input = receipt is None
    if terminal_input:
        receipt = {"line": line, "done": threading.Event(), "error": None,
                   "turn_attempted": False, "turn": None}
    cause = None
    admitted = False
    previous = getattr(_cli, "_last_turn_result", None)
    _receipt.current = receipt
    try:
        if terminal_input:
            cause = _host_call("sleeve_cli_input", line)
            admitted = True
        # GraalPy finalizes temporary native cursors at collection rather than at
        # CPython's reference-count boundary. Retire them before admitting input.
        gc.collect()
        context = getattr(getattr(_cli, "_app", None), "context", None)
        return context.copy().run(_process, line) if context is not None else _process(line)
    except BaseException:
        if receipt is not None:
            receipt["error"] = traceback.format_exc()
        raise
    finally:
        try:
            result = getattr(_cli, "_last_turn_result", None)
            receipt["turn"] = _turn_result(previous, result)
            if admitted:
                outcome = status()
                outcome.update(input_processed=True, turn_attempted=receipt["turn_attempted"],
                               turn=receipt["turn"], input_error=receipt["error"])
                _host_call("sleeve_cli_result", cause, json.dumps(outcome))
        finally:
            receipt["done"].set()
            _receipt.current = None


def _serve():
    global _pipe, _failure, _feeder
    from prompt_toolkit.application import create_app_session
    from prompt_toolkit.data_structures import Size
    from prompt_toolkit.input import create_pipe_input
    from prompt_toolkit.output.vt100 import Vt100_Output
    try:
        with create_pipe_input() as pipe:
            _pipe = pipe
            _feeder = threading.Thread(target=_feed, args=(pipe,), name="hermes-terminal-input", daemon=True)
            _feeder.start()
            try:
                output = Vt100_Output(sys.stdout, lambda: Size(rows=_size[0], columns=_size[1]),
                                      term="xterm-256color", enable_cpr=False)
                with create_app_session(input=pipe, output=output):
                    _cli.run()
            finally:
                _stop_input(pipe)
    except BaseException:
        _failure = traceback.format_exc()
        print(_failure, file=sys.stderr, flush=True)
    finally:
        _pipe = None
        _cli._should_exit = True
        try:
            _join_workers()
            from hermes_state_registry import close_all
            close_all()
        except BaseException:
            _failure = (_failure + "\n" if _failure else "") + traceback.format_exc()
            print(_failure, file=sys.stderr, flush=True)
        with _condition:
            for receipt in _pending:
                receipt["error"] = _failure or "HermesCLI exited before processing input"
                receipt["done"].set()
            _pending.clear()
            _condition.notify_all()
        _host_call("sleeve_cli_closed", _cli.session_id, _failure)


def start(call, model=None, resume=None, rows=32, columns=100):
    global _cli, _thread, _process, _chat, _size, _host_call, _source
    if _cli is not None:
        raise RuntimeError("HermesCLI worker already started")
    _host_call = call
    call("sleeve_progress", "verifying curated Hermes source pin")
    spec = importlib.util.find_spec("cli")
    if spec is None or not spec.origin:
        raise RuntimeError("Pinned Hermes CLI source could not be found")
    _source = verify_source(Path(spec.origin).resolve().parent,
                            Path(__file__).resolve().parents[1] / "hermes.lock.json")
    if not sys.argv:
        sys.argv.append("hermes")
    import trikeshed_contextvars
    trikeshed_contextvars.install()
    import trikeshed_modelmux as mux
    from hermes_cli import runtime_provider
    from agent import auxiliary_client
    mux.install(call)
    mux.register()
    mux.bind_runtime(runtime_provider, auxiliary_client)
    call("sleeve_progress", "importing stock cli.HermesCLI")
    from cli import HermesCLI
    import trikeshed_posix
    trikeshed_posix.install()
    call("sleeve_progress", "opening HermesCLI session store")
    resize(rows, columns)
    from hermes_state_registry import acquire, release
    from hermes_cli.config import load_config_readonly
    configured = load_config_readonly().get("model") or {}
    options = {"model": model} if model else {}
    if configured.get("provider") == mux.PROVIDER:
        options.update(model=mux.select_model(model), provider=mux.PROVIDER, base_url=mux.BASE_URL)
    store = acquire()
    try:
        gc.collect()
        _cli = HermesCLI(**options, **({"resume": resume} if resume else {}))
    finally:
        release(store)
    if _cli._session_db_unavailable or _cli._session_db is None:
        from hermes_state import get_last_init_error
        raise RuntimeError("HermesCLI session store unavailable: " + str(get_last_init_error()))
    gc.collect()
    _process = _cli._tui_process_one_input
    _chat = _cli.chat
    _cli.chat = _observe_chat
    _cli._tui_process_one_input = _observe
    _thread = threading.Thread(target=_serve, name="hermes-cli", daemon=False)
    call("sleeve_progress", "starting HermesCLI.run terminal input loop")
    _thread.start()
    deadline = time.monotonic() + 900
    while _thread.is_alive() and time.monotonic() < deadline:
        if _failure:
            break
        app = getattr(_cli, "_app", None)
        if app is not None and app.is_running and app.render_counter > 0 and _feeder and _feeder.is_alive():
            if _cli._active_session_lease is None:
                raise RuntimeError("HermesCLI has no active-session ownership")
            return status()
        time.sleep(0.02)
    failure = _failure or "HermesCLI did not reach its terminal input loop"
    call("sleeve_cli_closed", _cli.session_id, failure)
    raise RuntimeError(failure)


def status():
    import sqlite3
    cli = sys.modules.get("cli")
    app = getattr(_cli, "_app", None)
    input_open = bool(_feeder and _feeder.is_alive() and not _input_stop.is_set() and not _failure)
    return {
        "open": bool(_thread and _thread.is_alive() and app and app.is_running and input_open),
        "input_open": input_open,
        "session_id": _cli.session_id if _cli else None,
        "session_store": bool(_cli and _cli._session_db is not None
                              and _cli._session_db._conn is not None),
        "history_size": len(_cli.conversation_history) if _cli else 0,
        "model": _cli.model if _cli else None,
        "provider": _cli.provider if _cli else None,
        "source": getattr(cli, "__file__", None),
        "source_repository": _source["repository"] if _source else None,
        "source_revision": _source["revision"] if _source else None,
        "sqlite": sqlite3.__file__,
        "python": sys.version,
        "failure": _failure,
        "threads": _threads(),
    }


def submit(line, timeout=900):
    if not status()["open"] or _pipe is None:
        raise RuntimeError(_failure or "HermesCLI input loop is closed")
    if not isinstance(line, str) or "\n" in line or "\r" in line:
        raise ValueError("HermesCLI command requires one input line")
    receipt = {"line": line, "done": threading.Event(), "error": None,
               "turn_attempted": False, "turn": None}
    if line == "/new":
        _clear_input()
    if _cli._app.current_buffer.text:
        raise RuntimeError("HermesCLI has unsubmitted terminal input")
    with _condition:
        if _pending:
            raise RuntimeError("HermesCLI already has an admitted command")
        _pending.append(receipt)
    deadline = time.monotonic() + timeout
    _pipe.send_text(line)
    # Hermes distinguishes paste newlines from Enter within 50ms of text changes.
    # Wait for the real input buffer to settle before sending the Enter key.
    while (not _cli._app.current_buffer.text or
           time.monotonic() - getattr(_cli, "_tui_last_text_change", 0) < 0.075):
        if time.monotonic() >= deadline:
            raise TimeoutError("HermesCLI terminal input did not settle")
        time.sleep(0.01)
    _pipe.send_text("\r")
    if not receipt["done"].wait(max(0, deadline - time.monotonic())):
        raise TimeoutError("HermesCLI command did not finish within its host deadline")
    if receipt["error"]:
        raise RuntimeError(receipt["error"])
    if _cli._should_exit:
        _thread.join(30)
        if _thread.is_alive():
            raise RuntimeError("HermesCLI did not finish shutdown")
        if _failure:
            raise RuntimeError(_failure)
    result = status()
    result["input_processed"] = True
    result["turn_attempted"] = receipt["turn_attempted"]
    if receipt["turn"] is not None:
        result["turn"] = receipt["turn"]
    return result


def resize(rows, columns):
    global _size
    _size = (int(rows), int(columns))
    os.environ["LINES"] = str(_size[0])
    os.environ["COLUMNS"] = str(_size[1])
    if _cli and getattr(_cli, "_app", None):
        _cli._app.invalidate()


def _clear_input():
    app = _cli._app
    if app.current_buffer.text:
        cleared = threading.Event()
        def clear_input():
            app.current_buffer.reset()
            cleared.set()
        app.loop.call_soon_threadsafe(clear_input)
        if not cleared.wait(5):
            raise RuntimeError("HermesCLI did not clear its terminal input")


def close():
    global _closing
    _closing = True
    if _thread and _thread.is_alive():
        deadline = time.monotonic() + 900
        while _thread.is_alive() and not status()["open"] and not _cli._should_exit and not _failure:
            if time.monotonic() >= deadline:
                raise RuntimeError("HermesCLI startup did not settle before shutdown")
            time.sleep(0.05)
        if status()["open"]:
            _clear_input()
            submit("/quit", timeout=900)
        _thread.join(30)
        if _thread.is_alive():
            raise RuntimeError("HermesCLI did not finish shutdown")
    if _failure:
        raise RuntimeError(_failure)
    return status()
