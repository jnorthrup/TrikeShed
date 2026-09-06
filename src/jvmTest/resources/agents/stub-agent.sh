#!/bin/sh
# A stand-in coding agent for the claim-loop tests (invoked as `sh <this file>` by the roster).
# It reads its brief on stdin like codex does, edits the scratch clone it is run in, and
# answers in the REPLY shape the plane judge parses. Knobs for the tests:
#   STUB_SLEEP=<seconds>   sleep before answering (the budget kill test)
#   STUB_BYTES=<n>         print n bytes of filler first (the output cap test)
#   STUB_EXIT=<code>       exit with this code after answering (default 0)
#   STUB_EVIDENCE=<id>     cite this evidence id instead of the one the brief names
brief=$(cat)
# The brief's AGENT block names the evidence id this run's diff will carry; cite it like a real agent would.
evidence=$(printf '%s\n' "$brief" | sed -n 's/.*evidence id \([^; ]*\);.*/\1/p' | head -1)
if [ -n "${STUB_SLEEP:-}" ]; then sleep "$STUB_SLEEP"; fi
if [ -n "${STUB_BYTES:-}" ]; then
  awk -v n="$STUB_BYTES" 'BEGIN { s = ""; while (length(s) < 1024) s = s "filler-bytes-"; while (n > 0) { if (n >= 1024) { printf "%s", s; n -= 1024 } else { printf "%s", substr(s, 1, n); n = 0 } } }'
  printf '\n'
fi
printf 'hello\n' > hello.txt
printf 'brief received: %s chars\n' "$(printf '%s' "$brief" | wc -c | tr -d ' ')"
printf 'edited hello.txt in %s\n' "$(pwd)"
printf 'VERDICT: MET\n'
printf 'MUST-1: MET — evidence: %s\n' "${STUB_EVIDENCE:-${evidence:-none}}"
printf 'ACTION: hello.txt now exists at the repo root.\n'
exit "${STUB_EXIT:-0}"
