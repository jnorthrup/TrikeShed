#!/bin/sh
# Copyright 2017 TrikeShed contributors. AGPL-3.0-or-later.
set -eu

LEASES=/tmp/dhcp.leases
NODE_ID="$(cat /proc/sys/kernel/hostname 2>/dev/null || echo creepernode)"
IFS= read -r request || request=HELLO
set -- $request
verb=$(printf '%s' "${1:-HELLO}" | tr 'a-z' 'A-Z')

case "$verb" in
    HELLO|PING)
        printf 'CREEPERNODE/1 %s PONG\n' "$NODE_ID"
        ;;
    STATUS)
        lease_count=0
        [ ! -r "$LEASES" ] || lease_count=$(wc -l < "$LEASES" | tr -d ' ')
        printf 'CREEPERNODE/1 node=%s uptime=%s leases=%s sctp=ready dhcp=authoritative\n' \
            "$NODE_ID" "$(cut -d. -f1 /proc/uptime)" "$lease_count"
        ;;
    DHCP)
        printf 'CREEPERNODE/1 DHCP\n'
        if [ -r "$LEASES" ]; then
            while read -r expiry _mac address hostname _client_id; do
                printf 'LEASE %s %s %s\n' "$expiry" "$address" "${hostname:--}"
            done < "$LEASES"
        fi
        printf '.\n'
        ;;
    *)
        printf 'CREEPERNODE/1 ERROR unsupported=%s commands=HELLO,PING,STATUS,DHCP\n' "$verb"
        ;;
esac
