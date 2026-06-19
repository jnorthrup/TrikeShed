#!/usr/bin/env python3.14
"""Flask web UI for the kanban board.

Serves the board state as JSON API and renders an HTML dashboard.
Board state is loaded from the persisted Confix JSON.

Usage:
    python3.14 bin/kanban_ui.py [--port PORT]
"""

from __future__ import annotations
import os
import sys
import argparse
import json

# Make tspy importable
_REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(_REPO, "libs", "tspy", "src", "python"))

from flask import Flask, jsonify, render_template_string
from tspy.kanban import KanbanBoard, Card, KeyEntry, Column, KeyStatus
from tspy.kanban import board_to_confix, confix_to_board
from tspy.keymux import get_store
from tspy.modelmux import get_mux

app = Flask(__name__)

STATE_DIR = ".tspy/kanban"
BOARD_JSON = os.path.join(STATE_DIR, "board.json")


def load_board() -> KanbanBoard:
    """Load board from persisted Confix JSON, or create fresh."""
    if os.path.exists(BOARD_JSON):
        with open(BOARD_JSON) as f:
            data = json.load(f)
        store = get_store()
        mux = get_mux()
        board = confix_to_board(data, store=store, mux=mux)
        print(f"Loaded board: {len(board.cards)} cards, tick {board.tick_count}")
        return board
    
    # Fresh board
    store = get_store()
    mux = get_mux()
    mux.refresh()
    keys = []
    for mk in store:
        models = mux.models(mk.provider)
        model = models[0] if models else ""
        keys.append(KeyEntry(
            key_id=mk.key_id,
            provider=mk.provider,
            label=mk.provider,
            model=model,
        ))
    cards = [
        Card(id=f"task-{i}", title=f"agent task {i}", priority=1 + (i % 3))
        for i in range(20)
    ]
    return KanbanBoard(keys=keys, cards=cards, store=store, mux=mux)


# Global board instance
BOARD: KanbanBoard = None


HTML_TEMPLATE = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Kanban -- TrikeShed</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
         background: #0f1117; color: #e6e6e6; min-height: 100vh; }
  header { background: #1a1d27; border-bottom: 1px solid #2a2d3a; padding: 1rem 2rem; }
  header h1 { font-size: 1.25rem; font-weight: 600; color: #7ee787; }
  header p { font-size: 0.75rem; color: #8b949e; margin-top: 0.25rem; }
  
  .metrics { display: flex; gap: 1.5rem; padding: 1rem 2rem; border-bottom: 1px solid #2a2d3a; }
  .metric { text-align: center; }
  .metric .value { font-size: 1.75rem; font-weight: 700; color: #79c0ff; }
  .metric .label { font-size: 0.7rem; color: #8b949e; text-transform: uppercase; letter-spacing: 0.05em; }
  
  .board { display: grid; grid-template-columns: repeat(3, 1fr); gap: 1rem; padding: 1rem 2rem; }
  .column { background: #1a1d27; border-radius: 8px; border: 1px solid #2a2d3a; overflow: hidden; }
  .column-header { padding: 0.75rem 1rem; font-size: 0.8rem; font-weight: 600;
                   text-transform: uppercase; letter-spacing: 0.05em; }
  .column-header.todo { background: #1f2937; color: #fbbf24; }
  .column-header.doing { background: #1e3a5f; color: #60a5fa; }
  .column-header.done { background: #14532d; color: #4ade80; }
  .column-cards { padding: 0.5rem; min-height: 200px; }
  
  .card { background: #0f1117; border: 1px solid #2a2d3a; border-radius: 6px;
          padding: 0.6rem 0.75rem; margin-bottom: 0.5rem; font-size: 0.8rem; }
  .card-title { font-weight: 500; margin-bottom: 0.25rem; }
  .card-meta { font-size: 0.65rem; color: #8b949e; }
  .card .priority { display: inline-block; width: 6px; height: 6px; border-radius: 50%;
                    margin-right: 4px; }
  .priority-high { background: #f87171; }
  .priority-med { background: #fbbf24; }
  .priority-low { background: #4ade80; }
  
  .keys-section { border-top: 1px solid #2a2d3a; padding: 1rem 2rem; }
  .keys-section h2 { font-size: 0.8rem; color: #8b949e; text-transform: uppercase;
                     letter-spacing: 0.05em; margin-bottom: 0.75rem; }
  .keys-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 0.5rem; }
  .key-item { background: #1a1d27; border: 1px solid #2a2d3a; border-radius: 6px;
              padding: 0.5rem 0.75rem; font-size: 0.75rem; }
  .key-item .provider { font-weight: 600; color: #79c0ff; }
  .key-item .leased { color: #f87171; }
  .key-item .free { color: #4ade80; }
  
  .actions { padding: 1rem 2rem; border-top: 1px solid #2a2d3a; }
  .btn { background: #238636; color: white; border: none; border-radius: 6px;
         padding: 0.5rem 1rem; font-size: 0.8rem; cursor: pointer; margin-right: 0.5rem; }
  .btn:hover { background: #2ea043; }
  .btn-refresh { background: #1f6feb; }
  .btn-refresh:hover { background: #388bfd; }
  
  .error-banner { background: #7f1d1d; color: #fca5a5; padding: 0.75rem 2rem; display: none; }
</style>
</head>
<body>
<div class="error-banner" id="error"></div>

<header>
  <h1>Kanban -- TrikeShed</h1>
  <p>Confix persistence | IntEnum handles</p>
</header>

<div class="metrics" id="metrics">
  <div class="metric"><div class="value" id="m-todo">--</div><div class="label">Todo</div></div>
  <div class="metric"><div class="value" id="m-doing">--</div><div class="label">Doing</div></div>
  <div class="metric"><div class="value" id="m-done">--</div><div class="label">Done</div></div>
  <div class="metric"><div class="value" id="m-util">--</div><div class="label">Util</div></div>
  <div class="metric"><div class="value" id="m-throughput">--</div><div class="label">Throughput</div></div>
  <div class="metric"><div class="value" id="m-tick">--</div><div class="label">Tick</div></div>
</div>

<div class="board">
  <div class="column">
    <div class="column-header todo">Todo <span id="count-todo"></span></div>
    <div class="column-cards" id="cards-todo"></div>
  </div>
  <div class="column">
    <div class="column-header doing">Doing <span id="count-doing"></span></div>
    <div class="column-cards" id="cards-doing"></div>
  </div>
  <div class="column">
    <div class="column-header done">Done <span id="count-done"></span></div>
    <div class="column-cards" id="cards-done"></div>
  </div>
</div>

<div class="keys-section">
  <h2>Keys (<span id="key-count">0</span>)</h2>
  <div class="keys-grid" id="keys-grid"></div>
</div>

<div class="actions">
  <button class="btn btn-refresh" onclick="refresh()">Refresh</button>
  <button class="btn" onclick="resetBoard()">Reset Board</button>
</div>

<script>
const POLL_MS = 3000;

function api(path, opts) {
  return fetch('/api' + path, opts || {}).then(function(r) {
    if (!r.ok) throw new Error('API error ' + r.status);
    return r.json();
  });
}

function priorityClass(p) {
  if (p >= 3) return 'priority-high';
  if (p >= 2) return 'priority-med';
  return 'priority-low';
}

function renderCards(cards, elId) {
  var el = document.getElementById(elId);
  if (!cards || !cards.length) { el.innerHTML = ''; return; }
  el.innerHTML = cards.map(function(c) {
    return '<div class="card">' +
      '<div class="card-title">' +
        '<span class="priority ' + priorityClass(c.priority) + '"></span>' +
        c.id +
      '</div>' +
      '<div class="card-meta">' + (c.title || '') + '</div>' +
      (c.assignee ? '<div class="card-meta">-&gt; ' + c.assignee + '</div>' : '') +
    '</div>';
  }).join('');
}

function renderKeys(keys) {
  var grid = document.getElementById('keys-grid');
  var vals = Object.values(keys);
  if (!vals.length) { grid.innerHTML = ''; return; }
  grid.innerHTML = vals.map(function(k) {
    var leased = k.leased_to != null;
    return '<div class="key-item">' +
      '<span class="provider">' + k.provider + '</span>' +
      ' <span class="' + (leased ? 'leased' : 'free') + '">' +
        (leased ? 'L ' + k.leased_to : 'FREE') +
      '</span>' +
      '<div class="card-meta">access=' + k.access_count + '</div>' +
    '</div>';
  }).join('');
}

function renderMetrics(data) {
  document.getElementById('m-todo').textContent = data._todo;
  document.getElementById('m-doing').textContent = data._doing;
  document.getElementById('m-done').textContent = data._done;
  document.getElementById('m-util').textContent = Math.round(data.worker_util * 100) + '%';
  document.getElementById('m-throughput').textContent = data.throughput.toFixed ? data.throughput.toFixed(2) : data.throughput;
  document.getElementById('m-tick').textContent = data.tick_count;
  
  document.getElementById('count-todo').textContent = '(' + data._todo + ')';
  document.getElementById('count-doing').textContent = '(' + data._doing + ')';
  document.getElementById('count-done').textContent = '(' + data._done + ')';
  document.getElementById('key-count').textContent = Object.keys(data.keys || {}).length;
}

function showError(msg) {
  var el = document.getElementById('error');
  el.textContent = msg;
  el.style.display = 'block';
  setTimeout(function() { el.style.display = 'none'; }, 5000);
}

async function refresh() {
  try {
    var data = await api('/board');
    
    // Compute column counts from cards
    var todo = [], doing = [], done = [];
    for (var i = 0; i < data.cards.length; i++) {
      var c = data.cards[i];
      if (c.column === 'TODO' || c.column === 'todo' || c.column === 'C0') todo.push(c);
      else if (c.column === 'DOING' || c.column === 'doing' || c.column === 'C1') doing.push(c);
      else if (c.column === 'DONE' || c.column === 'done' || c.column === 'C2') done.push(c);
    }
    
    data._todo = todo.length;
    data._doing = doing.length;
    data._done = done.length;
    
    renderMetrics(data);
    renderCards(todo, 'cards-todo');
    renderCards(doing, 'cards-doing');
    renderCards(done, 'cards-done');
    renderKeys(data.keys);
  } catch(e) {
    showError('Failed to load board: ' + e.message);
  }
}

async function resetBoard() {
  try {
    await api('/reset', { method: 'POST' });
    refresh();
  } catch(e) {
    showError('Reset failed: ' + e.message);
  }
}

refresh();
setInterval(refresh, POLL_MS);
</script>
</body>
</html>
"""


@app.route("/")
def index():
    return render_template_string(HTML_TEMPLATE)


@app.route("/api/board")
def get_board():
    global BOARD
    return jsonify(board_to_confix(BOARD))


@app.route("/api/reset", methods=["POST"])
def reset_board():
    global BOARD
    store = get_store()
    mux = get_mux()
    mux.refresh()
    keys = []
    for mk in store:
        models = mux.models(mk.provider)
        model = models[0] if models else ""
        keys.append(KeyEntry(
            key_id=mk.key_id,
            provider=mk.provider,
            label=mk.provider,
            model=model,
        ))
    cards = [
        Card(id=f"task-{i}", title=f"agent task {i}", priority=1 + (i % 3))
        for i in range(20)
    ]
    BOARD = KanbanBoard(keys=keys, cards=cards, store=store, mux=mux)
    return jsonify({"status": "reset", "tick": BOARD.tick_count})


def main():
    global BOARD
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=5555)
    args = parser.parse_args()
    
    BOARD = load_board()
    
    print(f"Starting Kanban UI on http://localhost:{args.port}")
    print(f"Board: {len(BOARD.keys)} keys, {len(BOARD.cards)} cards")
    print(f"State: {BOARD_JSON}")
    app.run(host="0.0.0.0", port=args.port, debug=False, threaded=True)


if __name__ == "__main__":
    main()
