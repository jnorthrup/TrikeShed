#!/bin/sh
# Copyright 2017 TrikeShed contributors. AGPL-3.0-or-later.
set -eu

ROOT=${CREEPER_ROOT:-/mnt/creeper}
REPO=${CREEPER_REPO:-$ROOT/TrikeShed}
UPSTREAM=${CREEPER_UPSTREAM:-}
BRANCH=${CREEPER_BRANCH:-master}
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

[ "$(id -u)" = 0 ] || { echo 'bootstrap-node: root required' >&2; exit 1; }
[ -r /etc/openwrt_release ] || { echo 'bootstrap-node: OpenWrt required' >&2; exit 1; }
grep -q " $ROOT " /proc/mounts || { echo "bootstrap-node: $ROOT is not mounted" >&2; exit 1; }
fs=$(awk -v root="$ROOT" '$2 == root { print $3; exit }' /proc/mounts)
[ "$fs" = ext4 ] || { echo "bootstrap-node: $ROOT must be ext4, found $fs" >&2; exit 1; }

mkdir -p "$ROOT/node/work/inbox" "$ROOT/node/work/active" \
    "$ROOT/node/work/done" "$ROOT/node/work/failed" "$ROOT/node/artifacts"
printf '%s\n' control dhcp sctp git work-host artifact-cache > "$ROOT/node/capabilities"

if [ -d "$REPO/.git" ]; then
    git -C "$REPO" fetch --depth 1 origin "$BRANCH"
    if [ -z "$(git -C "$REPO" status --porcelain)" ]; then
        git -C "$REPO" checkout "$BRANCH"
        git -C "$REPO" merge --ff-only "origin/$BRANCH"
    else
        echo 'bootstrap-node: preserving modified checkout; fetched upstream only' >&2
    fi
else
    [ -n "$UPSTREAM" ] || { echo 'bootstrap-node: CREEPER_UPSTREAM is required for the initial clone' >&2; exit 1; }
    git clone --depth 1 --single-branch --branch "$BRANCH" "$UPSTREAM" "$REPO"
fi

git -C "$REPO" config fetch.prune true
"$HERE/install-creepernode.sh"
printf 'bootstrap-node: repo=%s head=%s filesystem=%s\n' \
    "$REPO" "$(git -C "$REPO" rev-parse --short HEAD)" "$fs"
