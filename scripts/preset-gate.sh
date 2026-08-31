#!/bin/bash
# The preset gate: every gallery example that promises it needs nothing must RUN.
#
# The panels gallery ships 16 server-authored presets, each carrying a `needs`
# note written for a person about to adopt it. Eleven of them need no model
# provider key, and eight of those say, in as many words, "Nothing — it runs as
# it is." This gate holds them to it.
#
# Why it boots through bin/oroboros-up instead of a test rig: the failure this
# exists to prevent was IN THE LAUNCHER. preset-curator and preset-state-freeze
# both promised "Nothing — it runs as it is." and both answered "belief bag
# disabled (--belief-bag)" for as long as bin/oroboros-up omitted that flag.
# A unit test over a synthetic runner registry would have stayed green through
# the whole outage. So the gate walks the path we actually tell a newcomer to
# walk, and a launcher that stops handing the daemon what its own examples need
# fails here.
#
#   - never binds 8888. That is the operator surface.
#   - never points --home at a real forge home; the scratch home is created and
#     removed by this script.
#
# Usage: scripts/preset-gate.sh [--keep] [--all]
#          --keep   leave the scratch home in place for inspection
#          --all    also run the presets that declare a prerequisite (they are
#                   reported, never failed — they need keys or local fixtures)

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${PORT:-8897}"
KEEP=0
ALL=0
while [ $# -gt 0 ]; do
  case "$1" in
    --keep) KEEP=1 ;;
    --all)  ALL=1 ;;
    *) printf 'unknown flag: %s\n' "$1"; exit 2 ;;
  esac
  shift
done

HOME_DIR="$(mktemp -d "${TMPDIR:-/tmp}/oroboros-presetgate.XXXXXX")"
BASE="http://127.0.0.1:$PORT"

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
die() { printf '  \033[31m✗\033[0m %s\n' "$*"; exit 1; }

cleanup() {
  pkill -f "build/live/classes.*--kanban-port $PORT" >/dev/null 2>&1
  # Wait for the java process to actually let go. Removing the home while the
  # daemon is still spilling into cas/sha256 leaves the directory behind —
  # and an unreclaimed scratch home is the very leak this repo already has
  # 12G of. Give it up to 15s, then say so rather than leaking silently.
  for _ in $(seq 1 30); do
    pgrep -f "build/live/classes.*--kanban-port $PORT" >/dev/null 2>&1 || break
    sleep .5
  done
  if [ "$KEEP" = "1" ]; then
    printf '\nscratch home kept: %s\n' "$HOME_DIR"
  elif ! rm -rf "$HOME_DIR" 2>/dev/null; then
    sleep 2
    rm -rf "$HOME_DIR" 2>/dev/null \
      || printf '\n  ! could not reclaim the scratch home: %s\n' "$HOME_DIR"
  fi
}
trap cleanup EXIT

say "booting the shipped onboarding path on port $PORT"
if ! ( cd "$REPO_ROOT" && bin/oroboros-up --port "$PORT" --home "$HOME_DIR" --no-build ) >"$HOME_DIR/up.log" 2>&1; then
  tail -25 "$HOME_DIR/up.log"
  die "bin/oroboros-up did not come up"
fi
printf '  daemon answering on %s\n' "$BASE"

say "running every preset that promises it needs nothing"
BASE="$BASE" ALL="$ALL" python3 - <<'PY'
import json, os, sys, urllib.request

BASE = os.environ["BASE"]
ALL  = os.environ.get("ALL") == "1"
NEEDS_NOTHING = "Nothing — it runs as it is."

def get(path):
    with urllib.request.urlopen(BASE + path, timeout=30) as r:
        return json.load(r)

def run(name, document):
    body = json.dumps({"name": name, "document": document, "inputs": {}}).encode()
    req = urllib.request.Request(BASE + "/api/lcnc/run", data=body,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.load(r)

presets = get("/api/panels/presets").get("presets", [])
if not presets:
    print("  FAIL  the gallery served no presets at all")
    sys.exit(1)

free = [p for p in presets if p.get("needs") == NEEDS_NOTHING]
gated = [p for p in presets if p.get("needs") != NEEDS_NOTHING]
print(f"  {len(presets)} presets: {len(free)} promise they need nothing, {len(gated)} declare a prerequisite\n")

failures = []
for p in free:
    name = p["name"]
    try:
        res = run(name, p.get("document") or {})
    except Exception as e:
        print(f"  \033[31mFAIL\033[0m {name:<24} did not run: {e}")
        failures.append(name); continue
    outs = res.get("outputs") or {}
    errs = {k: v.get("error") for k, v in outs.items()
            if isinstance(v, dict) and v.get("error")}
    if res.get("ok") and not errs:
        print(f"  \033[32mPASS\033[0m {name:<24} {len(outs)} nodes, no node errored")
    else:
        # The exact shape of the outage: a preset whose own copy says it needs
        # nothing, answering with a disabled-subsystem error.
        print(f"  \033[31mFAIL\033[0m {name:<24} says {NEEDS_NOTHING!r} but: {json.dumps(errs)[:200]}")
        failures.append(name)

if ALL:
    print()
    for p in gated:
        name = p["name"]
        try:
            res = run(name, p.get("document") or {})
            outs = res.get("outputs") or {}
            errs = {k: v.get("error") for k, v in outs.items()
                    if isinstance(v, dict) and v.get("error")}
            verdict = "ran clean" if (res.get("ok") and not errs) else f"needs: {p.get('needs')}"
        except Exception as e:
            verdict = f"needs: {p.get('needs')} ({e})"
        print(f"  \033[2mnote\033[0m {name:<24} {verdict}")

print()
if failures:
    print(f"\033[31m{len(failures)} preset(s) broke a promise their own gallery copy makes: "
          + ", ".join(failures) + "\033[0m")
    sys.exit(1)
print(f"\033[32mall {len(free)} model-free presets run — no example left unhooked\033[0m")
PY
RC=$?

say "verdict"
if [ "$RC" = "0" ]; then
  printf '  \033[32mPASS\033[0m the gallery keeps its promises on the shipped launch path\n'
else
  printf '  \033[31mFAIL\033[0m see above\n'
fi
exit $RC
