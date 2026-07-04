"""
forge_agent.py — persistent Hermes-style agent logic in GraalPy.

This runs inside the GraalVM Python context. The host bridge `forge` is bound
before this script loads. The agent operates over the shared blackboard and
the Kanban board cursor projection — never over a parallel object graph.

Persistent loop:
  1. Read goal/intent from blackboard
  2. Inspect board state via forge.board_cursor_json()
  3. Decide next action
  4. Write decision back to blackboard
  5. Optionally call native fallbacks for heavy computation

Self-improvement:
  The agent writes its own heuristics back into the blackboard under
  'agent.heuristics.*' keys. On the next cycle, it reads its prior heuristics
  and adapts. The blackboard is the durable memory; the Python context is
  ephemeral and can be forked/reset without losing state.
"""

# ── Agent state ──────────────────────────────────────────────

_cycle = 0

def get_cycle():
    return _cycle

def reset_cycle():
    global _cycle
    _cycle = 0

# ── Blackboard accessors ─────────────────────────────────────

def read(key):
    """Read from the shared blackboard."""
    return forge.blackboard_get(key)

def write(key, value):
    """Write to the shared blackboard (visible to Kotlin and other agents)."""
    forge.blackboard_put(key, value)
    return value

# ── Board inspection ─────────────────────────────────────────

def board_state():
    """Get the current Kanban board (Confix-serialized KanbanBoard)."""
    import json
    raw = forge.board_json()
    try:
        board = json.loads(raw)
        return {
            "rowCount": len(board.get("cards", [])),
            "columns": board.get("columns", []),
            "cards": board.get("cards", []),
        }
    except Exception:
        return {"rowCount": 0, "columns": [], "cards": []}

# ── Native lib fallback ──────────────────────────────────────

def native(lib, **kwargs):
    """Call a heavy native lib that can't run in GraalPy.
    Falls back to the Kotlin host implementation."""
    return forge.native_call(lib, dict(kwargs))

# ── Persistent agent cycle ───────────────────────────────────

def run_cycle():
    """Execute one agent decision cycle. Returns the action taken."""
    global _cycle
    _cycle += 1

    # Read goal from blackboard
    goal = read("agent.goal") or "maintain board health"

    # Inspect board
    board = board_state()
    row_count = board.get("rowCount", 0)

    # Read prior heuristics (self-improvement memory)
    heuristics_raw = read("agent.heuristics")
    heuristics = []
    if heuristics_raw and isinstance(heuristics_raw, str):
        import json
        try:
            heuristics = json.loads(heuristics_raw)
        except Exception:
            heuristics = []
    elif isinstance(heuristics_raw, list):
        heuristics = heuristics_raw

    # Decide: simple WIP-balance heuristic
    # In a real agent this is where the LLM/model loop lives.
    action = {
        "cycle": _cycle,
        "goal": goal,
        "boardRows": row_count,
        "heuristicsCount": len(heuristics),
        "decision": "inspect" if row_count == 0 else "prioritize",
    }

    # Write decision to blackboard (durable across context resets)
    write(f"agent.cycle.{_cycle}.decision", action["decision"])

    # Self-improve: append the heuristic that worked
    if action["decision"] == "prioritize":
        heuristics.append({"cycle": _cycle, "saw": row_count})
        if len(heuristics) > 100:
            heuristics = heuristics[-50:]  # cap memory
        import json
        write("agent.heuristics", json.dumps(heuristics))

    return action

def run_cycles(n=1):
    """Run n cycles, returning the last decision."""
    last = None
    for _ in range(n):
        last = run_cycle()
    return last

# ── Self-improvement surface ─────────────────────────────────

def seed(goal="maintain board health"):
    """Seed the agent's persistent state on the blackboard."""
    write("agent.goal", goal)
    write("agent.heuristics", "[]")
    write("agent.seeded", True)
    return True

def status():
    """Return current agent status for host inspection."""
    return {
        "cycle": _cycle,
        "goal": read("agent.goal"),
        "seeded": read("agent.seeded"),
        "heuristicsSize": len(str(read("agent.heuristics") or "")),
        "boardRows": board_state().get("rowCount", 0),
    }
