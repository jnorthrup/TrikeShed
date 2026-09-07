"""Twins for host capabilities the polyglot bounds withhold, installed at the import waist.

Two shapes live here:

*withheld* — the capability cannot exist in a sandboxed single-threaded guest, so the twin
raises the same exception CPython raises when the OS refuses. Without it the guest does not
get an exception at all: `allowCreateThread(false)` surfaces as a host
`IllegalStateException` that Python's `except` cannot catch and that fails the isolate
closed (GuestFailure.DEAD), so one library's background worker kills the whole VM.

*ported* — the capability has a faithful single-threaded meaning, so the twin implements it.
Withholding alone is not enough for these: `logging.handlers.QueueListener` swallows the
RuntimeError, the boot "succeeds", and every log record then piles up in a queue nobody
drains. A twin that hands each record straight to the handlers preserves the observable
behaviour that the worker thread was there to provide.
"""
import sys

INSTALLED = []


def _withhold_threads():
    """`_thread`'s spawn verbs. GraalPy preloads `_thread` as a builtin, so the sleeve's
    meta-path finder never sees it and the twin is installed by assignment. `threading` is
    NOT preloaded, so its import-time rebinding (`_start_joinable_thread =
    _thread.start_joinable_thread`, threading.py:35) picks the twin up."""
    import _thread
    if "threading" in sys.modules:
        raise RuntimeError("threading bound before the prelude — the twin missed the import waist")

    def _no_thread(*args, **kwargs):
        raise RuntimeError("can't start new thread")

    verbs = [v for v in ("start_new_thread", "start_new", "start_joinable_thread") if hasattr(_thread, v)]
    for verb in verbs:
        setattr(_thread, verb, _no_thread)
    INSTALLED.append(("withheld", "_thread", tuple(verbs)))


def _port_queue_listener():
    """`logging.handlers.QueueListener` without its worker thread.

    The real listener parks a thread on `queue.get()`. With threads withheld its `start()`
    raises, callers that swallow that (hermes_logging._start_queue_listener_locked) keep a
    live QueueHandler on the root logger, and records accumulate in a SimpleQueue forever.
    The twin drains the queue on the emitting call instead: the same records reach the same
    handlers under the same `respect_handler_level` rule, on the only thread there is."""
    import logging.handlers as _h

    listeners = []

    class QueueListener(_h.QueueListener):
        def start(self):
            self._thread = None
            if self not in listeners:
                listeners.append(self)

        def stop(self):
            self._drain()
            if self in listeners:
                listeners.remove(self)

        def _drain(self):
            if getattr(self, "_draining", False):
                return
            self._draining = True
            try:
                while True:
                    try:
                        record = self.queue.get_nowait()
                    except Exception:
                        return
                    if record is self._sentinel:
                        continue
                    self.handle(record)
            finally:
                self._draining = False

    _real_emit = _h.QueueHandler.emit

    def emit(self, record):
        _real_emit(self, record)
        for listener in tuple(listeners):
            if listener.queue is self.queue:
                listener._drain()

    _h.QueueHandler.emit = emit
    _h.QueueListener = QueueListener
    INSTALLED.append(("ported", "logging.handlers", ("QueueListener", "QueueHandler.emit")))


def install():
    _withhold_threads()
    _port_queue_listener()
    return INSTALLED


install()
