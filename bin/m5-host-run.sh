#!/usr/bin/env bash
# bin/m5-host-run.sh — lane w-m5-host driver for VAL-BTRFS-006/-008/-009/-007.
#
# Hosts the REAL oroboros daemon inside the mission-002 decision-D10 container with its
# forge home ON the file-backed btrfs volume created by bin/trikeshed-btrfs, published on
# host port :8890 (NEVER 8888 — the macOS operator daemon owns that and is never touched).
#
# Same shape as bin/trikeshed-btrfs and bin/m5-store-run.sh: on Darwin it prints the docker
# run command line VERBATIM and re-enters ITSELF inside the container by piping its own file
# on stdin (single-FILE bind mounts fail on this virtiofs setup).
#
# Subcommands:
#   phase-a        mount; boot the daemon with TRIKESHED_CAS=btrfs; VAL-BTRFS-006 identity +
#                  time-bounded write + ro negative control; VAL-BTRFS-008 materialize
#                  measurement; VAL-BTRFS-009 snapshot/mutation; VAL-BTRFS-007 manifest +
#                  daemon restart; unmount + losetup -d. Container is DESTROYED afterwards.
#   phase-b        FRESH container: re-attach the loop device, remount, manifest re-check,
#                  daemon restart #3, HTTP read-back, scrub, unmount.
#   control        FileCasStore CONTROL run — no volume mounted, forge home on the container
#                  overlay: the store-attributable observable must be ABSENT and the
#                  /api/cas/* routes must not exist.
#   boot-refusal   TRIKESHED_CAS=btrfs with a CAS root that is NOT on btrfs — the daemon must
#                  refuse LOUDLY at boot and exit non-zero (decision D6).
#
# BIND-MOUNT FENCE: exactly two mounts — ~/.local/trikeshed-btrfs (rw, the image directory,
# which doubles as this lane's log/ack channel) and the repo READ-ONLY. $HOME, ~/.local,
# ~/.local/forge and ~/.hermes are never mounted.
#
# BUILD ISOLATION: the patched daemon is compiled to build/m5-host/classes and placed FIRST
# on the classpath. This lane never runs ./gradlew hotswapFeed and never writes build/live/**.

set -uo pipefail

IMG_DIR_HOST="$HOME/.local/trikeshed-btrfs"
REPO_HOST="/Users/jim/work/TrikeShed"
IMG_NAME="trikeshed-store.img"
MNT="/mnt/trikeshed"
FORGE="$MNT/forge"
CAS="$FORGE/cas"
WIKI="$FORGE/wiki"
IMG="/img/$IMG_NAME"
WORK="/img/m5-host"                       # host-visible work dir (logs, manifest, acks)
DOCKER_IMAGE="eclipse-temurin:25-jdk"
PORT=8890
CP="/repo/build/m5-host/classes:/repo/build/live/classes:/repo/build/processedResources/jvm/main:/repo/build/staging/lib/*"
MAIN="borg.trikeshed.daemon.OroborosDaemon"
BASE="http://127.0.0.1:$PORT"

CMD="${1:-phase-a}"

if [ "$(uname -s)" = "Darwin" ]; then
  SELF="${BASH_SOURCE[0]}"
  mkdir -p "$IMG_DIR_HOST/m5-host"
  case "$CMD" in
    phase-a)      NAME=m5-host-a ;;
    phase-b)      NAME=m5-host-b ;;
    control)      NAME=m5-host-control ;;
    boot-refusal) NAME=m5-host-refusal ;;
    *) echo "unknown subcommand: $CMD" >&2; exit 2 ;;
  esac
  DOCKER_ARGS=(docker run --name "$NAME" -i --privileged
               -p "$PORT:$PORT"
               -v "$IMG_DIR_HOST:/img"
               -v "$REPO_HOST:/repo:ro"
               "$DOCKER_IMAGE" bash -s -- "$CMD")
  echo "### macOS shell: docker run command line, VERBATIM (bind mounts: ONLY $IMG_DIR_HOST rw and $REPO_HOST ro)"
  printf '%q ' "${DOCKER_ARGS[@]}"; printf '< %q\n' "$SELF"
  exec "${DOCKER_ARGS[@]}" < "$SELF"
fi

# ─────────────────────────── in-container from here ───────────────────────────

ctx() { echo; echo "### axis-2 container: $*"; }
mkdir -p "$WORK"

ensure_tools() {
  if ! command -v btrfs >/dev/null 2>&1 || ! command -v curl >/dev/null 2>&1 || ! command -v git >/dev/null 2>&1; then
    ctx "apt-get install btrfs-progs btrfs-compsize e2fsprogs util-linux (decision D10) + curl + git"
    echo "(TWO packages beyond the D10 list, both forced by what is under test, not by the measurement:"
    echo "   curl — an HTTP surface cannot be exercised without an HTTP client;"
    echo "   git  — the daemon's own preflight execs 'git fetch' (OroborosDaemon.kt:1878-1885) and,"
    echo "          without the binary, the resulting IOException cancels the daemon's job and the"
    echo "          process exits seconds after 'daemon up'. Observed in the first phase-a attempt."
    echo " The IMAGE is unchanged: eclipse-temurin:25-jdk, decision D10.)"
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
      btrfs-progs btrfs-compsize e2fsprogs util-linux curl git >/dev/null
  fi
  ctx "TOOL PROVENANCE"
  btrfs --version; filefrag -V 2>&1 | head -1; cp --version | head -1
  losetup --version | head -1; curl --version | head -1
  java -version 2>&1 | head -1; uname -m; uname -r
}

sync_fs() { ctx "sync; btrfs filesystem sync $MNT   (MANDATORY before every extent/space reading — decision D12)"; sync; btrfs filesystem sync "$MNT"; }

attach_and_mount() {
  ctx "losetup -j $IMG  (loop device located BY BACKING FILE, never a hardcoded /dev/loopN)"
  losetup -j "$IMG" || true
  DEV="$(losetup -j "$IMG" 2>/dev/null | cut -d: -f1 | head -1)"
  if [ -z "$DEV" ]; then DEV="$(losetup -f --show "$IMG")"; echo "attached $DEV -> $IMG"; else echo "reusing $DEV -> $IMG"; fi
  mkdir -p "$MNT"
  ctx "mount $DEV $MNT   (mount only; NO mkfs anywhere in this lane)"
  mount "$DEV" "$MNT"
  findmnt -no FSTYPE,SOURCE,TARGET -T "$MNT"
  echo "$DEV" > "$WORK/dev"
}

wait_health() {
  local tries="${1:-180}" i
  for i in $(seq 1 "$tries"); do
    if curl -s -m 2 "$BASE/api/health" >/dev/null 2>&1; then
      echo "daemon answering on :$PORT after ${i}s"; return 0
    fi
    sleep 1
  done
  echo "daemon DID NOT answer on :$PORT within ${tries}s"; return 1
}

wait_boot_line() {   # $1 = log, $2 = grep pattern, $3 = seconds
  local i
  for i in $(seq 1 "$3"); do grep -q "$2" "$1" 2>/dev/null && { echo "saw '$2' after ${i}s"; return 0; }; sleep 1; done
  echo "did NOT see '$2' within $3s"; return 1
}

wait_ack() {         # $1 = name, $2 = seconds — lets the macOS side act while we hold still
  local i
  ctx "HOLDING for macOS-side observation: touch $IMG_DIR_HOST/m5-host/ack-$1 (waiting up to $2s)"
  for i in $(seq 1 "$2"); do
    [ -f "$WORK/ack-$1" ] && { rm -f "$WORK/ack-$1"; echo "ack-$1 received after ${i}s"; return 0; }
    sleep 1
  done
  echo "no ack-$1 within $2s — continuing"
}

start_daemon() {     # $1 = tag, $2 = forge home, $3 = TRIKESHED_CAS value
  local tag="$1" fh="$2" sel="$3"
  ctx "START DAEMON [$tag]: TRIKESHED_CAS=$sel java -cp $CP $MAIN $fh /repo --kanban-port $PORT"
  echo "(repo is mounted READ-ONLY at /repo; forge home is $fh)"
  TRIKESHED_CAS="$sel" TRIKESHED_NO_KANBAN_MODULE=1 \
    java -cp "$CP" "$MAIN" "$fh" /repo --kanban-port "$PORT" \
    > "$WORK/daemon-$tag.log" 2>&1 &
  DAEMON_PID=$!
  echo "daemon pid=$DAEMON_PID log=$WORK/daemon-$tag.log"
}

stop_daemon() {
  ctx "STOP DAEMON: kill $DAEMON_PID (the daemon is REALLY stopped — no 'survives' claim over a live process)"
  kill "$DAEMON_PID" 2>/dev/null || true
  local i
  for i in $(seq 1 40); do kill -0 "$DAEMON_PID" 2>/dev/null || break; sleep 1; done
  kill -9 "$DAEMON_PID" 2>/dev/null || true
  wait "$DAEMON_PID" 2>/dev/null
  echo "daemon $DAEMON_PID stopped; curl must now fail:"
  curl -s -m 3 -o /dev/null -w 'exit-check http_code=%{http_code}\n' "$BASE/api/health" || echo "curl failed as expected (connection refused)"
}

boot_cas_root() {    # echo the casRoot the daemon RESOLVED, out of its OWN boot log
  grep -m1 '\[OROBOROS\] CAS STORE SELECTED' "$1" | sed -E 's/.*casRoot=([^ ]+).*/\1/'
}

put_blob() {         # $1 = file, $2 = label — response saved to $WORK/put-$2.json and printed
  ctx "HTTP WRITE [$2]: curl -X POST --data-binary @$1 $BASE/api/cas/put   ($(stat -c %s "$1") bytes)"
  curl -sS -X POST --data-binary "@$1" -H 'Content-Type: application/octet-stream' \
       -o "$WORK/put-$2.json" -w 'HTTP %{http_code}\n' "$BASE/api/cas/put"
  cat "$WORK/put-$2.json"; echo
}

# $1 = file holding one JSON object, $2 = field name
jf() { grep -o "\"$2\":\"[^\"]*\"" "$1" | head -1 | sed 's/.*":"//; s/"$//'; }

blob_manifest() {    # $1 = out file — VAL-BTRFS-002's counting rule
  find "$CAS/sha256" -type f -regextype posix-extended -regex '.*/sha256/[0-9a-f]{2}/[0-9a-f]{62}' 2>/dev/null \
    | sed -E 's|.*/sha256/([0-9a-f]{2})/([0-9a-f]{62})$|\1\2|' | sort > "$1"
}
blob_bytes() {       # total bytes of the cids listed in $1
  local total=0 c f
  while read -r c; do
    f="$CAS/sha256/${c:0:2}/${c:2}"
    [ -f "$f" ] && total=$((total + $(stat -c %s "$f")))
  done < "$1"
  echo "$total"
}

measure() {          # $1 = path, $2 = label
  echo "--- $2: $1"
  filefrag -v "$1" | sed -n '1,10p'
  btrfs filesystem du -s "$1"
  compsize "$1" 2>/dev/null || true
}

case "$CMD" in

# ═══════════════════════════════════════════════════════════════════════════════
phase-a)
  ensure_tools
  attach_and_mount
  DEV="$(cat "$WORK/dev")"

  sync_fs
  ctx "df -h $MNT  — BEFORE (VAL-BTRFS-006 capacity watch; preceded by the sync above)"
  df -h "$MNT"

  ctx "btrfs subvolume list $MNT  — BEFORE the daemon (no snapshot of the CAS plane exists)"
  btrfs subvolume list "$MNT"

  ctx "PRE-DAEMON blob set (VAL-BTRFS-002's counting rule)"
  blob_manifest "$WORK/blobs-pre-daemon.txt"; wc -l < "$WORK/blobs-pre-daemon.txt"

  # ── VAL-BTRFS-006 (a): forge-home identity ────────────────────────────────
  ctx "[VAL-BTRFS-006a] findmnt -no FSTYPE,SOURCE -T $FORGE   (fstype AND backing device on ONE line)"
  findmnt -no FSTYPE,SOURCE -T "$FORGE"
  ctx "[VAL-BTRFS-006a] readlink -f $FORGE   (no symlink/bind indirection between the path and the mountpoint)"
  readlink -f "$FORGE"
  ctx "[VAL-BTRFS-006a] btrfs subvolume show $CAS"
  btrfs subvolume show "$CAS"
  ctx "[VAL-BTRFS-006a] stat -c %i $CAS   (a subvolume root is always inode 256)"
  stat -c %i "$CAS"

  # ── boot the daemon on the btrfs store ────────────────────────────────────
  start_daemon a-btrfs "$FORGE" btrfs
  wait_health 300 || { ctx "BOOT FAILED — tail of the log"; tail -60 "$WORK/daemon-a-btrfs.log"; exit 1; }

  ctx "[VAL-BTRFS-008] BOOT LOG — store selection, its root, and the armed reflink routes"
  grep -E '\[OROBOROS\] (CAS STORE SELECTED|CAS reflink routes armed|daemon up)' "$WORK/daemon-a-btrfs.log"

  BOOT_CAS="$(boot_cas_root "$WORK/daemon-a-btrfs.log")"
  ctx "[VAL-BTRFS-006a] BYTE-EQUALITY: the CAS root from the daemon's OWN BOOT LOG vs VAL-BTRFS-004's recorded plane"
  echo "boot-log casRoot : $BOOT_CAS"
  echo "m5-subvol.md CAS : $CAS"
  if [ "$BOOT_CAS" = "$CAS" ]; then echo "BYTE-EQUAL: YES"; else echo "BYTE-EQUAL: NO — FAIL"; fi
  echo "$BOOT_CAS" > "$WORK/boot-cas-root.txt"
  ctx "[VAL-BTRFS-006a] btrfs subvolume show \$BOOT_CAS + stat -c %i \$BOOT_CAS  (against the RESOLVED path, not the flags)"
  btrfs subvolume show "$BOOT_CAS" | head -8
  stat -c %i "$BOOT_CAS"

  ctx "waiting for the boot reconcile to finish so the -newermt window is not swamped by boot writes"
  wait_boot_line "$WORK/daemon-a-btrfs.log" 'Build→Couch initial reconcile' 1800 || true
  wait_boot_line "$WORK/daemon-a-btrfs.log" 'daemon up. forgeHome' 300 || true
  sleep 5
  ctx "LIVENESS RE-CHECK before the measured window (the daemon must still be up AND serving)"
  kill -0 "$DAEMON_PID" 2>/dev/null && echo "daemon pid $DAEMON_PID alive" || { echo "DAEMON DIED"; tail -40 "$WORK/daemon-a-btrfs.log"; exit 1; }
  curl -sS -m 5 -w '\nHTTP %{http_code}\n' "$BASE/api/health" || { echo "DAEMON NOT SERVING"; exit 1; }

  # ── the payloads ──────────────────────────────────────────────────────────
  ctx "payloads: three DISTINCT 1 MiB blobs (≥1 MiB — a file at or under max_inline lives in metadata and fakes identical extents)"
  mkdir -p /payload
  head -c 1048576 /dev/urandom > /payload/blob1.bin
  head -c 1048576 /dev/urandom > /payload/blob2.bin
  head -c 1048576 /dev/urandom > /payload/blob3.bin
  head -c 1048576 /dev/urandom > /payload/blob4.bin
  for f in /payload/blob*.bin; do echo "$(sha256sum "$f")  size=$(stat -c %s "$f")"; done

  # ── VAL-BTRFS-006 (b): time-bounded HTTP write ────────────────────────────
  T=$(date +%s)
  ctx "[VAL-BTRFS-006b] T recorded BEFORE the write: T=$T ($(date -u -d @$T +%Y-%m-%dT%H:%M:%SZ)); the -newermt form is used (GNU findutils on the D10 image)"
  touch "$WORK/stamp-T"
  put_blob /payload/blob1.bin blob1
  CID1="$(jf "$WORK/put-blob1.json" cid)"; PATH1="$(jf "$WORK/put-blob1.json" path)"
  HEX1="${CID1#sha256:}"
  echo "cid=$CID1 path=$PATH1"
  echo "$CID1" > "$WORK/cid1.txt"; echo "$PATH1" > "$WORK/path1.txt"

  ctx "[VAL-BTRFS-006b] SHA256-TO-CID RULE, STATED: cid == \"sha256:\" + sha256(RAW REQUEST BODY). No envelope, no re-encoding."
  echo "sha256(the bytes POSTed)          : $(sha256sum /payload/blob1.bin | cut -d' ' -f1)"
  echo "sha256(the file at the CAS path)  : $(sha256sum "$PATH1" | cut -d' ' -f1)"
  echo "cid returned by the API           : $CID1"
  echo "cid hex                           : $HEX1"

  ctx "[VAL-BTRFS-006b] find $MNT -newermt @$T -type f   (the object created by that write must be listed)"
  find "$MNT" -newermt "@$T" -type f > "$WORK/newer.txt" 2>/dev/null
  echo "files newer than T: $(wc -l < "$WORK/newer.txt")"
  echo "--- the written object among them:"
  grep -F "$HEX1" "$WORK/newer.txt" || echo "NOT FOUND — FAIL"
  echo "--- first 20 entries of the same listing:"
  head -20 "$WORK/newer.txt"
  ctx "[VAL-BTRFS-006b] findmnt -no FSTYPE,SOURCE -T <that object>  (SAME loop device as the forge home)"
  findmnt -no FSTYPE,SOURCE -T "$PATH1"
  ctx "[VAL-BTRFS-006b] read-back over HTTP: GET /api/cas/get?cid=$CID1"
  curl -sS -o /payload/readback1.bin -w 'HTTP %{http_code} bytes=%{size_download}\n' "$BASE/api/cas/get?cid=$CID1"
  echo "sha256(read-back) : $(sha256sum /payload/readback1.bin | cut -d' ' -f1)"
  cmp /payload/blob1.bin /payload/readback1.bin && echo "read-back is BYTE-IDENTICAL to what was POSTed"

  ctx "[VAL-BTRFS-008] STORE-ATTRIBUTABLE OBSERVABLE for that write (emitted INSIDE BtrfsReflinkStore)"
  grep -F "$HEX1" "$WORK/daemon-a-btrfs.log" | grep '\[BTRFS-CAS\]' || echo "OBSERVABLE MISSING — FAIL"

  wait_ack serving 120

  # ── VAL-BTRFS-008: the hosted-path MATERIALIZE measurement ────────────────
  ctx "[VAL-BTRFS-008] btrfs subvolume list $MNT  — AT MEASUREMENT TIME: NO snapshot of the CAS plane exists"
  btrfs subvolume list "$MNT"

  ctx "[VAL-BTRFS-008] MATERIALIZE through the named route POST /api/cas/materialize (D13 reflinkReorganize)"
  echo "request: {\"cid\":\"$CID1\",\"topic\":\"wiki\",\"path\":\"materialized/m5-host-blob1.bin\"}"
  curl -sS -X POST -H 'Content-Type: application/json' \
      -d "{\"cid\":\"$CID1\",\"topic\":\"wiki\",\"path\":\"materialized/m5-host-blob1.bin\"}" \
      -o "$WORK/materialize.json" -w 'HTTP %{http_code}\n' "$BASE/api/cas/materialize"
  cat "$WORK/materialize.json"; echo
  if ! grep -q '"ok":true' "$WORK/materialize.json"; then
    ctx "[VAL-BTRFS-008] cross-plane reflink refused; retrying the SAME primitive with topic=cas (same subvolume as the source)"
    curl -sS -X POST -H 'Content-Type: application/json' \
        -d "{\"cid\":\"$CID1\",\"topic\":\"cas\",\"path\":\"materialized/m5-host-blob1.bin\"}" \
        -o "$WORK/materialize.json" -w 'HTTP %{http_code}\n' "$BASE/api/cas/materialize"
    cat "$WORK/materialize.json"; echo
  fi
  TGT1="$(jf "$WORK/materialize.json" target)"
  echo "materialized target: $TGT1"
  echo "$TGT1" > "$WORK/target1.txt"
  grep '\[BTRFS-CAS\] reflink-reorganize' "$WORK/daemon-a-btrfs.log" | tail -3

  ctx "[VAL-BTRFS-008] the NEGATIVE CONTROL of this assertion: cp --reflink=never over the SAME bytes"
  cp --reflink=never "$PATH1" "$MNT/m5-host-control-never.bin"
  echo "cp --reflink=never $PATH1 $MNT/m5-host-control-never.bin"

  sync_fs
  ctx "[VAL-BTRFS-008] EXTENT MEASUREMENT — both sharing paths NAMED VERBATIM"
  echo "SOURCE  (CAS blob)   : $PATH1"
  echo "TARGET  (materialized): $TGT1"
  echo "CONTROL (--reflink=never): $MNT/m5-host-control-never.bin"
  measure "$PATH1" "SOURCE"
  measure "$TGT1" "TARGET"
  measure "$MNT/m5-host-control-never.bin" "CONTROL"

  ctx "[VAL-BTRFS-008] production caller: grep -rn BtrfsReflinkStore /repo/src (contrast with the pre-mission grep in btrfs-readiness.md, which found none)"
  grep -rn "BtrfsReflinkStore" /repo/src --include=*.kt | grep -v "^/repo/src/commonTest\|^/repo/src/jvmTest" | sed 's|^/repo/||'

  # ── VAL-BTRFS-006 (c): the read-only NEGATIVE CONTROL ─────────────────────
  ctx "[VAL-BTRFS-006c] NEGATIVE CONTROL, PRIMARY FORM: btrfs property set $CAS ro true"
  echo "(the btrfs-native form is used, NOT mount -o remount,ro: the daemon holds the couch dbs"
  echo " and the WAL open on this mount — decision D8 — so a remount would be refused EBUSY."
  echo " For the record, the remount is attempted below so the EBUSY is on the page.)"
  btrfs property set "$CAS" ro true
  btrfs property get "$CAS" ro
  ctx "[VAL-BTRFS-006c] for the record: mount -o remount,ro $MNT  (the ALTERNATIVE form, attempted; its refusal is why the property is primary)"
  mount -o remount,ro "$MNT" 2>&1 || true
  ctx "[VAL-BTRFS-006c] the SAME API write repeated with the CAS plane read-only — it must FAIL"
  echo "(the payload is a FRESH 1 MiB blob, not a byte-repeat of blob1: an identical payload is"
  echo " answered from the already-published cid without touching the filesystem — a dedup hit,"
  echo " not a test of writability. The OPERATION repeated is POST /api/cas/put.)"
  put_blob /payload/blob2.bin blob2-under-ro
  ctx "[VAL-BTRFS-006c] the store's own refusal in the daemon log"
  tail -5 "$WORK/daemon-a-btrfs.log" | grep -i "read-only\|readonly" || tail -5 "$WORK/daemon-a-btrfs.log"
  ctx "[VAL-BTRFS-006c] blob2 must NOT be on the plane"
  ls -l "$CAS/sha256/$(sha256sum /payload/blob2.bin | cut -c1-2)/$(sha256sum /payload/blob2.bin | cut -c3-64)" 2>&1 || true

  ctx "[VAL-BTRFS-006c] RESTORE: btrfs property set $CAS ro false"
  btrfs property set "$CAS" ro false
  btrfs property get "$CAS" ro
  ctx "[VAL-BTRFS-006c] the daemon is STILL SERVING on :$PORT after rw was restored"
  curl -sS -m 5 -w '\nHTTP %{http_code}\n' "$BASE/api/health"
  ctx "[VAL-BTRFS-006c] and the same write now SUCCEEDS"
  put_blob /payload/blob2.bin blob2

  # ── VAL-BTRFS-009: read-only snapshot of the LIVE CAS plane ───────────────
  SNAP="$MNT/cas-snap-m5-host"
  ctx "[VAL-BTRFS-009] snapshot SOURCE PATH, verbatim: $BOOT_CAS  (the CAS root from the daemon's own boot log)"
  ctx "[VAL-BTRFS-009] btrfs subvolume snapshot -r $BOOT_CAS $SNAP"
  btrfs subvolume snapshot -r "$BOOT_CAS" "$SNAP"
  ctx "[VAL-BTRFS-009] btrfs subvolume list $MNT  (the snapshot now exists — this is AFTER 008's measurement)"
  btrfs subvolume list "$MNT"
  ctx "[VAL-BTRFS-009] btrfs property get $SNAP ro"
  btrfs property get "$SNAP" ro
  ctx "[VAL-BTRFS-009] REJECTED WRITE into the snapshot (read-only is proven by the refusal, never by the -r flag)"
  touch "$SNAP/should-not-exist" 2>&1 || true
  echo "---"
  ( : > "$SNAP/should-not-exist" ) 2>&1 || true

  ctx "[VAL-BTRFS-009] the snapshot holds the NAMED blob written over HTTP: $CID1"
  ls -l "$SNAP/sha256/${HEX1:0:2}/${HEX1:2}"

  ctx "[VAL-BTRFS-009] MUTATE the live plane — DELETE the named blob $CID1 (written through the daemon's HTTP surface, VAL-BTRFS-008's record)"
  rm -f "$PATH1"
  echo "rm -f $PATH1"
  ctx "[VAL-BTRFS-009] the cid is ABSENT from the live plane"
  ls -l "$PATH1" 2>&1 || echo "absent from the live plane (as intended)"
  curl -sS -m 5 -o /dev/null -w 'GET /api/cas/get?cid=%{url_effective} -> HTTP %{http_code}\n' "$BASE/api/cas/get?cid=$CID1" || true

  ctx "[VAL-BTRFS-009] ADD new blobs through the daemon (blob3, blob4)"
  put_blob /payload/blob3.bin blob3
  put_blob /payload/blob4.bin blob4
  CID3="$(jf "$WORK/put-blob3.json" cid)"
  CID4="$(jf "$WORK/put-blob4.json" cid)"
  echo "added: $CID3  $CID4"

  ctx "[VAL-BTRFS-009] read the DELETED cid back OUT OF THE SNAPSHOT and verify sha256 == cid"
  sha256sum "$SNAP/sha256/${HEX1:0:2}/${HEX1:2}"
  echo "cid hex : $HEX1"
  SNAPHEX="$(sha256sum "$SNAP/sha256/${HEX1:0:2}/${HEX1:2}" | cut -d' ' -f1)"
  if [ "$SNAPHEX" = "$HEX1" ]; then echo "EARLY MEMORY SURVIVES: sha256(snapshot bytes) == cid"; else echo "MISMATCH — FAIL"; fi

  sync_fs
  ctx "[VAL-BTRFS-009] space BEFORE the snapshot delete"
  btrfs filesystem df "$MNT"; df -h "$MNT"
  ctx "[VAL-BTRFS-009] btrfs subvolume delete $SNAP"
  btrfs subvolume delete "$SNAP"
  sync_fs
  ctx "[VAL-BTRFS-009] space AFTER the snapshot delete"
  btrfs filesystem df "$MNT"; df -h "$MNT"
  ctx "[VAL-BTRFS-009] the live plane and its data are intact: blob3 still reads back with sha256 == cid"
  curl -sS -o /payload/rb3.bin -w 'HTTP %{http_code} bytes=%{size_download}\n' "$BASE/api/cas/get?cid=$CID3"
  echo "sha256(read-back) : $(sha256sum /payload/rb3.bin | cut -d' ' -f1)"
  echo "cid3              : $CID3"
  btrfs subvolume list "$MNT"

  # ── VAL-BTRFS-007: the MANIFEST is the FIRST act of the durability window ──
  sync_fs
  ctx "[VAL-BTRFS-007] MANIFEST — captured AFTER VAL-BTRFS-009's mutation, as the FIRST act of the durability window"
  echo "command: find $CAS/sha256 -type f -regextype posix-extended -regex '.*/sha256/[0-9a-f]{2}/[0-9a-f]{62}' | sed -E 's|.*/sha256/(..)/(.*)|\\1\\2|' | sort"
  blob_manifest "$WORK/manifest.txt"
  MCOUNT=$(wc -l < "$WORK/manifest.txt"); MBYTES=$(blob_bytes "$WORK/manifest.txt")
  echo "manifest captured at $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "count       : $MCOUNT"
  echo "total bytes : $MBYTES"
  echo "$MCOUNT" > "$WORK/manifest-count.txt"; echo "$MBYTES" > "$WORK/manifest-bytes.txt"
  echo "--- the sorted cid list:"
  cat "$WORK/manifest.txt"
  ctx "[VAL-BTRFS-007] no .tmp residue under the CAS plane"
  find "$CAS" -name '*.tmp' | head -5; echo "(.tmp count: $(find "$CAS" -name '*.tmp' | wc -l))"

  ctx "[VAL-BTRFS-007] PRE-RESTART HTTP read of a record written before the restart"
  curl -sS -o /payload/pre3.bin -w 'HTTP %{http_code} bytes=%{size_download}\n' "$BASE/api/cas/get?cid=$CID3"
  sha256sum /payload/pre3.bin

  # ── VAL-BTRFS-007 (1): daemon restart against the SAME volume ─────────────
  stop_daemon
  start_daemon a-restart "$FORGE" btrfs
  wait_health 300 || { tail -40 "$WORK/daemon-a-restart.log"; exit 1; }
  ctx "[VAL-BTRFS-007 step 1] POST-RESTART HTTP read of the SAME record"
  curl -sS -o /payload/post3.bin -w 'HTTP %{http_code} bytes=%{size_download}\n' "$BASE/api/cas/get?cid=$CID3"
  sha256sum /payload/post3.bin
  cmp /payload/pre3.bin /payload/post3.bin && echo "identical bytes across the daemon restart"
  grep -m1 '\[OROBOROS\] CAS STORE SELECTED' "$WORK/daemon-a-restart.log"

  sync_fs
  ctx "[VAL-BTRFS-007 step 1] MONOTONIC-SUBSET check against the manifest"
  blob_manifest "$WORK/blobs-after-restart1.txt"
  echo "MISSING (manifest cids not present) — must be EMPTY:"
  comm -23 "$WORK/manifest.txt" "$WORK/blobs-after-restart1.txt"
  echo "ADDED (cids not in the manifest) — enumerated IN FULL:"
  comm -13 "$WORK/manifest.txt" "$WORK/blobs-after-restart1.txt" | tee "$WORK/added-restart1.txt"
  echo "manifest count=$MCOUNT  present-now=$(wc -l < "$WORK/blobs-after-restart1.txt")  added=$(wc -l < "$WORK/added-restart1.txt")"
  echo "manifest-only total bytes now: $(blob_bytes "$WORK/manifest.txt")  (captured: $MBYTES)"

  ctx "[VAL-BTRFS-007 step 1] re-verify EVERY manifest cid (bytes still hash to the cid) — counted, not sampled"
  bad=0; n=0
  while read -r c; do
    n=$((n+1))
    h="$(sha256sum "$CAS/sha256/${c:0:2}/${c:2}" 2>/dev/null | cut -d' ' -f1)"
    [ "$h" = "$c" ] || { echo "MISMATCH $c -> ${h:-<missing>}"; bad=$((bad+1)); }
  done < "$WORK/manifest.txt"
  echo "re-verified $n/$MCOUNT manifest cids; mismatches=$bad"

  # ── VAL-BTRFS-007 (2): unmount + detach, with the daemon DOWN ─────────────
  stop_daemon
  ctx "[VAL-BTRFS-007 step 2] superblock generation BEFORE unmount"
  btrfs inspect-internal dump-super -f "$DEV" | grep -E '^(fsid|label|generation|uuid_tree_generation|dev_item.uuid|dev_item.fsid)' | head -8
  sync_fs
  ctx "[VAL-BTRFS-007 step 2] umount $MNT"
  umount "$MNT" || { fuser -vm "$MNT" 2>&1 | head; umount -l "$MNT"; }
  findmnt -no TARGET "$MNT" || echo "not mounted (as intended)"
  ctx "[VAL-BTRFS-007 step 2] losetup -d $DEV"
  losetup -d "$DEV"
  losetup -j "$IMG" || echo "no loop device bound to $IMG (detached)"
  ctx "[VAL-BTRFS-007] COMMAND LOG CHECK: no mkfs invocation appears anywhere in this window"
  echo "(this script contains no mkfs; bin/trikeshed-btrfs create is NOT invoked by this lane)"
  wait_ack unmounted 120
  ctx "phase-a COMPLETE — the container may now be destroyed with docker rm -f"
  ;;

# ═══════════════════════════════════════════════════════════════════════════════
phase-b)
  ensure_tools
  ctx "[VAL-BTRFS-007 step 2b] FRESH container re-attaching the SAME host image file"
  attach_and_mount
  DEV="$(cat "$WORK/dev")"

  ctx "[VAL-BTRFS-007] btrfs filesystem show $MNT   (uuid must equal VAL-BTRFS-001's recorded value 1b7032f7-27a1-4189-a58f-319d7dddaa62)"
  btrfs filesystem show "$MNT"
  ctx "[VAL-BTRFS-007] losetup -j $IMG   (the loop device is bound to that exact backing file)"
  losetup -j "$IMG"
  ctx "[VAL-BTRFS-007] superblock generation AFTER remount (must be strictly INCREASING, never reset)"
  btrfs inspect-internal dump-super -f "$DEV" | grep -E '^(fsid|label|generation|uuid_tree_generation|dev_item.uuid|dev_item.fsid)' | head -8

  sync_fs
  MCOUNT=$(cat "$WORK/manifest-count.txt"); MBYTES=$(cat "$WORK/manifest-bytes.txt")
  ctx "[VAL-BTRFS-007 step 2b] MONOTONIC-SUBSET check against the manifest, after the fresh-container remount"
  blob_manifest "$WORK/blobs-after-remount.txt"
  echo "MISSING (manifest cids not present) — must be EMPTY:"
  comm -23 "$WORK/manifest.txt" "$WORK/blobs-after-remount.txt"
  echo "ADDED (cids not in the manifest) — enumerated IN FULL:"
  comm -13 "$WORK/manifest.txt" "$WORK/blobs-after-remount.txt" | tee "$WORK/added-remount.txt"
  echo "manifest count=$MCOUNT  present-now=$(wc -l < "$WORK/blobs-after-remount.txt")  added=$(wc -l < "$WORK/added-remount.txt")"
  echo "manifest-only total bytes: $(blob_bytes "$WORK/manifest.txt")  (captured: $MBYTES)"
  bad=0; n=0
  while read -r c; do
    n=$((n+1))
    h="$(sha256sum "$CAS/sha256/${c:0:2}/${c:2}" 2>/dev/null | cut -d' ' -f1)"
    [ "$h" = "$c" ] || { echo "MISMATCH $c -> ${h:-<missing>}"; bad=$((bad+1)); }
  done < "$WORK/manifest.txt"
  echo "re-verified $n/$MCOUNT manifest cids; mismatches=$bad"

  # ── VAL-BTRFS-007 (3): the daemon serves the same records again ───────────
  start_daemon b-fresh "$FORGE" btrfs
  wait_health 300 || { tail -40 "$WORK/daemon-b-fresh.log"; exit 1; }
  grep -E '\[OROBOROS\] (CAS STORE SELECTED|CAS reflink routes armed)' "$WORK/daemon-b-fresh.log"
  CID3="$(jf "$WORK/put-blob3.json" cid)"
  ctx "[VAL-BTRFS-007 step 3] FINAL post-remount HTTP read of the SAME record ($CID3)"
  curl -sS -o /payload3.bin -w 'HTTP %{http_code} bytes=%{size_download}\n' "$BASE/api/cas/get?cid=$CID3"
  sha256sum /payload3.bin; echo "cid3 : $CID3"

  ctx "[VAL-BTRFS-007] the materialized target survived the cycle too"
  ls -l "$(cat "$WORK/target1.txt")"
  sha256sum "$(cat "$WORK/target1.txt")"

  wait_ack serving-b 120

  stop_daemon
  sync_fs
  ctx "[VAL-BTRFS-007] FINAL post-remount SCRUB: btrfs scrub start -B $MNT"
  btrfs scrub start -B "$MNT"
  ctx "[VAL-BTRFS-007] btrfs scrub status -d $MNT  (zero csum/read/verify errors per devid)"
  btrfs scrub status -d "$MNT"
  sync_fs
  ctx "df -h $MNT — AFTER (capacity watch; ≥30% free required)"
  df -h "$MNT"
  ctx "[VAL-BTRFS-007] final blob set, and the manifest comparison one last time"
  blob_manifest "$WORK/blobs-final.txt"
  echo "MISSING — must be EMPTY:"; comm -23 "$WORK/manifest.txt" "$WORK/blobs-final.txt"
  echo "final count: $(wc -l < "$WORK/blobs-final.txt")"
  ctx "leaving the volume MOUNTED and the loop device attached for downstream lanes"
  findmnt -no FSTYPE,SOURCE,TARGET -T "$MNT"
  ;;

# ═══════════════════════════════════════════════════════════════════════════════
control)
  ensure_tools
  ctx "[VAL-BTRFS-008 CONTROL] the SAME daemon build on FileCasStore — no btrfs volume is mounted at all"
  echo "forge home is /root/forge-control on the CONTAINER OVERLAY (the observable must be ABSENT here)"
  findmnt -no FSTYPE,SOURCE -T /root || true
  mkdir -p /root/forge-control
  start_daemon control /root/forge-control file
  wait_health 300 || { tail -40 "$WORK/daemon-control.log"; exit 1; }
  ctx "[CONTROL] boot log — the selection line names FileCasStore"
  grep -E '\[OROBOROS\] (CAS STORE SELECTED|CAS reflink routes armed)' "$WORK/daemon-control.log" || true
  ctx "[CONTROL] the D13 materialize route does NOT exist on a FileCasStore boot"
  head -c 1048576 /dev/urandom > /payload-control.bin
  curl -sS -X POST --data-binary @/payload-control.bin -w '\nHTTP %{http_code}\n' "$BASE/api/cas/put"
  curl -sS -X POST -d '{"cid":"sha256:0000000000000000000000000000000000000000000000000000000000000000","topic":"wiki","path":"x"}' \
       -w '\nHTTP %{http_code}\n' "$BASE/api/cas/materialize"
  ctx "[CONTROL] a write that DOES flow through the store on both configurations: PUT a couch doc"
  curl -sS -X PUT -H 'Content-Type: application/json' -d '{"lane":"m5-host-control","n":1}' \
       -w '\nHTTP %{http_code}\n' "$BASE/trikeshed/m5-host-control-doc"
  curl -sS -w '\nHTTP %{http_code}\n' "$BASE/trikeshed/m5-host-control-doc"
  ctx "[CONTROL] blobs the FileCasStore control wrote (its own CAS root, on the overlay)"
  find /root/forge-control/cas/sha256 -type f -regextype posix-extended -regex '.*/sha256/[0-9a-f]{2}/[0-9a-f]{62}' | wc -l
  ctx "[CONTROL] THE DISCRIMINATOR: the store-attributable observable is ABSENT from this run's log"
  echo "grep -c '\\[BTRFS-CAS\\]' $WORK/daemon-control.log  ->  $(grep -c '\[BTRFS-CAS\]' "$WORK/daemon-control.log")"
  grep '\[BTRFS-CAS\]' "$WORK/daemon-control.log" | head -5 || echo "(no [BTRFS-CAS] lines — as required)"
  stop_daemon
  ;;

# ═══════════════════════════════════════════════════════════════════════════════
boot-refusal)
  ensure_tools
  ctx "[VAL-BTRFS-008] BOOT REFUSAL: TRIKESHED_CAS=btrfs with a forge home that is NOT on btrfs"
  mkdir -p /root/forge-notbtrfs
  findmnt -no FSTYPE,SOURCE -T /root/forge-notbtrfs
  TRIKESHED_CAS=btrfs TRIKESHED_NO_KANBAN_MODULE=1 \
    java -cp "$CP" "$MAIN" /root/forge-notbtrfs /repo --kanban-port "$PORT" > "$WORK/daemon-refusal.log" 2>&1
  echo "exit code: $?"
  ctx "[VAL-BTRFS-008] the refusal, verbatim (LOUD, and no silent fallback to FileCasStore)"
  grep -E 'CAS STORE REFUSED|BOOT ABORTED|CAS STORE SELECTED' "$WORK/daemon-refusal.log" || tail -20 "$WORK/daemon-refusal.log"
  ;;

*) echo "unknown subcommand: $CMD" >&2; exit 2 ;;
esac
