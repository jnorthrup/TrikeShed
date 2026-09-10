#!/usr/bin/env bash
# Full-build daemon verification. Existing app instances and storage stay untouched.
set -euo pipefail
inspect_only=false
if [[ ${1:-} == --inspect ]]; then inspect_only=true; shift; fi
if [[ $# -lt 4 || $# -gt 5 ]]; then
  printf 'Usage: %s [--inspect] CLASSES RESOURCES RUNTIME_LIB_DIRECTORY MANAGED_SUBVM_ROOT [NEW_OUTPUT_DIRECTORY]\n' "$0" >&2
  exit 2
fi
classes=$(cd "$1" && pwd)
resources=$(cd "$2" && pwd)
runtime_lib=$(cd "$3" && pwd)
subvm_root=$(cd "$4" && pwd)
output_dir=${5:-$(mktemp -d /private/tmp/document-curation-live.XXXXXX)}
mkdir -p "$output_dir"
output_dir=$(cd "$output_dir" && pwd)
repository=$(cd "$(dirname "$0")/.." && pwd)
mkdir "$output_dir/forge" "$output_dir/repo" "$output_dir/user-home" "$output_dir/empty-profile"
git init -q "$output_dir/repo"
python3 - "$classes" "$resources" "$runtime_lib" "$output_dir" "${TRIKESHED_JAVA_CLASSES:-}" <<'MANIFEST'
import hashlib, pathlib, sys
classes,resources,lib,out=map(pathlib.Path,sys.argv[1:5])
java_classes=[pathlib.Path(sys.argv[5])] if sys.argv[5] else []
assert (classes/'borg/trikeshed/daemon/OroborosDaemon.class').is_file()
jars=[p for p in sorted(lib.glob('*.jar')) if not any(x in p.name.lower() for x in ['trikeshed','tika','corenlp','stanford','camel','spring'])]
(out/'classpath.txt').write_text(':'.join(map(str,[classes,*java_classes,resources,*jars]))+'\n')
with (out/'runtime.sha256').open('w') as f:
 for p in [*sorted(classes.rglob('*.class')),*sorted(p for root in java_classes for p in root.rglob('*.class')),*sorted(p for p in resources.rglob('*') if p.is_file()),*jars]:
  f.write(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+str(p)+'\n')
MANIFEST
run_cp=$(cat "$output_dir/classpath.txt")
# Existing configured credentials remain local to the provider client; only the two
# approved source texts are submitted. The absorber sees the empty temporary home.
export HERMES_HOME=${HERMES_HOME:-$HOME/.hermes}
export HERMES_PROFILE="$output_dir/empty-profile"
export HERMES_ARCHIVE_PROFILE="$output_dir/empty-profile"
export WIKI_CONSOLIDATE_EVERY=0
port=$(python3 - <<'PORT'
import socket
with socket.socket() as s:
 s.bind(('127.0.0.1',0));print(s.getsockname()[1])
PORT
)
printf '%s\n' "$port" > "$output_dir/port.txt"
java -version > "$output_dir/toolchain.txt" 2>&1
date -u '+%Y-%m-%dT%H:%M:%SZ' > "$output_dir/started-at.txt"
shasum -a 256 "$repository/scripts/verify-document-curation-live.sh" "$repository/scripts/verify-document-curation-http.py" > "$output_dir/scripts.sha256"
daemon_pid=
stop_daemon() {
  if [[ -n "$daemon_pid" ]] && kill -0 "$daemon_pid" 2>/dev/null; then
    kill -TERM "$daemon_pid"
    for ((attempt=0; attempt<60; attempt++)); do
      if ! kill -0 "$daemon_pid" 2>/dev/null; then break; fi
      sleep 1
    done
    if kill -0 "$daemon_pid" 2>/dev/null; then
      printf 'Daemon did not stop within 60 seconds; preserving its PID %s\n' "$daemon_pid" >&2
      return 1
    fi
    wait "$daemon_pid"
  fi
  daemon_pid=
}
trap 'stop_daemon' EXIT
phases=(write reopen)
if $inspect_only; then phases=(inspect); fi
for phase in "${phases[@]}"; do
  java -Xmx4g -Djava.awt.headless=true -Duser.home="$output_dir/user-home" \
    -Dtrikeshed.subvm.home="$subvm_root" -cp "$run_cp" \
    borg.trikeshed.daemon.OroborosDaemon --watch --agents '' --belief-bag --document-feed \
    --kanban-port "$port" "$output_dir/forge" "$output_dir/repo" \
    > "$output_dir/$phase-daemon.out" 2> "$output_dir/$phase-daemon.err" &
  daemon_pid=$!
  printf '%s\n' "$daemon_pid" > "$output_dir/$phase-pid.txt"
  python3 - "$port" "$output_dir/$phase-contracts.json" "$daemon_pid" <<'READY'
import json, os, pathlib, sys, time, urllib.request
port,out,pid=int(sys.argv[1]),pathlib.Path(sys.argv[2]),int(sys.argv[3])
for _ in range(180):
 try:
  os.kill(pid,0)
  with urllib.request.urlopen(f'http://127.0.0.1:{port}/api/lcnc/contracts',timeout=2) as r: data=r.read()
  if 'document.curate' in data.decode():
   out.write_bytes(data);break
 except (OSError,ValueError): pass
 time.sleep(1)
else: raise RuntimeError('document.curate did not become available within 180 seconds')
READY
  if $inspect_only; then
    # GET-only inspection: no document admission, prompt or model invocation.
    python3 - "$port" "$output_dir" <<'INSPECT'
import pathlib, sys, urllib.request
port,out=int(sys.argv[1]),pathlib.Path(sys.argv[2])
for name,path in [('models','/api/mux/models'),('storage-sheet','/blackboard/sheet?key=daemon%2Fdocument-feed')]:
 with urllib.request.urlopen(f'http://127.0.0.1:{port}{path}',timeout=15) as response:
  (out/f'inspect-{name}.json').write_bytes(response.read())
INSPECT
  else
    python3 "$repository/scripts/verify-document-curation-http.py" "$phase" "http://127.0.0.1:$port" "$output_dir/http" \
      > "$output_dir/$phase-client.out" 2> "$output_dir/$phase-client.err"
  fi
  stop_daemon
  printf '%s\n' 'exited after SIGTERM' > "$output_dir/$phase-stop.txt"
  printf 'live HTTP %s and process stop: OK\n' "$phase"
done
printf 'Artifacts: %s\n' "$output_dir"
