#!/bin/bash
# The blackboard gate: everything LCNC lives on the daemon's blackboard, every
# cable is typed exactly, and the surfaces OBEY it. Verified with the thing,
# not about the thing — a scratch daemon is booted the way a newcomer boots
# one and every claim below is a request against it with the payload checked.
#
#   1. lcnc/vocabulary and lcnc/program/<preset> are on the board, every cable typed.
#   2. A panel save produces two deltas on /blackboard/facts: the program, then the vocabulary.
#   3. A preset's name is refused (409).
#   4. The run seam obeys the entry: a kind-mismatched program is 400 before it runs;
#      a user composite referenced by type runs.
#   5. A program that exists ONLY on the board (raw /blackboard/assert) is reconciled,
#      opens through /api/panels/<name>, and runs.
#   6. An entry EDITED on the board is obeyed, not clobbered by its source.
#   7. (if node + playwright-core + Chrome are present) the rendered canvas obeys:
#      refuses json→List<TurnFact>, accepts List<TurnFact>→Any, defers an undeclared
#      scope.in, and rebuilds its palette when another tab saves a composite.
#
#   - never binds 8888. That is the operator surface.
#   - never points --home at a real forge home; the scratch home is created and
#     removed by this script.
#
# Usage: scripts/blackboard-gate.sh [--keep]
set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${PORT:-8896}"
KEEP=0
while [ $# -gt 0 ]; do
  case "$1" in
    --keep) KEEP=1 ;;
    *) printf 'unknown flag: %s\n' "$1"; exit 2 ;;
  esac
  shift
done
[ "$PORT" != "8888" ] || { printf '8888 is the operator surface; pick another PORT\n'; exit 2; }

HOME_DIR="$(mktemp -d "${TMPDIR:-/tmp}/oroboros-bbgate.XXXXXX")"
BASE="http://127.0.0.1:$PORT"

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
die() { printf '  \033[31m✗\033[0m %s\n' "$*"; exit 1; }

cleanup() {
  pkill -f "build/live/classes.*--kanban-port $PORT" >/dev/null 2>&1
  for _ in $(seq 1 30); do
    pgrep -f "build/live/classes.*--kanban-port $PORT" >/dev/null 2>&1 || break
    sleep .5
  done
  if [ "$KEEP" = "1" ]; then
    printf '\nscratch home kept: %s\n' "$HOME_DIR"
  elif ! rm -rf "$HOME_DIR" 2>/dev/null; then
    sleep 2
    rm -rf "$HOME_DIR" 2>/dev/null || printf '\n  ! could not reclaim the scratch home: %s\n' "$HOME_DIR"
  fi
}
trap cleanup EXIT

say "booting a scratch daemon on port $PORT"
if ! ( cd "$REPO_ROOT" && bin/oroboros-up --port "$PORT" --home "$HOME_DIR" --no-build ) >"$HOME_DIR/up.log" 2>&1; then
  tail -25 "$HOME_DIR/up.log"
  die "bin/oroboros-up did not come up"
fi
for _ in $(seq 1 60); do
  curl -sf -m 3 "$BASE/blackboard/board" | grep -q 'lcnc/vocabulary' && break
  sleep 2
done
curl -sf -m 3 "$BASE/blackboard/board" | grep -q 'lcnc/vocabulary' || die "lcnc/vocabulary never appeared on the board"
printf '  daemon answering on %s, lcnc/vocabulary on the board\n' "$BASE"

say "1–6: the board, the deltas, the run seam — checked payload by payload"
# The SSE listener is armed BEFORE the save so the deltas are observed, not inferred.
timeout 30 curl -sN "$BASE/blackboard/facts" > "$HOME_DIR/sse.log" 2>/dev/null &
SSE_PID=$!
sleep 2
PRE_BYTES=$(wc -c < "$HOME_DIR/sse.log" | tr -d ' ')

BASE="$BASE" HOME_DIR="$HOME_DIR" PRE_BYTES="$PRE_BYTES" python3 - <<'PY'
import json, os, sys, time, urllib.request, urllib.error

BASE = os.environ["BASE"]; HOME = os.environ["HOME_DIR"]; PRE = int(os.environ["PRE_BYTES"])
fails = []
def ok(msg): print(f"  \033[32mPASS\033[0m {msg}")
def bad(msg): print(f"  \033[31mFAIL\033[0m {msg}"); fails.append(msg)

def req(method, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    r = urllib.request.Request(BASE + path, data=data, method=method, headers={"content-type": "application/json"})
    try:
        with urllib.request.urlopen(r, timeout=120) as resp:
            raw = resp.read().decode()
            return resp.status, (json.loads(raw) if raw.strip().startswith(("{", "[")) else raw)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        return e.code, (json.loads(raw) if raw.strip().startswith(("{", "[")) else raw)

def board():
    return req("GET", "/blackboard/board")[1]["board"]

COMPOSITE = {"nodes": [{"id": "p1", "type": "scope.in", "params": {"name": "s", "kind": "text"}, "x": 0, "y": 0},
                       {"id": "r", "type": "scope.out", "params": {"name": "out", "kind": "text"}, "x": 200, "y": 0}],
             "wires": [{"from": ["p1", "value"], "to": ["r", "value"]}]}
CURATOR_BUG = {"nodes": [{"id": "n2", "type": "beliefs.introspect", "params": {}, "x": 0, "y": 0},
                         {"id": "n3", "type": "beliefs.review", "params": {}, "x": 200, "y": 0}],
               "wires": [{"from": ["n2", "field"], "to": ["n3", "facts"]}]}

# 1. the board holds the vocabulary and every preset, cables typed
b = board()
vocab = b.get("lcnc/vocabulary")
_, api = req("GET", "/api/lcnc/contracts")
if vocab and len(vocab["contracts"]) == len(api["contracts"]) and "kindAcceptance" in vocab and "bindings" in vocab:
    ok(f"lcnc/vocabulary on the board: {len(vocab['contracts'])} contracts, bindings {vocab['bindings']}, same count as /api/lcnc/contracts")
else:
    bad(f"lcnc/vocabulary missing or not what the route serves: board={vocab and len(vocab['contracts'])} api={len(api['contracts'])}")
presets = [p["name"] for p in req("GET", "/api/panels/presets")[1]["presets"]]
missing = [n for n in presets if f"lcnc/program/{n}" not in b]
typed = nulls = 0; violating = []
for n in presets:
    e = b.get(f"lcnc/program/{n}") or {}
    for c in e.get("cables", []):
        if "type" not in c: bad(f"{n}: a cable without a type key: {c}")
        elif c["type"] is None: nulls += 1
        else: typed += 1
    if e.get("violations"): violating.append(n)
if not missing and not violating:
    ok(f"lcnc/program/<name> for all {len(presets)} presets; cables typed {typed}, unresolved {nulls}, violations 0")
else:
    bad(f"presets missing from the board {missing}; presets with violations {violating}")

# 2. a save produces the two deltas
st, r = req("POST", "/api/panels/gate-twice", COMPOSITE)
if st == 200 and r.get("verdict") == "ok" and r.get("violations") == []: ok("POST /api/panels/gate-twice: verdict ok, violations []")
else: bad(f"save of gate-twice: {st} {r}")
time.sleep(3)
tail = open(os.path.join(HOME, "sse.log"), "rb").read()[PRE:].decode(errors="replace")
seqs = {}
for line in tail.splitlines():
    if line.startswith("data:"):
        try:
            d = json.loads(line[5:].strip()); seqs.setdefault(d["key"], []).append(d["seq"])
        except Exception: pass
prog_seq = seqs.get("lcnc/program/gate-twice", []); voc_seq = seqs.get("lcnc/vocabulary", [])
if prog_seq and voc_seq and min(voc_seq) > min(prog_seq):
    ok(f"the save produced deltas after the listener's pre-save offset: lcnc/program/gate-twice seq {prog_seq[0]}, then lcnc/vocabulary seq {min(voc_seq)}")
else:
    bad(f"deltas not observed after the save: program {prog_seq} vocabulary {voc_seq}")
_, api2 = req("GET", "/api/lcnc/contracts")
hit = [c for c in api2["contracts"] if c["type"] == "gate-twice"]
if hit and (hit[0].get("binding") or {}).get("kind") == "composite": ok("the vocabulary now lists gate-twice bound as composite")
else: bad(f"gate-twice not a composite in the vocabulary: {hit}")

# 3. a preset's name is refused
st, r = req("POST", "/api/panels/preset-curator", COMPOSITE)
if st == 409 and isinstance(r, dict) and r.get("error") == "name_is_a_preset": ok("POST /api/panels/preset-curator → 409 name_is_a_preset")
else: bad(f"a preset's name was not refused: {st} {r}")

# 4. the run seam obeys the entry
st, r = req("POST", "/api/panels/gate-bug", CURATOR_BUG)
v = r.get("violations") if isinstance(r, dict) else None
if st == 200 and v and v[0]["rule"] == "kind-mismatch" and "List<TurnFact>" in v[0]["detail"]: ok(f"saving the curator wire reports its violation: {v[0]['detail']}")
else: bad(f"save of gate-bug did not report the violation: {st} {r}")
st, r = req("POST", "/api/lcnc/run", {"program": "gate-bug"})
if st == 400 and r.get("error") == "type_check_failed": ok("POST /api/lcnc/run gate-bug → 400 type_check_failed (the entry's violations are obeyed)")
else: bad(f"gate-bug ran or failed differently: {st} {r}")
st, r = req("GET", "/api/panels/gate-bug?entry=1")
if st == 200 and isinstance(r, dict) and len(r.get("cables", [])) == 1 and r["cables"][0].get("type") == "json" and len(r.get("violations", [])) == 1:
    ok("GET /api/panels/gate-bug?entry=1 serves the board entry the canvas shows: cable typed json, one violation")
else: bad(f"the board entry route: {st} {str(r)[:200]}")
OUTER = {"nodes": [{"id": "lit", "type": "text.value", "params": {"value": "hi"}, "x": 0, "y": 0},
                   {"id": "c", "type": "gate-twice", "params": {}, "x": 200, "y": 0}],
         "wires": [{"from": ["lit", "value"], "to": ["c", "s"]}]}
st, r = req("POST", "/api/panels/gate-outer", OUTER)
if st == 200 and r.get("violations") == []: ok("gate-outer (uses gate-twice by type) saves with no violations against the late-bound vocabulary")
else: bad(f"gate-outer save: {st} {r}")
st, r = req("POST", "/api/lcnc/run", {"program": "gate-outer"})
if st == 200 and r.get("ok") and r["outputs"].get("c", {}).get("out") == "hi": ok('gate-outer runs: outputs.c.out == "hi"')
else: bad(f"gate-outer run: {st} {r}")

# 5. a program that exists only on the board
doc = {"nodes": [{"id": "t", "type": "text.value", "params": {"value": "from-the-board"}, "x": 0, "y": 0},
                 {"id": "d", "type": "display", "params": {}, "x": 200, "y": 0}],
       "wires": [{"from": ["t", "value"], "to": ["d", "x"]}]}
st, _ = req("POST", "/blackboard/assert", {"lcnc/program/gate-asserted": {"name": "gate-asserted", "document": doc, "cables": [], "violations": []}})
time.sleep(1)
st, r = req("POST", "/api/lcnc/run", {"program": "gate-asserted"})
if st == 200 and r.get("ok") and r["outputs"].get("d", {}).get("x") == "from-the-board": ok('a board-only program runs: outputs.d.x == "from-the-board"')
else: bad(f"board-only program: {st} {r}")
e = board().get("lcnc/program/gate-asserted") or {}
if len(e.get("cables", [])) == 1 and e["cables"][0].get("type") == "text": ok("…and its raw entry was reconciled on load: one cable, typed text")
else: bad(f"raw entry not reconciled: {e.get('cables')}")
st, r = req("GET", "/api/panels/gate-asserted")
if st == 200 and isinstance(r, dict) and len(r.get("nodes", [])) == 2: ok("GET /api/panels/gate-asserted serves the board's document (no attachment exists)")
else: bad(f"board-only program does not open through /api/panels: {st} {str(r)[:120]}")

# 6. a board edit is obeyed, not clobbered by the source
st, r = req("POST", "/api/lcnc/run", {"program": "preset-scope-inner"})
if not (st == 200 and r.get("returns", {}).get("result") == "hello"): bad(f"preset-scope-inner baseline: {st} {r}")
e = board().get("lcnc/program/preset-scope-inner")
edited = json.loads(json.dumps(e))
for n in edited["document"]["nodes"]:
    if n["type"] == "scope.in": n["params"]["default"] = "edited-on-the-board"
edited["cables"] = []   # a board edit need not carry types; the loader reconciles it
st, _ = req("POST", "/blackboard/assert", {"lcnc/program/preset-scope-inner": edited})
time.sleep(1)
st, r = req("POST", "/api/lcnc/run", {"program": "preset-scope-inner"})
if st == 200 and r.get("returns", {}).get("result") == "edited-on-the-board": ok('an entry edited on the board is obeyed: preset-scope-inner returns "edited-on-the-board"')
else: bad(f"the board edit was clobbered or ignored: {st} {r}")

print()
if fails:
    print(f"\033[31m{len(fails)} check(s) failed\033[0m"); sys.exit(1)
print("\033[32mevery blackboard check holds\033[0m")
PY
RC=$?
wait $SSE_PID 2>/dev/null
[ "$RC" = "0" ] || die "the blackboard is not obeyed"

say "7: the rendered canvas obeys (skipped if node/playwright-core/Chrome are absent)"
PWC="/opt/homebrew/lib/node_modules/@playwright/mcp/node_modules/playwright-core"
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
if command -v node >/dev/null 2>&1 && [ -d "$PWC" ] && [ -x "$CHROME" ]; then
  cat > "$HOME_DIR/canvas.mjs" <<EOF
import { chromium } from '$PWC/index.mjs';
const B = '$BASE';
const b = await chromium.launch({ headless: true, executablePath: '$CHROME' });
const A = await b.newPage({ viewport: { width: 1400, height: 900 } });
await A.goto(B + '/panels', { waitUntil: 'domcontentloaded' });
await A.waitForFunction(() => document.querySelectorAll('#palette .pitem').length > 50, null, { timeout: 30000 });
const r = await A.evaluate(() => ({
  refusesJsonIntoTurnFacts: kindsCompatible('json', 'List<TurnFact>') === false,
  acceptsTurnFactsIntoAny: kindsCompatible('List<TurnFact>', 'Any') === true,
  scopeInDefers: nodeKindOf({ type: 'scope.in', params: { name: 'brief' } }, 'out', 'value') === '*',
  scopeInDeclared: nodeKindOf({ type: 'scope.in', params: { name: 'brief', kind: 'text' } }, 'out', 'value') === 'text',
  seatPromptIsText: CONTRACTS['council.seat'].inputKinds.prompt === 'text',
}));
const Bp = await b.newPage();
await Bp.goto(B + '/api/panels', { waitUntil: 'domcontentloaded' });
await Bp.evaluate(async () => { await fetch('/api/panels/gate-composite-b', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ nodes: [{ id: 'p1', type: 'scope.in', params: { name: 'q', kind: 'text' }, x: 0, y: 0 }, { id: 'r', type: 'scope.out', params: { name: 'out', kind: 'text' }, x: 200, y: 0 }], wires: [{ from: ['p1', 'value'], to: ['r', 'value'] }] }) }); });
let rebuilt = false;
try { await A.waitForFunction(() => [...document.querySelectorAll('#palette .pitem')].some(e => e.textContent.startsWith('gate-composite-b')), null, { timeout: 10000 }); rebuilt = true; } catch (e) {}
r.paletteRebuiltOnDelta = rebuilt;
console.log(JSON.stringify(r));
await b.close();
process.exit(Object.values(r).every(Boolean) ? 0 : 1);
EOF
  if OUT=$(timeout 120 node "$HOME_DIR/canvas.mjs" 2>&1); then
    printf '  \033[32mPASS\033[0m %s\n' "$OUT"
  else
    printf '  \033[31mFAIL\033[0m %s\n' "$OUT"; die "the rendered canvas does not obey"
  fi
else
  printf '  \033[33mskipped\033[0m node, playwright-core or Chrome not found; the daemon checks above are the gate\n'
fi

printf '\n\033[32mblackboard gate: PASS\033[0m\n'
