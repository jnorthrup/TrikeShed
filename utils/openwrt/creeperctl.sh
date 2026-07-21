#!/bin/sh
# Copyright 2017 TrikeShed contributors. AGPL-3.0-or-later.
set -eu

host=${1:-192.168.8.1}
command=${2:-STATUS}
port=${CREEPERNODE_PORT:-3868}
printf '%s\n' "$command" | ncat --sctp --wait 5 "$host" "$port"
