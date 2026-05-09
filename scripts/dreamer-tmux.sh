#!/bin/bash
# dreamer-tmux.sh — Launch Dreamer Paper Trader in a tmux session
#
# Creates a 3-pane layout:
#   Top-left:   Paper trading engine (live output, TUI dashboard)
#   Top-right:  CouchDB sync watcher (GitTreeSelfHost status)
#   Bottom:     Build watcher / logs
#
# Usage: ./dreamer-tmux.sh [session-name]

set -e
SESSION="${1:-dreamer}"
REPO="/Users/jim/work/TrikeShed"
GRADLE_HOME="/tmp/gradle-home"
GRADLE="/tmp/gradle-9.5.0/bin/gradle"

# Check tmux
command -v tmux >/dev/null 2>&1 || { echo "Install tmux: brew install tmux"; exit 1; }

# Kill existing session if any
tmux kill-session -t "$SESSION" 2>/dev/null || true

# Create new session with engine in first pane
tmux new-session -d -s "$SESSION" -n engine -c "$REPO" \
  "echo '=== Dreamer Paper Trader ===' && \
   echo 'Loading .env credentials...' && \
   GRADLE_USER_HOME=$GRADLE_HOME $GRADLE --no-daemon :libs:dreamer-kmm:jvmRun 2>&1"

# Top-right: CouchDB sync watcher
tmux split-window -h -p 40 -c "$REPO" \
  "watch -n 5 'echo \"=== GitTree Couch Store ===\" && \
   find .couch -name \"*.ndjson\" 2>/dev/null | wc -l | xargs echo \"Documents:\" && \
   ls -la .couch/trike_git/ 2>/dev/null | head -8'"

# Bottom: Build status
tmux split-window -v -p 30 -c "$REPO" \
  "echo '=== Build Watcher ===' && \
   echo 'Waiting for changes...' && \
   while inotifywait -r -e modify,create,delete src/ libs/ 2>/dev/null; do \
     echo \"\$(date +%H:%M:%S) Change detected\" ; \
   done"

tmux select-pane -t 0
tmux attach -t "$SESSION"
