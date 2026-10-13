package borg.trikeshed.hermes

/** Compatibility at the existing single-threaded GraalPy boundary, not a guest scheduler. */
object HermesGuestPrelude {
    fun source(logging: Boolean): String = threads + if (logging) "\n" + handlers else ""

    private val threads = """
        import sys, _thread

        def _no_thread(*args, **kwargs):
            raise RuntimeError("can't start new thread")

        for _verb in ('start_new_thread', 'start_new', 'start_joinable_thread'):
            if hasattr(_thread, _verb):
                setattr(_thread, _verb, _no_thread)
        # A standard-library import may already have captured the builtins. Do not leave
        # that reference able to turn a catchable refusal into a host-level VM failure.
        if 'threading' in sys.modules:
            _threading = sys.modules['threading']
            for _verb in ('_start_new_thread', '_start_joinable_thread'):
                if hasattr(_threading, _verb):
                    setattr(_threading, _verb, _no_thread)
    """.trimIndent()

    // Guest handlers write to the owned in-memory Btrfs VFS. Delivery is synchronous
    // and explicitly so: no worker, hidden task, or unacknowledged queue survives a turn.
    private val handlers = """
        import logging.handlers as _handlers
        import queue as _queue

        _listeners = []
        _emit = _handlers.QueueHandler.emit

        class QueueListener(_handlers.QueueListener):
            def start(self):
                if self in _listeners:
                    raise RuntimeError('listener already started')
                self._thread = None
                _listeners.append(self)
                self._drain()

            def stop(self):
                try:
                    self._drain()
                finally:
                    if self in _listeners:
                        _listeners.remove(self)

            def _drain(self):
                if getattr(self, '_draining', False):
                    return
                self._draining = True
                try:
                    while True:
                        try:
                            record = self.dequeue(False)
                        except _queue.Empty:
                            break
                        try:
                            if record is not self._sentinel:
                                self.handle(record)
                        finally:
                            if hasattr(self.queue, 'task_done'):
                                self.queue.task_done()
                finally:
                    self._draining = False

        def _deliver(self, record):
            _emit(self, record)
            for listener in tuple(_listeners):
                if listener.queue is self.queue:
                    listener._drain()

        _handlers.QueueHandler.emit = _deliver
        _handlers.QueueListener = QueueListener
    """.trimIndent()
}
