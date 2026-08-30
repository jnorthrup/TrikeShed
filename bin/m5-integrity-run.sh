#!/usr/bin/env bash
# bin/m5-integrity-run.sh — lane w-m5-integrity driver for VAL-BTRFS-005.
#
# Runs a FULL btrfs scrub over the live file-backed volume, reads the per-devid error
# counters, reconciles `Total to scrub` against PHYSICAL usage (`btrfs filesystem df`)
# and against VAL-BTRFS-001's recorded EMPTY-VOLUME floor, scopes coverage to the store
# with `btrfs filesystem du -s <casRoot>`, and then re-verifies EVERY stored cid through
# TrikeShed's own store code (borg.trikeshed.btrfs.BtrfsIntegrityHarnessKt).
#
# READ-ONLY with respect to the CAS tree: this lane never writes a blob, never runs
# mkfs, never creates or deletes a subvolume, and never touches VAL-BTRFS-003's scratch
# plane. It mounts, measures, verifies, unmounts.
#
# Same re-entry shape as bin/m5-store-run.sh: on Darwin it prints the docker run command
# line VERBATIM and re-enters ITSELF inside the decision-D10 container by piping its own
# file on stdin (single-FILE bind mounts fail on this virtiofs setup).
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
SCRATCH="$MNT/scratch-m5-reflink"          # VAL-BTRFS-003's named scratch plane
IMG="/img/$IMG_NAME"
DOCKER_IMAGE="eclipse-temurin:25-jdk"
CP="/repo/build/m5-integrity/classes:/repo/build/m5-store/classes:/repo/build/live/classes:/repo/build/staging/lib/*"
MAIN="borg.trikeshed.btrfs.BtrfsIntegrityHarnessKt"

# ── pinned constants inherited from the upstream lanes ──────────────────────────
# VAL-BTRFS-001 / evidence/m5-volume.md §2 line 139: the EMPTY-VOLUME scrub floor,
# quoted verbatim, measured by THIS axis on THIS 8 GiB volume. Never re-derived.
FLOOR_TEXT="Total to scrub:   288.00KiB"
FLOOR_BYTES=$((288 * 1024))
# VAL-BTRFS-002 required >=1 MiB blobs; it wrote 1 MiB + 2 MiB + 4 MiB = 7 MiB.
# The contract's minimum margin over the floor is >=3 MiB.
MARGIN_BYTES=$((3 * 1024 * 1024))
# VAL-BTRFS-002 / evidence/m5-store.md: FINAL BLOB SET = 51 (50-blob corpus + the
# RESTORED planted-divergence blob).
EXPECT_COUNT=51
# VAL-BTRFS-002's NAMED exclusion list (`m5-store-planted-divergence-exclusions`) is
# EMPTY: the divergence was RESTORED and re-verified. Consumed by name; never widened.
EXCLUSIONS=()

if [ "$(uname -s)" = "Darwin" ]; then
  SELF="${BASH_SOURCE[0]}"
  DOCKER_ARGS=(docker run --rm -i --privileged
               -v "$IMG_DIR_HOST:/img"
               -v "$REPO_HOST:/repo:ro"
               "$DOCKER_IMAGE" bash -s)
  echo "### macOS shell: docker run command line, VERBATIM (bind mounts: ONLY $IMG_DIR_HOST rw and $REPO_HOST ro)"
  printf '%q ' "${DOCKER_ARGS[@]}"; printf '< %q\n' "$SELF"
  exec "${DOCKER_ARGS[@]}" < "$SELF"
fi

ctx() { echo; echo "### axis-2 container: $*"; }
sync_fs() { ctx "sync; btrfs filesystem sync $MNT   (MANDATORY before every extent/space reading — decision D12)"; sync; btrfs filesystem sync "$MNT"; }

if ! command -v btrfs >/dev/null 2>&1; then
  ctx "apt-get install btrfs-progs btrfs-compsize e2fsprogs util-linux (decision D10 image)"
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
    btrfs-progs btrfs-compsize e2fsprogs util-linux >/dev/null
fi

ctx "TOOL PROVENANCE"
btrfs --version
filefrag -V 2>&1 | head -1
lsattr -V 2>&1 | head -1
java -version 2>&1 | head -1
uname -m; uname -r

# ── attach + mount (never mkfs, never reset) ────────────────────────────────────
ctx "losetup -j $IMG  (loop device located BY BACKING FILE, never a hardcoded /dev/loopN)"
losetup -j "$IMG" || true
DEV="$(losetup -j "$IMG" 2>/dev/null | cut -d: -f1 | head -1)"
if [ -z "$DEV" ]; then DEV="$(losetup -f --show "$IMG")"; echo "attached $DEV -> $IMG"; else echo "reusing $DEV"; fi

ctx "mount $DEV $MNT   (mount only; this lane NEVER runs mkfs and NEVER resets the CAS tree)"
mkdir -p "$MNT"
findmnt -no TARGET "$MNT" >/dev/null 2>&1 || mount "$DEV" "$MNT"
findmnt "$MNT"

ctx "MOUNT OPTIONS — 'nodatacow' / 'nodatasum' MUST be ABSENT (data without checksums cannot be scrub-verified)"
findmnt -no OPTIONS "$MNT"
echo "-- grep for the disqualifying options:"
findmnt -no OPTIONS "$MNT" | tr ',' '\n' | grep -E '^(nodatacow|nodatasum)$' && echo "DISQUALIFYING OPTION PRESENT" || echo "(neither nodatacow nor nodatasum is set — data carries csums)"

ctx "btrfs filesystem show $MNT"
btrfs filesystem show "$MNT"
ctx "btrfs subvolume list $MNT   (the planes VAL-BTRFS-001 created)"
btrfs subvolume list "$MNT"
ctx "findmnt -no FSTYPE,SOURCE -T $CAS ; stat -f -c %T $CAS"
findmnt -no FSTYPE,SOURCE -T "$CAS"
stat -f -c %T "$CAS"

ctx "chattr +C / NOCOW check — lsattr on the CAS plane and on every blob (a 'C' flag means NO checksums)"
lsattr -d "$CAS" || true
lsattr -d "$CAS/sha256" || true
echo "-- blobs carrying the C (nodatacow) attribute:"
lsattr -R "$CAS" 2>/dev/null | grep -E '^[-a-zA-Z]+ +/' | awk '$1 ~ /C/ {print}' | tee /tmp/nocow.txt
echo "NOCOW blob count = $(wc -l < /tmp/nocow.txt)  (must be 0)"

# ── the physical readings, AT SCRUB TIME, after the mandatory sync ──────────────
sync_fs

ctx "[VAL-BTRFS-005] btrfs filesystem df $MNT — the PHYSICAL denominator, read AT SCRUB TIME (logical blob bytes are the WRONG denominator and are not used)"
btrfs filesystem df "$MNT" | tee /tmp/fidf.txt

ctx "[VAL-BTRFS-005] btrfs filesystem du -s $CAS — coverage SCOPED TO THE STORE so VAL-BTRFS-003's scratch plane cannot pad it"
btrfs filesystem du -s "$CAS"
ctx "[VAL-BTRFS-005] btrfs filesystem du -s $SCRATCH — the scratch plane shown SEPARATELY (its bytes are excluded from the store's own accounting)"
btrfs filesystem du -s "$SCRATCH" || echo "(scratch plane absent)"
ctx "[VAL-BTRFS-005] btrfs filesystem du -s $MNT — whole-mount total, for the record"
btrfs filesystem du -s "$MNT" || true

# ── the scrub ──────────────────────────────────────────────────────────────────
mkdir -p /var/lib/btrfs
ctx "[VAL-BTRFS-005] btrfs scrub start -B $MNT   (-B = foreground, runs to completion; the blob set already exists — this scrub runs AFTER the writes)"
btrfs scrub start -B "$MNT" 2>&1 | tee /tmp/scrub.txt
SCRUB_RC=${PIPESTATUS[0]}
echo "scrub exit code = $SCRUB_RC"

ctx "[VAL-BTRFS-005] btrfs scrub status -d $MNT   (PER-DEVID counters — zero csum, read and verify errors are read HERE, not inferred from a 'finished' line)"
btrfs scrub status -d "$MNT" 2>&1 | tee /tmp/scrubstatus.txt

ctx "[VAL-BTRFS-005] btrfs scrub status -d -R $MNT   (PER-DEVID RAW counters — the individual read_errors / csum_errors / verify_errors fields)"
btrfs scrub status -d -R "$MNT" 2>&1 | tee /tmp/scrubraw.txt

sync_fs
ctx "[VAL-BTRFS-005] btrfs filesystem df $MNT — AFTER the scrub (a scrub must not change usage)"
btrfs filesystem df "$MNT"

# ── the reconciliation arithmetic, computed here so it cannot be hand-waved ─────
ctx "[VAL-BTRFS-005] RECONCILIATION — Total to scrub vs btrfs filesystem df physical usage vs the VAL-BTRFS-001 empty-volume floor"
TOTAL_LINE="$(grep -E 'Total to scrub' /tmp/scrub.txt | head -1)"
echo "Total to scrub line (verbatim) : $TOTAL_LINE"
echo "VAL-BTRFS-001 EMPTY-VOLUME FLOOR (evidence/m5-volume.md, verbatim): $FLOOR_TEXT"

to_bytes() { # "1.23MiB" / "288.00KiB" / "4.00GiB" / "512.00B" -> bytes (awk; no python in this image)
  awk -v s="$1" 'BEGIN{
    if (match(s, /^[0-9]+(\.[0-9]+)?/)) { v = substr(s, RSTART, RLENGTH) + 0; u = substr(s, RSTART+RLENGTH) }
    else { print -1; exit }
    gsub(/[ \t]/, "", u)
    m = 1
    if (u == "KiB") m = 1024; else if (u == "MiB") m = 1048576;
    else if (u == "GiB") m = 1073741824; else if (u == "TiB") m = 1099511627776;
    else if (u == "PiB") m = 1125899906842624; else if (u == "B" || u == "") m = 1;
    else { print -1; exit }
    printf "%d\n", int(v * m + 0.5)
  }'
}

TOTAL_TXT="$(echo "$TOTAL_LINE" | awk '{print $NF}')"
TOTAL_B="$(to_bytes "$TOTAL_TXT")"
DATA_TXT="$(awk '/^Data,/ {for(i=1;i<=NF;i++) if($i ~ /^used=/){sub(/used=/,"",$i); print $i}}' /tmp/fidf.txt | head -1)"
META_TXT="$(awk '/^Metadata,/ {for(i=1;i<=NF;i++) if($i ~ /^used=/){sub(/used=/,"",$i); print $i}}' /tmp/fidf.txt | head -1)"
SYS_TXT="$(awk '/^System,/ {for(i=1;i<=NF;i++) if($i ~ /^used=/){sub(/used=/,"",$i); print $i}}' /tmp/fidf.txt | head -1)"
DATA_B="$(to_bytes "$DATA_TXT")"; META_B="$(to_bytes "$META_TXT")"; SYS_B="$(to_bytes "$SYS_TXT")"

echo
echo "  Total to scrub                        = $TOTAL_TXT  ($TOTAL_B bytes)"
echo "  btrfs fi df  Data used                = $DATA_TXT  ($DATA_B bytes)"
echo "  btrfs fi df  Metadata used            = $META_TXT  ($META_B bytes)"
echo "  btrfs fi df  System used              = $SYS_TXT  ($SYS_B bytes)"
echo "  Data+Metadata used                    = $((DATA_B + META_B)) bytes"
echo "  Data+Metadata+System used             = $((DATA_B + META_B + SYS_B)) bytes"
echo "  NOTE: btrfs mkfs defaults to DUP metadata on this image, so the DEVICE-side"
echo "        'Total to scrub' counts metadata TWICE; the identity to check is"
echo "        Total to scrub == Data + 2*Metadata + 2*System (single-device DUP)."
echo "  Data + 2*Metadata + 2*System          = $((DATA_B + 2*META_B + 2*SYS_B)) bytes"
echo "  EMPTY-VOLUME FLOOR (VAL-BTRFS-001)    = $FLOOR_BYTES bytes  ($FLOOR_TEXT)"
echo "  required margin (>=3 MiB of >=1 MiB blobs VAL-BTRFS-002 wrote) = $MARGIN_BYTES bytes"
echo "  floor + margin                        = $((FLOOR_BYTES + MARGIN_BYTES)) bytes"
if [ "$TOTAL_B" -gt "$((FLOOR_BYTES + MARGIN_BYTES))" ]; then
  echo "  FLOOR CHECK: PASS — Total to scrub ($TOTAL_B) > floor+margin ($((FLOOR_BYTES + MARGIN_BYTES)))"
  echo "               excess over the empty-volume floor = $((TOTAL_B - FLOOR_BYTES)) bytes"
else
  echo "  FLOOR CHECK: FAIL — Total to scrub ($TOTAL_B) <= floor+margin ($((FLOOR_BYTES + MARGIN_BYTES)))"
fi

ctx "[VAL-BTRFS-005] PER-DEVID ERROR COUNTERS extracted from btrfs scrub status -d"
grep -E 'Scrub device|read_errors|csum_errors|verify_errors|super_errors|malloc_errors|uncorrectable|corrected_errors|no_csum|Error summary|Total to scrub|Bytes scrubbed' /tmp/scrubstatus.txt || true
ERRSUM="$(grep -c -E 'read_errors: *[1-9]|csum_errors: *[1-9]|verify_errors: *[1-9]|super_errors: *[1-9]|uncorrectable_errors: *[1-9]|corrected_errors: *[1-9]|malloc_errors: *[1-9]' /tmp/scrubraw.txt /tmp/scrubstatus.txt 2>/dev/null | awk -F: '{s+=$2} END{print s+0}')"
echo "non-zero error-counter lines across scrub status -d and -R = $ERRSUM   (must be 0)"

# ── the counting rule, from the shell, and the .tmp residue check ──────────────
ctx "[VAL-BTRFS-005] COUNTING RULE (inherited verbatim from VAL-BTRFS-002): find <casRoot>/sha256 -regex '.*/sha256/[0-9a-f]{2}/[0-9a-f]{62}'"
find "$CAS/sha256" -mindepth 2 -maxdepth 2 -type f -regextype posix-extended -regex '.*/sha256/[0-9a-f]{2}/[0-9a-f]{62}' | wc -l
ctx "[VAL-BTRFS-005] all regular files under $CAS (must equal the blob set — nothing else is a blob)"
find "$CAS" -type f | wc -l
ctx "[VAL-BTRFS-005] find $CAS -name '*.tmp'  (and the writeAtomically dot-prefixed form) — must return NOTHING"
find "$CAS" \( -name '*.tmp' -o -name '.*.tmp' \) -print | tee /tmp/tmp-residue.txt
echo "residue count = $(wc -l < /tmp/tmp-residue.txt)"

ctx "[VAL-BTRFS-005] independent whole-set sha256 cross-check (sha256sum, NOT the JVM): every blob's content vs <shard><basename>"
BAD=0; N=0
while IFS= read -r f; do
  N=$((N+1))
  H="$(sha256sum "$f" | cut -d' ' -f1)"
  E="$(basename "$(dirname "$f")")$(basename "$f")"
  if [ "$H" != "$E" ]; then BAD=$((BAD+1)); echo "MISMATCH $f  sha256=$H  expected=$E"; fi
done < <(find "$CAS/sha256" -mindepth 2 -maxdepth 2 -type f -regextype posix-extended -regex '.*/sha256/[0-9a-f]{2}/[0-9a-f]{62}' | sort)
echo "sha256sum cross-check: $N blobs hashed, $BAD mismatches"

# ── the application-layer full-set re-verification ─────────────────────────────
ctx "[VAL-BTRFS-005] APPLICATION-LAYER FULL-SET RE-VERIFICATION through TrikeShed's own store code"
EX_ARGS=()
for c in "${EXCLUSIONS[@]+"${EXCLUSIONS[@]}"}"; do EX_ARGS+=(--exclude "$c"); done
echo "java -cp $CP $MAIN --cas-root $CAS --expect-count $EXPECT_COUNT ${EX_ARGS[*]-}"
java -cp "$CP" "$MAIN" --cas-root "$CAS" --expect-count "$EXPECT_COUNT" ${EX_ARGS[@]+"${EX_ARGS[@]}"}
HARNESS_RC=$?
ctx "integrity harness exit code = $HARNESS_RC   (0 = every cid re-verified)"

# ── teardown ───────────────────────────────────────────────────────────────────
ctx "LANE END — the CAS tree is unchanged by this lane (read-only scrub + read-only verification)"
find "$CAS" -type f | wc -l
btrfs subvolume list "$MNT"

ctx "teardown: sync, umount, detach BY BACKING FILE"
sync; btrfs filesystem sync "$MNT"; umount "$MNT" && echo "unmounted $MNT"
for d in $(losetup -j "$IMG" 2>/dev/null | cut -d: -f1); do losetup -d "$d" && echo "detached $d"; done
losetup -a || true
echo "### INTEGRITY RUN COMPLETE (scrub rc=$SCRUB_RC, harness rc=$HARNESS_RC, sha256sum mismatches=$BAD)"
[ "$SCRUB_RC" = "0" ] && [ "$HARNESS_RC" = "0" ] && [ "$BAD" = "0" ] || exit 1
