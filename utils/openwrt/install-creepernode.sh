#!/bin/sh
# Copyright 2017 TrikeShed contributors. AGPL-3.0-or-later.
set -eu

[ "$(id -u)" = 0 ] || { echo 'install-creepernode: root required' >&2; exit 1; }
[ -r /etc/openwrt_release ] || { echo 'install-creepernode: OpenWrt required' >&2; exit 1; }

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
for file in creepernode-participant.sh creepernode.init creeperctl.sh; do
    [ -r "$HERE/$file" ] || { echo "install-creepernode: missing $file" >&2; exit 1; }
done

missing=''
for package in kmod-sctp ncat; do
    opkg status "$package" 2>/dev/null | grep -q '^Status: .* installed$' || missing="$missing $package"
done
if [ -n "$missing" ]; then
    opkg update
    # shellcheck disable=SC2086
    opkg install $missing
fi

modprobe sctp
mkdir -p /usr/libexec/creepernode /usr/sbin
cp "$HERE/creepernode-participant.sh" /usr/libexec/creepernode/participant
cp "$HERE/creeperctl.sh" /usr/sbin/creeperctl
cp "$HERE/creepernode.init" /etc/init.d/creepernode
chmod 0755 /usr/libexec/creepernode/participant /usr/sbin/creeperctl /etc/init.d/creepernode

# Preserve the existing pools; only assert that this router remains the LAN DHCP authority.
uci set dhcp.lan.dhcpv4='server'
uci set dhcp.lan.force='1'
uci set dhcp.@dnsmasq[0].authoritative='1'
uci set dhcp.creepernode='domain'
uci set dhcp.creepernode.name='creepernode.lan'
uci set dhcp.creepernode.ip="$(uci -q get network.lan.ipaddr || echo 192.168.8.1)"
uci commit dhcp
/etc/init.d/dnsmasq reload

/etc/init.d/creepernode enable
/etc/init.d/creepernode restart
sleep 1
/usr/sbin/creeperctl 127.0.0.1 STATUS
