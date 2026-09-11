# OpenWrt Creeper Node

Minimal router-side Creeper Node: the existing dnsmasq instance remains the authoritative DHCP hub, while a procd-managed SCTP participant exposes bounded discovery/status commands.

Runtime packages: `kmod-sctp`, `ncat`. `git-http` is bootstrap-only when installing from a local Git clone.

Install from the checked-out directory:

    ./install-creepernode.sh

Protocol (SCTP port 3868): `HELLO`, `PING`, `STATUS`, `DHCP`. DHCP responses omit client MAC addresses. Query locally or remotely:

    creeperctl 127.0.0.1 STATUS
    creeperctl 192.168.8.1 DHCP

Service operations:

    /etc/init.d/creepernode status
    /etc/init.d/creepernode restart
    logread -e creepernode

The installer preserves all existing DHCP pools and static leases. It only reasserts LAN DHCP server/authoritative mode and publishes `creepernode.lan` through dnsmasq.
