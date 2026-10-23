"""Host line transport for the donor HermesCLI, with one crossing per input.

The host imports this capability before ``cli`` and owns its bounded input lane.
HermesCLI retains command dispatch, conversation state, turn phases and cleanup.
There is no guest renderer application or guest worker thread on this surface.
"""

import queue

LINE_MODE = True
_cli = None


def start(cli):
    """Acquire and initialize the actual CLI; return control to the host owner."""
    global _cli
    from cli import HermesCLI

    if not isinstance(cli, HermesCLI):
        raise TypeError("line transport requires the donor HermesCLI")
    if _cli is cli:
        return status()
    if _cli is not None:
        raise RuntimeError("HermesCLI line transport is already open")
    if not cli._claim_active_session("cli"):
        raise RuntimeError("HermesCLI could not claim its session")
    if cli._active_session_lease is None:
        raise RuntimeError("HermesCLI active-session ownership was not acquired")
    _cli = cli
    try:
        cli._tui_print_startup()
        cli._tui_init_run_state()
    except BaseException as failure:
        try:
            close()
        except BaseException as cleanup:
            failure.add_note(f"HermesCLI startup cleanup failed: {cleanup}")
        raise
    return status()


def submit(line=None):
    """Run the donor dispatcher once; None drains one donor-queued continuation."""
    cli = _cli
    if cli is None:
        raise RuntimeError("HermesCLI line transport is closed")
    if line is not None and not isinstance(line, str):
        raise TypeError("HermesCLI input must be a string")
    if cli._should_exit:
        return close()
    if line is None:
        try:
            line = cli._pending_input.get_nowait()
        except queue.Empty:
            cli._tui_idle_tick()
            return status()
    cli._last_turn_attempted = False
    cli._last_turn_result = None
    cli._tui_process_one_input(line)
    if cli._should_exit:
        return close()
    return status()


def status():
    cli = _cli
    result = getattr(cli, "_last_turn_result", None) if cli is not None else None
    return {
        "open": cli is not None,
        "session_id": cli.session_id if cli is not None else None,
        "pending": cli._pending_input.qsize() if cli is not None else 0,
        "history_size": len(cli.conversation_history) if cli is not None else 0,
        "turn_attempted": bool(getattr(cli, "_last_turn_attempted", False)) if cli is not None else False,
        "model": cli.model if cli is not None else None,
        "provider": cli.provider if cli is not None else None,
        "session_store": getattr(cli, "_session_db", None) is not None if cli is not None else False,
        "turn": {
            key: result.get(key)
            for key in ("completed", "failed", "interrupted", "api_calls", "error", "failure_reason")
        } if isinstance(result, dict) else None,
    }


def close():
    """Run the donor shutdown and release its actual active-session lease once."""
    global _cli
    cli, _cli = _cli, None
    if cli is not None:
        try:
            cli._tui_shutdown()
        finally:
            cli._release_active_session()
    return status()
