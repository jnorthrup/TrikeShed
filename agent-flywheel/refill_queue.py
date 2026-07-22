#!/usr/bin/env python3
"""
refill_queue.py — keeps the flywheel queue topped off 24/7.

Reads unchecked [ ] items from doc/todo.md, cross-references against
the harvested ledger and dont-redo, and injects new tasks into
state.json queue when depth drops below the threshold.

Designed to run on cron — it's stateless and safe to run concurrently
with the flywheel main loop because it only appends to the queue.
"""
import json, os, re, sys, time
from datetime import datetime, timezone

STATE_PATH = os.environ.get("FLYWHEEL_STATE",
    os.path.expanduser("~/.local/forge/flywheel/state.json"))
REPO_PATH = os.environ.get("REPO_PATH",
    os.path.expanduser("~/.local/forge/flywheel/TrikeShed"))
TODO_PATH = os.path.join(REPO_PATH, "doc", "todo.md")
DONT_REDO = os.path.join(REPO_PATH, "dont-redo")
MIN_QUEUE = int(os.environ.get("REFILL_MIN", "10"))
MAX_INJECT = int(os.environ.get("REFILL_MAX_INJECT", "15"))

def read_todo_items():
    """Extract unchecked items from doc/todo.md."""
    if not os.path.exists(TODO_PATH):
        return []
    with open(TODO_PATH) as f:
        text = f.read()
    items = []
    for match in re.finditer(r'^- \[ \] \*\*(.+?)\*\*', text, re.M):
        title = match.group(1).strip()
        # Get the next few lines as spec context
        pos = match.end()
        rest = text[pos:pos+500]
        spec_lines = []
        for line in rest.split("\n"):
            if line.strip().startswith("- [ ]") or line.strip().startswith("- [x]"):
                break
            spec_lines.append(line.strip())
        spec = " ".join(spec_lines)[:300] if spec_lines else title
        items.append({"tier": "feature", "score": 0.8, "title": title, "spec": spec})
    return items

def read_dont_redo_titles():
    """Read completed task titles from dont-redo."""
    titles = set()
    if os.path.exists(DONT_REDO):
        with open(DONT_REDO) as f:
            for line in f:
                if "[x]" in line:
                    # Extract the task identifier (T5, CBOR-1, etc)
                    m = re.search(r'\[x\]\s+(.+?)(?:\s*—|\s*\(|$)', line)
                    if m:
                        titles.add(m.group(1).strip().lower()[:40])
    return titles

def load_state():
    if not os.path.exists(STATE_PATH):
        return None
    try:
        with open(STATE_PATH) as f:
            return json.load(f)
    except Exception:
        return None

def save_state(state):
    tmp = STATE_PATH + ".tmp"
    state["saved_at"] = datetime.now(timezone.utc).isoformat()
    with open(tmp, "w") as f:
        json.dump(state, f, indent=2)
    os.replace(tmp, STATE_PATH)

def main():
    state = load_state()
    if not state:
        print("refill: no state.json found", flush=True)
        return 1

    queue = state.get("queue", [])
    live = state.get("live", {})
    harvested = set(state.get("harvested", []))

    current_titles = set()
    for q in queue:
        current_titles.add(q.get("title", "")[:40].lower())
    for sess in live.values():
        current_titles.add(sess.get("work", {}).get("title", "")[:40].lower())

    # Also check outcomes for recently landed tasks
    for o in state.get("outcomes", []):
        if o.get("ok"):
            current_titles.add(o.get("title", "")[:40].lower())

    dont_redo = read_dont_redo_titles()
    current_titles.update(dont_redo)

    todo_items = read_todo_items()
    inject = []
    for item in todo_items:
        key = item["title"][:40].lower()
        if key in current_titles:
            continue
        inject.append(item)
        if len(inject) >= MAX_INJECT:
            break

    total_depth = len(queue) + len(live)
    if total_depth >= MIN_QUEUE and not inject:
        print(f"refill: queue depth {total_depth} >= {MIN_QUEUE}, no injection needed", flush=True)
        return 0

    if not inject:
        print(f"refill: no new tasks to inject (todo={len(todo_items)} done={len(dont_redo)})", flush=True)
        return 0

    for item in inject:
        queue.append(item)

    state["queue"] = queue
    save_state(state)
    print(f"refill: injected {len(inject)} tasks (queue={len(queue)} live={len(live)})", flush=True)
    for item in inject:
        print(f"  + {item['title'][:60]}", flush=True)
    return 0

if __name__ == "__main__":
    sys.exit(main())
