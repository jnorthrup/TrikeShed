#!/bin/bash
# The frozen MCP Kanban proof (marketability audit MKT-003 / "demonstrable outcome").
#
# Boots a daemon on a SCRATCH forge home, drives the board entirely through
# /api/mcp as an agent would, restarts the daemon, and reads the card back —
# then prints PASS/FAIL per step and exits non-zero if any claim fails.
#
# What this proves, in one run and without trusting prose:
#   1. MCP is mounted and discoverable on a live daemon
#   2. a card written over MCP appears on the ordinary /api/board route (one
#      board, two lenses — not an MCP-private sidecar)
#   3. tags and owner survive the LCNC runner (they used to be dropped)
#   4. a move is compare-and-set: replaying a stale revision is REFUSED
#   5. the WIP limit is enforced
#   6. the card survives a daemon restart, WAL-replayed, still in its column
#
# Two deliberate safety properties, both learned the hard way:
#   - never binds 8888. That is the operator surface; a demo squatting it makes
#     a live board look empty. Override with PORT=nnnn if 8899 is taken.
#   - never points --home at a real forge home. Production state is not a demo
#     fixture; the scratch home is created and removed by this script.
#
# Usage: scripts/demo-mcp-kanban.sh [--keep]
#          --keep   leave the scratch home in place for inspection

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${PORT:-8899}"
KEEP=0
EVIDENCE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --keep) KEEP=1 ;;
    --evidence) shift; EVIDENCE="${1:-}" ;;
    *) printf 'unknown flag: %s\n' "$1"; exit 2 ;;
  esac
  shift
done

HOME_DIR="$(mktemp -d "${TMPDIR:-/tmp}/oroboros-demo.XXXXXX")"
LOG="$HOME_DIR/daemon.log"
BASE="http://127.0.0.1:$PORT"
MCP="$BASE/api/mcp"
PASS=0
FAIL=0

# Wall-clock from process start, so the run doubles as the trial-friction
# measurement the audit asks for (MKT-005): how long until a buyer sees a card.
T0=$(date +%s)
T_FIRST_VALUE=""
elapsed() { echo $(( $(date +%s) - T0 )); }

# Every assertion is recorded as claim/expected/observed/verdict, so a run can
# hand back the evidence bundle MKT-004 asks for instead of only a green bar.
# TAB-separated because the values are short and shell JSON quoting is a trap;
# python renders the JSON at the end.
EV_FILE="$(mktemp "${TMPDIR:-/tmp}/oroboros-evidence.XXXXXX")"
record() { printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" >>"$EV_FILE"; }

say()  { printf '\n\033[1m== %s\033[0m \033[2m(+%ss)\033[0m\n' "$*" "$(elapsed)"; SECTION="$*"; }
ok()   { PASS=$((PASS+1)); printf '  \033[32mPASS\033[0m %s\n' "$*"; record "$*" "" "" pass; }
bad()  { FAIL=$((FAIL+1)); printf '  \033[31mFAIL\033[0m %s\n' "$*"; record "$*" "" "" FAIL; }
check(){
  if [ "$1" = "$2" ]; then
    PASS=$((PASS+1)); printf '  \033[32mPASS\033[0m %s\n' "$3"; record "$3" "$1" "$2" pass
  else
    FAIL=$((FAIL+1)); printf '  \033[31mFAIL\033[0m %s (expected \x27%s\x27, got \x27%s\x27)\n' "$3" "$1" "$2"; record "$3" "$1" "$2" FAIL
  fi
}

# Kill by classpath, not by wrapper pid: bin/oroboros-daemon spawns java as a
# child, so killing the wrapper leaves the port held.
stop_daemon() {
  pgrep -f "build/live/classes.*--kanban-port $PORT" >/dev/null 2>&1 && \
    pkill -f "build/live/classes.*--kanban-port $PORT" 2>/dev/null
  for _ in $(seq 1 20); do
    pgrep -f "build/live/classes.*--kanban-port $PORT" >/dev/null 2>&1 || return 0
    sleep 1
  done
  pkill -9 -f "build/live/classes.*--kanban-port $PORT" 2>/dev/null
  return 0
}

# Only ever stop a daemon THIS script started. The EXIT trap is installed before
# the port guard runs, so without this a refusal would still fire stop_daemon and
# kill the very daemon the guard exists to protect.
WE_STARTED=0
cleanup() {
  [ "$WE_STARTED" = "1" ] && stop_daemon
  rm -f "$EV_FILE" 2>/dev/null
  if [ "$KEEP" = "1" ]; then
    printf '\nscratch home kept: %s\n' "$HOME_DIR"
  else
    rm -rf "$HOME_DIR" 2>/dev/null
  fi
}
trap cleanup EXIT

start_daemon() {
  ( cd "$REPO_ROOT" && nohup bin/oroboros-daemon \
      --home "$HOME_DIR" --repo "$REPO_ROOT" --kanban-port "$PORT" >>"$LOG" 2>&1 & )
  for _ in $(seq 1 60); do
    curl -sf -m 2 "$BASE/api/board" >/dev/null 2>&1 && return 0
    sleep 2
  done
  printf 'daemon did not come up on %s; tail of %s:\n' "$PORT" "$LOG"
  tail -20 "$LOG"
  return 1
}

rpc() { # rpc <id> <method> <params-json|-->
  local id="$1" method="$2" params="${3:-}"
  local body
  if [ -z "$params" ] || [ "$params" = "--" ]; then
    body="{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"$method\"}"
  else
    body="{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"$method\",\"params\":$params}"
  fi
  curl -sf -m 15 "$MCP" -H 'Content-Type: application/json' -d "$body"
}

# jq is not assumed: this repo ships no jq dependency, and a demo that needs one
# is a demo that does not run on a clean machine.
pyq() { python3 -c "$1" 2>/dev/null; }

say "0. the port must be OURS"
# Without this the proof is worthless and dangerous. If something already holds
# the port, `start_daemon`'s readiness curl is answered by THAT daemon: the demo
# then submits a card to a stranger's forge home, and its restart step kills a
# process it did not start. That is exactly what happened when this ran on a port
# `oroboros-up` had already taken — the card was written to one home, looked for
# in another, and reported a false durability failure while killing the other
# daemon on the way out.
if lsof -ti:"$PORT" >/dev/null 2>&1; then
  bad "port $PORT is already in use — refusing to run"
  printf '       Something is already serving %s. This demo must own its port: it\n' "$PORT"
  printf '       starts a daemon on a scratch home and stops it again, and adopting\n'
  printf '       a daemon it did not start would test the wrong state and kill the\n'
  printf '       wrong process.\n'
  printf '       Try:  PORT=%s scripts/demo-mcp-kanban.sh\n' "$((PORT+10))"
  exit 1
fi
ok "port $PORT is free — the daemon this starts is the one it tests"

say "1. build feed (so the daemon runs the code in this checkout, not a stale build/live)"
if ( cd "$REPO_ROOT" && ./gradlew hotswapFeed --console=plain -q >/dev/null 2>&1 ); then
  ok "hotswapFeed published build/live/classes"
else
  bad "hotswapFeed failed — the daemon would boot stale; see the guide's step 1b"
  exit 1
fi

say "2. boot a daemon on a scratch home (port $PORT, never 8888)"
if start_daemon; then WE_STARTED=1; ok "daemon up at $BASE"; else bad "daemon failed to boot"; exit 1; fi

say "3. MCP is mounted and discoverable"
CARD="$(curl -sf -m 10 "$MCP")"
check "oroboros-lcnc-kanban" "$(printf '%s' "$CARD" | pyq 'import sys,json;print(json.load(sys.stdin)["server"])')" "GET /api/mcp names the server"
TOOLS="$(rpc 1 tools/list | pyq 'import sys,json;print(",".join(t["name"] for t in json.load(sys.stdin)["result"]["tools"]))')"
check "kanban.submit,kanban.move" "$TOOLS" "tools/list offers exactly the two LCNC runners"

say "4. an agent writes a card over MCP"
SUB="$(rpc 2 tools/call '{"name":"kanban.submit","arguments":{"title":"Written by the demo","tags":["demo"],"owner":"agent"}}')"
JOB="$(printf '%s' "$SUB" | pyq 'import sys,json;print(json.load(sys.stdin)["result"]["structuredContent"]["jobId"])')"
REV="$(printf '%s' "$SUB" | pyq 'import sys,json;print(json.load(sys.stdin)["result"]["structuredContent"]["revision"])')"
CID="$(printf '%s' "$SUB" | pyq 'import sys,json;print(json.load(sys.stdin)["result"]["structuredContent"]["cid"])')"
if [ -n "$JOB" ]; then T_FIRST_VALUE="$(elapsed)"; ok "submit accepted: $JOB at revision $REV"; else bad "submit produced no jobId"; fi
if [ -n "$CID" ]; then ok "committed with a CAS receipt: ${CID:0:24}…"; else bad "no CAS receipt on the write"; fi

say "5. the same card is on the ordinary board route (one board, two lenses)"
BOARD_TITLE="$(curl -sf -m 10 "$BASE/api/board" | pyq "import sys,json;print(next((i['title'] for i in json.load(sys.stdin)['items'] if i['id']=='$JOB'),''))")"
check "Written by the demo" "$BOARD_TITLE" "/api/board shows the MCP-written card"

say "6. tags and owner survived the LCNC runner"
CARDJSON="$(rpc 3 resources/read "{\"uri\":\"oroboros://lcnc/kanban/cards/$JOB\"}" | pyq 'import sys,json;print(json.load(sys.stdin)["result"]["contents"][0]["text"])')"
check "demo" "$(printf '%s' "$CARDJSON" | pyq 'import sys,json;print(",".join(json.load(sys.stdin)["tags"]))')" "tags round-tripped"
check "agent" "$(printf '%s' "$CARDJSON" | pyq 'import sys,json;print(json.load(sys.stdin)["owner"])')" "owner round-tripped"

say "7. move is compare-and-set"
MV="$(rpc 4 tools/call "{\"name\":\"kanban.move\",\"arguments\":{\"jobId\":\"$JOB\",\"toColumn\":\"running\",\"expectedRevision\":$REV}}")"
check "False" "$(printf '%s' "$MV" | pyq 'import sys,json;print(json.load(sys.stdin)["result"]["isError"])')" "move accepted"
STALE="$(rpc 5 tools/call "{\"name\":\"kanban.move\",\"arguments\":{\"jobId\":\"$JOB\",\"toColumn\":\"done\",\"expectedRevision\":$REV}}")"
check "True" "$(printf '%s' "$STALE" | pyq 'import sys,json;print(json.load(sys.stdin)["result"]["isError"])')" "replaying the stale revision is REFUSED"
printf '       reason: %s\n' "$(printf '%s' "$STALE" | pyq 'import sys,json;print(json.load(sys.stdin)["result"]["content"][0]["text"])')"

say "8. the WIP limit is enforced (running holds 3)"
for i in 1 2 3 4; do
  S="$(rpc "$((10+i))" tools/call "{\"name\":\"kanban.submit\",\"arguments\":{\"title\":\"Rusher $i\"}}")"
  J="$(printf '%s' "$S" | pyq 'import sys,json;print(json.load(sys.stdin)["result"]["structuredContent"]["jobId"])')"
  R="$(printf '%s' "$S" | pyq 'import sys,json;print(json.load(sys.stdin)["result"]["structuredContent"]["revision"])')"
  rpc "$((20+i))" tools/call "{\"name\":\"kanban.move\",\"arguments\":{\"jobId\":\"$J\",\"toColumn\":\"running\",\"expectedRevision\":$R}}" >/dev/null
done
RUNNING="$(curl -sf -m 10 "$BASE/api/board" | pyq 'import sys,json;print(sum(1 for i in json.load(sys.stdin)["items"] if i["status"]=="running"))')"
check "3" "$RUNNING" "exactly 3 cards in running despite 5 attempts"

say "9. restart the daemon on the SAME home — the WAL is the state"
stop_daemon
if start_daemon; then ok "daemon restarted"; else bad "daemon failed to restart"; exit 1; fi
AFTER="$(rpc 30 resources/read "{\"uri\":\"oroboros://lcnc/kanban/cards/$JOB\"}" | pyq 'import sys,json;print(json.load(sys.stdin)["result"]["contents"][0]["text"])')"
check "running" "$(printf '%s' "$AFTER" | pyq 'import sys,json;print(json.load(sys.stdin)["status"])')" "the agent's card is still in running after restart"
check "agent" "$(printf '%s' "$AFTER" | pyq 'import sys,json;print(json.load(sys.stdin)["owner"])')" "its owner survived replay"

say "trial friction (MKT-005 — this machine, warm caches)"
printf '  time to first value (boot -> card on the board): %ss\n' "${T_FIRST_VALUE:-n/a}"
printf '  total run (incl. restart + replay verify):       %ss\n' "$(elapsed)"
printf '  build/live/classes + build/staging/lib:          %s\n' \
  "$(du -shc "$REPO_ROOT/build/live/classes" "$REPO_ROOT/build/staging/lib" 2>/dev/null | tail -1 | cut -f1)"
printf '  scratch forge home after the run:                %s\n' "$(du -sh "$HOME_DIR" 2>/dev/null | cut -f1)"
printf '  java:                                           %s\n' "$(java -version 2>&1 | head -1)"

if [ -n "$EVIDENCE" ]; then
  EV_FILE="$EV_FILE" OUT="$EVIDENCE" PASS="$PASS" FAIL="$FAIL" \
  TFV="${T_FIRST_VALUE:-}" TOTAL="$(elapsed)" PORT="$PORT" REPO="$REPO_ROOT" \
  python3 - <<'EOF'
import json, os, subprocess, datetime

def sh(*a):
    try: return subprocess.check_output(a, stderr=subprocess.DEVNULL, text=True).strip()
    except Exception: return None

rows = []
with open(os.environ["EV_FILE"]) as f:
    for line in f:
        parts = line.rstrip("\n").split("\t")
        if len(parts) != 4: continue
        claim, expected, observed, verdict = parts
        row = {"claim": claim, "verdict": verdict}
        # Only assertions that compared two values carry expected/observed; the
        # rest are presence checks and would be padded with empty strings.
        if expected or observed:
            row["expected"], row["observed"] = expected, observed
        rows.append(row)

bundle = {
    "schema": "oroboros.mcp-kanban.evidence/1",
    "capturedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
    "subject": "LCNC Kanban over MCP (/api/mcp)",
    "producer": "scripts/demo-mcp-kanban.sh",
    "environment": {
        "gitRev": sh("git", "-C", os.environ["REPO"], "rev-parse", "HEAD"),
        "gitDirty": bool(sh("git", "-C", os.environ["REPO"], "status", "--porcelain")),
        "java": (sh("java", "-version") or "").splitlines()[0] if sh("java", "-version") else None,
        "uname": sh("uname", "-sm"),
        "port": int(os.environ["PORT"]),
        "forgeHome": "scratch (created and removed by this run)",
    },
    "timings": {
        "timeToFirstValueSeconds": int(os.environ["TFV"]) if os.environ.get("TFV") else None,
        "totalSeconds": int(os.environ["TOTAL"]),
        "note": "warm caches, single machine; a cold clone with first compile is not measured here",
    },
    "summary": {"pass": int(os.environ["PASS"]), "fail": int(os.environ["FAIL"]), "total": len(rows)},
    "claims": rows,
}
with open(os.environ["OUT"], "w") as f:
    json.dump(bundle, f, indent=2)
    f.write("\n")
print(f"  evidence bundle: {os.environ['OUT']} ({len(rows)} claims)")
EOF
fi

printf '\n\033[1m%s\033[0m\n' "----------------------------------------"
printf '\033[1mPASS %d   FAIL %d\033[0m   (%s)\n' "$PASS" "$FAIL" "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
[ "$FAIL" -eq 0 ] || exit 1
