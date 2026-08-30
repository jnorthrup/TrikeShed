#!/usr/bin/env bash
# bin/m5-store-run.sh — lane w-m5-store driver for VAL-BTRFS-002 and VAL-BTRFS-003.
#
# Runs the JVM harness (borg.trikeshed.btrfs.BtrfsStoreHarnessKt) against the live
# file-backed btrfs volume created by bin/trikeshed-btrfs, then takes the physical
# reflink measurements. Same shape as bin/trikeshed-btrfs: on Darwin it prints the
# docker run command line VERBATIM and re-enters ITSELF inside the decision-D10
# container by piping its own file on stdin (single-FILE bind mounts fail on this
# virtiofs setup).
#
# Subcommands:
#   live       mount the volume; run the harness (populate + reflinkCopy); take the
#              VAL-BTRFS-003 extent measurements; lane-end hygiene.
#   negative   do NOT mount anything; mkdir the CAS root on the container overlay and
#              run the SAME harness — it must refuse loudly and create no blobs.
#
# Bind-mount fence: exactly two mounts, ~/.local/trikeshed-btrfs (rw, the image
# directory) and the repo READ-ONLY. $HOME, ~/.local, ~/.local/forge and ~/.hermes are
# never mounted.

set -uo pipefail

IMG_DIR_HOST="$HOME/.local/trikeshed-btrfs"
REPO_HOST="/Users/jim/work/TrikeShed"
IMG_NAME="trikeshed-store.img"
MNT="/mnt/trikeshed"
FORGE_HOME="$MNT/forge"
CAS="$FORGE_HOME/cas"
SCRATCH="$MNT/scratch-m5-reflink"          # the NAMED SCRATCH PLANE (plain directory)
IMG="/img/$IMG_NAME"
DOCKER_IMAGE="eclipse-temurin:25-jdk"
CP="/repo/build/m5-store/classes:/repo/build/live/classes:/repo/build/staging/lib/*"
MAIN="borg.trikeshed.btrfs.BtrfsStoreHarnessKt"

CMD="${1:-live}"

if [ "$(uname -s)" = "Darwin" ]; then
  SELF="${BASH_SOURCE[0]}"
  DOCKER_ARGS=(docker run --rm -i --privileged
               -v "$IMG_DIR_HOST:/img"
               -v "$REPO_HOST:/repo:ro"
               "$DOCKER_IMAGE" bash -s -- "$CMD")
  echo "### macOS shell: docker run command line, VERBATIM (bind mounts: ONLY $IMG_DIR_HOST rw and $REPO_HOST ro)"
  printf '%q ' "${DOCKER_ARGS[@]}"; printf '< %q\n' "$SELF"
  exec "${DOCKER_ARGS[@]}" < "$SELF"
fi

ctx() { echo; echo "### axis-2 container: $*"; }

ensure_tools() {
  if ! command -v btrfs >/dev/null 2>&1; then
    ctx "apt-get install btrfs-progs btrfs-compsize e2fsprogs util-linux (decision D10 image)"
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
      btrfs-progs btrfs-compsize e2fsprogs util-linux >/dev/null
  fi
  ctx "TOOL PROVENANCE (both tools are version-dependent; coreutils 9.7 is why the control needs --reflink=never)"
  btrfs --version
  mkfs.btrfs --version | head -1
  filefrag -V 2>&1 | head -1
  cp --version | head -1
  compsize --help 2>&1 | head -1 || true
  losetup --version | head -1
  java -version 2>&1 | head -1
  uname -m; uname -r
}

sync_fs() { ctx "sync; btrfs filesystem sync $MNT   (MANDATORY before every extent/space reading — decision D12)"; sync; btrfs filesystem sync "$MNT"; }

case "$CMD" in

live)
  ensure_tools

  ctx "losetup -j $IMG  (loop device located BY BACKING FILE, never a hardcoded /dev/loopN)"
  losetup -j "$IMG" || true
  DEV="$(losetup -j "$IMG" 2>/dev/null | cut -d: -f1 | head -1)"
  if [ -z "$DEV" ]; then DEV="$(losetup -f --show "$IMG")"; echo "attached $DEV -> $IMG"; else echo "reusing $DEV"; fi

  ctx "mount $DEV $MNT   (mount only; this lane NEVER runs mkfs)"
  mkdir -p "$MNT"
  findmnt -no TARGET "$MNT" >/dev/null 2>&1 || mount "$DEV" "$MNT"
  findmnt "$MNT"

  ctx "btrfs subvolume list $MNT   (the planes VAL-BTRFS-001 created)"
  btrfs subvolume list "$MNT"
  ctx "findmnt -no FSTYPE,SOURCE -T $CAS   (the CAS plane is on btrfs)"
  findmnt -no FSTYPE,SOURCE -T "$CAS"
  ctx "stat -f -c %T $CAS"
  stat -f -c %T "$CAS"
  ctx "RESET — this lane owns the CAS tree and the scratch plane; both start EMPTY so the record is one clean population (the planes themselves are never touched: no subvolume is created or deleted here)"
  rm -rf "${CAS:?}/sha256" "${SCRATCH:?}"
  ctx "CAS tree BEFORE the harness (must be 0 files — the empty plane VAL-BTRFS-001 created)"
  find "$CAS" -type f | wc -l
  find "$CAS" -mindepth 1 | head -20 || true
  ctx "btrfs subvolume list $MNT after the reset (both planes still present, untouched)"
  btrfs subvolume list "$MNT"

  # ── the NAMED SCRATCH PLANE — created by THIS lane, plain mkdir -p, not a subvolume ──
  ctx "[VAL-BTRFS-003] scratch plane: plain 'mkdir -p $SCRATCH' — a PLAIN DIRECTORY on the same btrfs mount, NOT a subvolume, NOT inside the CAS tree"
  mkdir -p "$SCRATCH"
  echo "-- btrfs subvolume show $SCRATCH (MUST FAIL: it is a plain directory)"
  if btrfs subvolume show "$SCRATCH" >/dev/null 2>&1; then echo "SUBVOLUME(!) — contract violation"; exit 9; else echo "not a subvolume (btrfs subvolume show fails) — correct"; fi
  echo "-- stat -c %i $SCRATCH   (a subvolume root would be inode 256)"; stat -c %i "$SCRATCH"
  echo "-- btrfs subvolume list $MNT | grep scratch (must be empty)"; btrfs subvolume list "$MNT" | grep -i scratch || echo "(absent from the subvolume list — correct)"
  ctx "[VAL-BTRFS-003] findmnt -no FSTYPE,SOURCE -T $SCRATCH   (SAME btrfs device as the CAS plane — cp --reflink=always is EXDEV across filesystems)"
  findmnt -no FSTYPE,SOURCE -T "$SCRATCH"
  findmnt -no FSTYPE,SOURCE -T "$CAS"

  # ── the JVM harness ────────────────────────────────────────────────────────
  ctx "[VAL-BTRFS-002] JVM HARNESS — the writes below come from TrikeShed store code, not from shell cp"
  echo "java -cp $CP $MAIN --cas-root $CAS --blobs 50 --scratch $SCRATCH"
  java -cp "$CP" "$MAIN" --cas-root "$CAS" --blobs 50 --scratch "$SCRATCH"
  HARNESS_RC=$?
  ctx "harness exit code = $HARNESS_RC"
  [ "$HARNESS_RC" = "0" ] || exit "$HARNESS_RC"

  # ── VAL-BTRFS-002 filesystem-side proof ────────────────────────────────────
  sync_fs
  ctx "[VAL-BTRFS-002] find $MNT -type f | head -20"
  find "$MNT" -type f | head -20
  ctx "[VAL-BTRFS-002] directory-fanout count: shard directories under $CAS/sha256"
  find "$CAS/sha256" -mindepth 1 -maxdepth 1 -type d | wc -l
  ctx "[VAL-BTRFS-002] blob set per the COUNTING RULE <casRoot>/sha256/<2hex>/<62hex>"
  find "$CAS/sha256" -mindepth 2 -maxdepth 2 -type f -regextype posix-extended -regex '.*/sha256/[0-9a-f]{2}/[0-9a-f]{62}' | wc -l
  ctx "[VAL-BTRFS-002] all regular files under $CAS (must equal the blob set — nothing else is a blob)"
  find "$CAS" -type f | wc -l
  ctx "[VAL-BTRFS-002] .tmp residue check: find $CAS \\( -name '.*.tmp' -o -name '*.tmp' \\)"
  find "$CAS" \( -name '.*.tmp' -o -name '*.tmp' \) -print | tee /tmp/tmp-residue.txt
  echo "residue count = $(wc -l < /tmp/tmp-residue.txt)"
  ctx "[VAL-BTRFS-002] blob sizes written (bytes, sorted)"
  find "$CAS" -type f -printf '%s\n' | sort -n | tr '\n' ' '; echo
  ctx "[VAL-BTRFS-002] independent sha256 re-verification of 3 blobs (basename == sha256 minus the 2-char shard)"
  for f in $(find "$CAS/sha256" -mindepth 2 -type f | sort | head -2) $(find "$CAS/sha256" -mindepth 2 -type f -size 4194304c | head -1); do
    H="$(sha256sum "$f" | cut -d' ' -f1)"
    SHARD="$(basename "$(dirname "$f")")"; NAME="$(basename "$f")"
    echo "$f  sha256=$H  shard+name=$SHARD$NAME  match=$([ "$H" = "$SHARD$NAME" ] && echo yes || echo NO)"
  done

  # ── VAL-BTRFS-003 physical measurement ─────────────────────────────────────
  SRC="$(find "$CAS/sha256" -mindepth 2 -type f -size 4194304c | head -1)"
  CLONE="$SCRATCH/clone-4MiB.bin"
  DIVERGE="$SCRATCH/diverge-4MiB.bin"
  CONTROL="$SCRATCH/control-reflink-never.bin"
  ctx "[VAL-BTRFS-003] paths under measurement"
  echo "SOURCE  (CAS blob, written by store.put)          = $SRC   ($(stat -c %s "$SRC") bytes)"
  echo "CLONE   (store.reflinkCopy -> scratch plane)      = $CLONE ($(stat -c %s "$CLONE") bytes)"
  echo "DIVERGE (store.reflinkCopy -> scratch plane, dd)  = $DIVERGE ($(stat -c %s "$DIVERGE") bytes)"
  echo "CONTROL (cp --reflink=never -> scratch plane)     = $CONTROL (not yet created)"

  sync_fs
  ctx "[VAL-BTRFS-003] (1) filefrag -v SOURCE — 'shared' must be PRESENT, 'inline' ABSENT"
  filefrag -v "$SRC"
  ctx "[VAL-BTRFS-003] (1) filefrag -v CLONE — same physical range as SOURCE"
  filefrag -v "$CLONE"
  ctx "[VAL-BTRFS-003] (2) btrfs filesystem du -s SOURCE CLONE"
  btrfs filesystem du -s "$SRC" "$CLONE"
  ctx "[VAL-BTRFS-003] (2) compsize SOURCE CLONE"
  compsize "$SRC" "$CLONE" || true

  ctx "[VAL-BTRFS-003] (3) NEGATIVE CONTROL — btrfs filesystem df BEFORE the cp --reflink=never"
  btrfs filesystem df "$MNT"
  ctx "[VAL-BTRFS-003] (3) the control command line, showing the flag: cp --reflink=never $SRC $CONTROL"
  cp --reflink=never "$SRC" "$CONTROL"
  sync_fs
  ctx "[VAL-BTRFS-003] (3) btrfs filesystem df AFTER the cp --reflink=never (Data used must rise by ~4 MiB)"
  btrfs filesystem df "$MNT"
  ctx "[VAL-BTRFS-003] (3) filefrag -v CONTROL — DIFFERENT physical range, NO 'shared' flag"
  filefrag -v "$CONTROL"
  ctx "[VAL-BTRFS-003] (3) btrfs filesystem du -s CONTROL (Exclusive must equal the file size)"
  btrfs filesystem du -s "$CONTROL"
  ctx "[VAL-BTRFS-003] (3) sha256 of the control == sha256 of the source (same bytes, different extents)"
  sha256sum "$SRC" "$CONTROL"

  ctx "[VAL-BTRFS-003] (4) COW DIVERGENCE — btrfs filesystem du -s DIVERGE before the dd"
  btrfs filesystem du -s "$DIVERGE"
  ctx "[VAL-BTRFS-003] (4) dd bs=64K count=1 conv=notrunc into $DIVERGE"
  dd if=/dev/urandom of="$DIVERGE" bs=64K count=1 conv=notrunc
  ctx "[VAL-BTRFS-003] (4) btrfs filesystem du -s DIVERGE — WITHOUT the sync (this reading is NOT evidence; it is the reason the sync rule exists)"
  btrfs filesystem du -s "$DIVERGE"
  sync_fs
  ctx "[VAL-BTRFS-003] (4) btrfs filesystem du -s DIVERGE SOURCE CLONE — AFTER the sync"
  btrfs filesystem du -s "$DIVERGE" "$SRC" "$CLONE"
  ctx "[VAL-BTRFS-003] (4) filefrag -v DIVERGE after the dd"
  filefrag -v "$DIVERGE"
  ctx "[VAL-BTRFS-003] (4) the untouched source still hashes to its cid; the diverged copy no longer does"
  sha256sum "$SRC" "$CLONE" "$DIVERGE"
  echo "-- source basename check:"
  echo "$(sha256sum "$SRC" | cut -d' ' -f1)  vs  $(basename "$(dirname "$SRC")")$(basename "$SRC")"

  ctx "[VAL-BTRFS-002] LANE END — scratch plane contents (all outside the CAS tree)"
  ls -la "$SCRATCH"
  ctx "[VAL-BTRFS-002] LANE END — CAS tree is untouched by the 003 measurements"
  find "$CAS" -type f | wc -l
  find "$CAS" \( -name '.*.tmp' -o -name '*.tmp' \) -print | wc -l
  ctx "[VAL-BTRFS-002] LANE END — btrfs subvolume list $MNT (no third plane was created)"
  btrfs subvolume list "$MNT"

  ctx "teardown: sync, umount, detach BY BACKING FILE"
  sync; btrfs filesystem sync "$MNT"; umount "$MNT" && echo "unmounted $MNT"
  for d in $(losetup -j "$IMG" 2>/dev/null | cut -d: -f1); do losetup -d "$d" && echo "detached $d"; done
  losetup -a || true
  echo "### LIVE RUN COMPLETE"
  ;;

negative)
  ensure_tools
  ctx "[VAL-BTRFS-002 NEGATIVE RUN] the volume is NOT mounted — no losetup, no mount, in this container"
  losetup -a || echo "(no loop devices at all)"
  findmnt "$MNT" || echo "(nothing mounted at $MNT)"
  ctx "[NEGATIVE] mkdir -p $CAS on whatever filesystem is underneath (the container overlay)"
  mkdir -p "$CAS"
  ctx "[NEGATIVE] findmnt -no FSTYPE,SOURCE -T $CAS   (NOT btrfs)"
  findmnt -no FSTYPE,SOURCE -T "$CAS"
  ctx "[NEGATIVE] stat -f -c %T $CAS"
  stat -f -c %T "$CAS"
  ctx "[NEGATIVE] the SAME harness, the SAME command line shape"
  echo "java -cp $CP $MAIN --cas-root $CAS --mode negative --blobs 50"
  java -cp "$CP" "$MAIN" --cas-root "$CAS" --mode negative --blobs 50
  RC=$?
  ctx "[NEGATIVE] harness exit code = $RC  (0 would be the Fail clause: the store manufactured a CAS root on the wrong filesystem)"
  ctx "[NEGATIVE] find $CAS -type f   (the run must have created NO blobs)"
  find "$CAS" -type f | wc -l
  find "$CAS" -mindepth 1 | wc -l
  ctx "[NEGATIVE] find $CAS/sha256 (the sharded tree must not exist)"
  ls -la "$CAS"
  test -e "$CAS/sha256" && echo "sha256/ EXISTS — FAIL" || echo "no sha256/ directory — the guard fired before any mkdirs"
  echo "### NEGATIVE RUN COMPLETE (harness rc=$RC)"
  [ "$RC" = "3" ] || exit 1
  ;;

*) echo "unknown subcommand: $CMD" >&2; exit 2 ;;
esac
