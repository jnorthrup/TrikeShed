"""Terminal transport and receipts around the unmodified HermesCLI.run()."""

import collections
import gc
import sys
import threading
import time
import traceback

_cli = None
_thread = None
_pipe = None
_failure = None
_pending = collections.deque()
_condition = threading.Condition()
_size = (32, 100)


def _feed(pipe):
    while True:
        text = sys.stdin.read(1)
        if not text:
            return
        try:
            pipe.send_text(text)
        except (OSError, ValueError):
            return


def _observe(line):
    with _condition:
        receipt = _pending.popleft() if _pending else None
    try:
        # GraalPy finalizes temporary native cursors at collection rather than at
        # CPython's reference-count boundary. Retire them before admitting input.
        gc.collect()
        return _process(line)
    except BaseException:
        if receipt is not None:
            receipt["error"] = traceback.format_exc()
        raise
    finally:
        if receipt is not None:
            receipt["done"].set()


def _serve():
    global _pipe, _failure
    from prompt_toolkit.application import create_app_session
    from prompt_toolkit.data_structures import Size
    from prompt_toolkit.input import create_pipe_input
    from prompt_toolkit.output.vt100 import Vt100_Output
    try:
        with create_pipe_input() as pipe:
            _pipe = pipe
            threading.Thread(target=_feed, args=(pipe,), name="hermes-terminal-input", daemon=True).start()
            output = Vt100_Output(sys.stdout, lambda: Size(rows=_size[0], columns=_size[1]),
                                  term="xterm-256color", enable_cpr=False)
            with create_app_session(input=pipe, output=output):
                _cli.run()
    except BaseException:
        _failure = traceback.format_exc()
        print(_failure, file=sys.stderr, flush=True)
    finally:
        _pipe = None
        with _condition:
            for receipt in _pending:
                receipt["error"] = _failure or "HermesCLI exited before processing input"
                receipt["done"].set()
            _pending.clear()
            _condition.notify_all()


def start(call, model=None, resume=None, rows=32, columns=100):
    global _cli, _thread, _process, _size
    if _cli is not None:
        raise RuntimeError("HermesCLI worker already started")
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
    _size = (rows, columns)
    from hermes_state_registry import acquire, release
    store = acquire()
    try:
        gc.collect()
        _cli = HermesCLI(model=mux.select_model(model), provider=mux.PROVIDER,
                         base_url=mux.BASE_URL, **({"resume": resume} if resume else {}))
    finally:
        release(store)
    if _cli._session_db_unavailable or _cli._session_db is None:
        from hermes_state import get_last_init_error
        raise RuntimeError("HermesCLI session store unavailable: " + str(get_last_init_error()))
    gc.collect()
    _process = _cli._tui_process_one_input
    _cli._tui_process_one_input = _observe
    _thread = threading.Thread(target=_serve, name="hermes-cli", daemon=False)
    call("sleeve_progress", "starting HermesCLI.run terminal input loop")
    _thread.start()
    deadline = time.monotonic() + 120
    while _thread.is_alive() and time.monotonic() < deadline:
        app = getattr(_cli, "_app", None)
        if app is not None and app.is_running and app.render_counter > 0:
            if _cli._active_session_lease is None:
                raise RuntimeError("HermesCLI has no active-session ownership")
            return status()
        time.sleep(0.02)
    raise RuntimeError(_failure or "HermesCLI did not reach its terminal input loop")


def status():
    import cli
    import sqlite3
    app = getattr(_cli, "_app", None)
    return {
        "open": bool(_thread and _thread.is_alive() and app and app.is_running),
        "session_id": _cli.session_id if _cli else None,
        "session_store": bool(_cli and _cli._session_db is not None),
        "history_size": len(_cli.conversation_history) if _cli else 0,
        "model": _cli.model if _cli else None,
        "provider": _cli.provider if _cli else None,
        "source": cli.__file__,
        "sqlite": sqlite3.__file__,
        "python": sys.version,
        "failure": _failure,
    }


def submit(line, timeout=300):
    if not status()["open"] or _pipe is None:
        raise RuntimeError(_failure or "HermesCLI input loop is closed")
    if not isinstance(line, str) or "\n" in line or "\r" in line:
        raise ValueError("HermesCLI command requires one input line")
    receipt = {"line": line, "done": threading.Event(), "error": None}
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
    result = status()
    result["input_processed"] = True
    return result


def resize(rows, columns):
    global _size
    _size = (int(rows), int(columns))
    if _cli and getattr(_cli, "_app", None):
        _cli._app.invalidate()


def close():
    if _thread and _thread.is_alive():
        if status()["open"]:
            submit("/quit", timeout=30)
        _thread.join(30)
        if _thread.is_alive():
            raise RuntimeError("HermesCLI did not finish shutdown")
    return status()
