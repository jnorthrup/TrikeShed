#!/bin/bash
# oroboros-doctor — what to run before asking anyone for help, and what to paste
# when you do.
#
# Every check here is a failure mode that actually happened and cost real time,
# not a hypothetical. Each one prints OK / WARN / FAIL and, when it can, the
# exact command that fixes it. Exit code is the number of FAILs.
#
# Usage: scripts/oroboros-doctor.sh [--port N] [--fix]
#          --port N   check this port instead of the default 8888
#          --fix      perform the safe repairs (never touches a forge home)

set -uo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT=8888
FIX=0
while [ $# -gt 0 ]; do
  case "$1" in
    --port) shift; PORT="${1:-8888}" ;;
    --fix)  FIX=1 ;;
    *) printf 'unknown flag: %s\n' "$1"; exit 2 ;;
  esac
  shift
done

FAILS=0
ok()   { printf '  \033[32mOK\033[0m    %s\n' "$*"; }
warn() { printf '  \033[33mWARN\033[0m  %s\n' "$*"; }
bad()  { FAILS=$((FAILS+1)); printf '  \033[31mFAIL\033[0m  %s\n' "$*"; }
fix()  { printf '        ↳ fix: %s\n' "$*"; }
sec()  { printf '\n\033[1m%s\033[0m\n' "$*"; }

sec "identity — paste this when you report a problem"
printf '  repo        %s\n' "$REPO"
printf '  git         %s%s\n' "$(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo '?')" \
  "$([ -n "$(git -C "$REPO" status --porcelain 2>/dev/null)" ] && echo ' (dirty)')"
printf '  branch      %s\n' "$(git -C "$REPO" rev-parse --abbrev-ref HEAD 2>/dev/null || echo '?')"
printf '  java        %s\n' "$(java -version 2>&1 | head -1)"
printf '  os          %s\n' "$(uname -sm)"
printf '  date        %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

sec "toolchain"
JV="$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+)\..*/\1/')"
if [ "${JV:-0}" -ge 25 ] 2>/dev/null; then ok "JDK $JV (needs 25+)"; else
  bad "JDK ${JV:-unknown} — the daemon needs 25+"; fix "sdk use java 25.0.4.1-graal"; fi
[ -x "$REPO/gradlew" ] && ok "gradle wrapper present" || bad "gradlew missing or not executable"

sec "disk headroom — the one that kills a RUNNING daemon"
# A full volume does not produce a tidy error. The JVM's flight recorder fails to
# write, logs "An irrecoverable error in Jfr. Shutting down VM", and the daemon
# DIES MID-REQUEST — routes that answered a second ago return an empty body, which
# reads exactly like a code regression in whatever you touched last. Observed
# 2026-08-31: 4.9G free of 460G, daemon killed while serving /api/lcnc/run.
# The CAS section below explains where the space went; this section is about
# whether you can still run at all. Checked on every volume that matters,
# because /, $TMPDIR and the forge home are frequently not the same device.
disk_check() {
  # $1 = path, $2 = label
  [ -d "$1" ] || return 0
  AVAIL_K=$(df -k "$1" 2>/dev/null | awk 'NR==2{print $4}')
  [ -n "${AVAIL_K:-}" ] || return 0
  AVAIL_G=$(( AVAIL_K / 1048576 ))
  PCT=$(df -k "$1" 2>/dev/null | awk 'NR==2{gsub(/%/,"",$5); print $5}')
  if [ "$AVAIL_K" -lt 2097152 ]; then
    bad "$2: ${AVAIL_G}G free (${PCT}% used) — a daemon will be killed by the JFR writer"
    fix "free space before anything else; the CAS under a forge home is the usual culprit"
  elif [ "$AVAIL_K" -lt 10485760 ]; then
    warn "$2: ${AVAIL_G}G free (${PCT}% used) — a long run plus a gradle build can exhaust this"
    fix "check the CAS sizes in the state section below"
  else
    ok "$2: ${AVAIL_G}G free (${PCT}% used)"
  fi
}
disk_check "$REPO" "repo volume"
disk_check "${TMPDIR:-/tmp}" "TMPDIR (scratch forge homes)"

sec "build freshness — the silent one"
# The daemon runs from build/live/classes, NOT build/classes. jvmMainClasses
# does not refresh it, and the self-heal only fires when build/live is ABSENT.
# A stale build/live boots quietly stale and looks like your code is broken.
LIVE="$REPO/build/live/classes"
if [ ! -d "$LIVE" ]; then
  warn "build/live/classes absent — the daemon will self-heal by running hotswapFeed"
  fix "./gradlew hotswapFeed"
else
  # Only MAIN sources land in build/live/classes. Comparing against test sources
  # reported "stale" every time a test was edited — a doctor that cries wolf gets
  # ignored, which is worse than not having one.
  NEWEST_SRC=$(find "$REPO/src" -path '*Main/*' -name '*.kt' -newer "$LIVE" 2>/dev/null | head -1)
  if [ -n "$NEWEST_SRC" ]; then
    bad "build/live/classes is STALE — the daemon is serving older code than your tree"
    fix "./gradlew hotswapFeed   (then restart the daemon)"
    printf '        e.g. newer: %s\n' "${NEWEST_SRC#$REPO/}"
    [ "$FIX" = "1" ] && { printf '        running hotswapFeed…\n'; (cd "$REPO" && ./gradlew hotswapFeed -q >/dev/null 2>&1) && ok "rebuilt"; }
  else
    ok "build/live/classes is current"
  fi
fi
RES="$REPO/build/processedResources/jvm/main/web/panels.html"
if [ -f "$RES" ] && [ "$REPO/src/commonMain/resources/web/panels.html" -nt "$RES" ]; then
  bad "web resources are STALE — /panels serves an older page than your tree"
  fix "./gradlew jvmProcessResources   (no daemon restart needed; the page is read per request)"
  [ "$FIX" = "1" ] && { (cd "$REPO" && ./gradlew jvmProcessResources -q >/dev/null 2>&1) && ok "resources republished"; }
elif [ -f "$RES" ]; then ok "web resources current"; fi

sec "processes and ports"
# bin/oroboros-daemon is a WRAPPER: killing its pid leaves the java child holding
# the port. Always identify daemons by classpath.
DAEMONS=$(pgrep -f 'build/live/classes' 2>/dev/null | tr '\n' ' ')
if [ -n "${DAEMONS// /}" ]; then
  ok "daemon(s) running: ${DAEMONS% }"
  for p in $DAEMONS; do
    printf '        pid %-7s up %-10s %s\n' "$p" "$(ps -o etime= -p "$p" 2>/dev/null | tr -d ' ')" \
      "$(ps -o command= -p "$p" 2>/dev/null | grep -o '\--home [^ ]*' || echo '--home ?')"
  done
else warn "no daemon running"; fix "bin/oroboros-daemon --home ~/.local/forge --repo ."; fi

if command -v lsof >/dev/null 2>&1 && lsof -ti:"$PORT" >/dev/null 2>&1; then
  ok "port $PORT is listening"
  if curl -sf -m 3 "http://127.0.0.1:$PORT/api/board" >/dev/null 2>&1; then ok "  /api/board answers"
  else bad "  port $PORT is held but /api/board does not answer — wrong process on the port?"; fi
  if curl -sf -m 3 "http://127.0.0.1:$PORT/api/mcp" >/dev/null 2>&1; then
    ok "  /api/mcp answers ($(curl -s -m 3 "http://127.0.0.1:$PORT/api/mcp" | sed -E 's/.*"server":"([^"]*)".*/\1/'))"
  else warn "  /api/mcp does not answer — daemon predates the MCP mount, or the module is detached"
       fix "restart the daemon after ./gradlew hotswapFeed"; fi
else
  warn "nothing listening on $PORT"
fi

sec "runaway test workers — these burn cores silently"
# A killed Gradle task leaves the forked JVM spinning; two of them once ran
# 1h47m at 100% CPU before anyone noticed.
WORKERS=$(pgrep -f 'jvmTest/work' 2>/dev/null | tr '\n' ' ')
if [ -n "${WORKERS// /}" ]; then
  LONG=0
  for p in $WORKERS; do
    E=$(ps -o etime= -p "$p" 2>/dev/null | tr -d ' ')
    C=$(ps -o %cpu= -p "$p" 2>/dev/null | tr -d ' ')
    printf '        pid %-7s up %-10s cpu %s%%\n' "$p" "$E" "$C"
    case "$E" in *-*|*:*:*) LONG=1 ;; esac
  done
  if [ "$LONG" = "1" ]; then
    bad "a test worker has been running over an hour — almost certainly orphaned"
    fix "pkill -f jvmTest/work && rm -rf build/tmp/jvmTest"
    [ "$FIX" = "1" ] && { pkill -f 'jvmTest/work' 2>/dev/null; rm -rf "$REPO/build/tmp/jvmTest"; ok "workers cleared"; }
  else ok "test worker running (a build is in progress)"; fi
else ok "no stray test workers"; fi

sec "state"
for H in "$HOME/.local/forge"; do
  [ -d "$H" ] || continue
  printf '  forge home  %s (%s)\n' "$H" "$(du -sh "$H" 2>/dev/null | cut -f1)"
  if [ -d "$H/cas" ]; then
    CAS_KB=$(du -sk "$H/cas" 2>/dev/null | cut -f1)
    CAS_N=$(find "$H/cas" -type f 2>/dev/null | wc -l | tr -d ' ')
    printf '  cas         %s in %s blobs\n' "$(du -sh "$H/cas" 2>/dev/null | cut -f1)" "$CAS_N"
    # Growth rate, measured against a reference file. NOTE: BSD find does not
    # accept GNU relative times (-newermt '-1 hour' silently matches NOTHING and
    # reports healthy), which is exactly how this went unnoticed.
    REF=$(mktemp); touch -t "$(date -v-1H '+%Y%m%d%H%M')" "$REF" 2>/dev/null
    if [ -f "$REF" ]; then
      RECENT=$(find "$H/cas" -type f -newer "$REF" 2>/dev/null | wc -l | tr -d ' ')
      rm -f "$REF"
      [ "${RECENT:-0}" -gt 0 ] && printf '  growth      %s blobs in the last hour\n' "$RECENT"
    fi
    # Block-rounding waste, sampled (a full scan takes ~10 minutes at this size).
    SAMPLE=$(find "$H/cas" -type f 2>/dev/null | head -2000)
    if [ -n "$SAMPLE" ]; then
      SMALL=$(printf '%s\n' "$SAMPLE" | xargs stat -f %z 2>/dev/null | awk '$1<4096{n++} END {print n+0}')
      printf '  small blobs %s%% of a 2000-blob sample are under one 4K block\n' \
        "$(( SMALL * 100 / 2000 ))"
    fi
    # CAS blobs are full byte copies and nothing reclaims them. Measured on this
    # machine 2026-08-30: 287k blobs / 5.0G on disk for 4.0G of content (1.26x,
    # ~1G lost to block rounding), the whole of it written within six hours, at
    # ~51k blobs/hour with a median blob of 330 bytes. Flag it before it becomes
    # "I had to clear dozens of GB".
    if [ "${CAS_KB:-0}" -gt 20971520 ] 2>/dev/null; then
      bad "CAS is over 20G and nothing reclaims it"
      fix "stop the daemon and prune $H/cas, or move to a store with dedup"
    elif [ "${CAS_KB:-0}" -gt 4194304 ] 2>/dev/null; then
      warn "CAS is over 4G and grows while the daemon runs (no reclamation today)"
      fix "watch it: scripts/oroboros-doctor.sh | grep cas"
    fi
  fi
  [ -d "$H/.kanban" ] && printf '  board wal   %s\n' "$(du -sh "$H/.kanban" 2>/dev/null | cut -f1)"
done

sec "summary"
if [ "$FAILS" -eq 0 ]; then printf '  \033[32mno failures\033[0m\n'
else printf '  \033[31m%d failure(s)\033[0m — see the fix lines above; re-run with --fix to apply the safe ones\n' "$FAILS"; fi
exit "$FAILS"
