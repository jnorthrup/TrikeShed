// Independent interoperability probe. This is an alien test process, never a TrikeShed transport.
package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"strings"
	"time"

	"github.com/ipfs/boxo/ipns"
	libp2p "github.com/libp2p/go-libp2p"
	dht "github.com/libp2p/go-libp2p-kad-dht"
	record "github.com/libp2p/go-libp2p-record"
	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/libp2p/go-libp2p/p2p/muxer/yamux"
	tls "github.com/libp2p/go-libp2p/p2p/security/tls"
	"github.com/libp2p/go-libp2p/p2p/transport/tcp"
	ma "github.com/multiformats/go-multiaddr"
)

func fail(err error) {
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
func emit(value any) { fail(json.NewEncoder(os.Stdout).Encode(value)) }
func main() {
	mode := flag.String("mode", "serve", "serve or get")
	name := flag.String("name", "", "IPNS CID or peer ID")
	peers := flag.String("peers", "", "comma-separated full peer multiaddrs; empty uses public bootstrap")
	listen := flag.String("listen", "/ip4/127.0.0.1/tcp/0", "server listen multiaddr")
	expect := flag.String("expect", "", "required resolved path")
	timeout := flag.Duration("timeout", 120*time.Second, "operation deadline")
	rsa := flag.Bool("rsa", false, "use RSA2048 host identity for TLS verification")
	flag.Parse()
	ctx, cancel := context.WithTimeout(context.Background(), *timeout)
	defer cancel()
	kind := crypto.Ed25519
	if *rsa {
		kind = crypto.RSA
	}
	identity, _, err := crypto.GenerateKeyPair(kind, 2048)
	fail(err)
	h, err := libp2p.New(libp2p.Identity(identity), libp2p.ListenAddrStrings(*listen), libp2p.Transport(tcp.NewTCPTransport),
		libp2p.Security(tls.ID, tls.New), libp2p.Muxer(yamux.ID, yamux.DefaultTransport), libp2p.DisableRelay())
	fail(err)
	defer h.Close()
	dhtMode := dht.ModeClient
	if *mode == "serve" {
		dhtMode = dht.ModeServer
	}
	kad, err := dht.New(h, dht.Mode(dhtMode), dht.Validator(record.NamespacedValidator{"pk": record.PublicKeyValidator{}, "ipns": ipns.Validator{}}))
	fail(err)
	defer kad.Close()
	if *mode == "serve" {
		addresses := make([]string, 0, len(h.Addrs()))
		for _, a := range h.Addrs() {
			addresses = append(addresses, a.String()+"/p2p/"+h.ID().String())
		}
		emit(map[string]any{"peer": h.ID().String(), "addresses": addresses, "protocol": "/ipfs/kad/1.0.0", "transport": "tcp/tls1.3/yamux"})
		stop := make(chan os.Signal, 1)
		signal.Notify(stop, os.Interrupt)
		<-stop
		return
	}
	if *mode != "get" {
		fail(fmt.Errorf("unknown mode %s", *mode))
	}
	addresses := dht.DefaultBootstrapPeers
	if *peers != "" {
		addresses = nil
		for _, s := range strings.Split(*peers, ",") {
			a, e := ma.NewMultiaddr(s)
			fail(e)
			addresses = append(addresses, a)
		}
	}
	connected := 0
	for _, a := range addresses {
		info, e := peer.AddrInfoFromP2pAddr(a)
		fail(e)
		if e = h.Connect(ctx, *info); e == nil {
			connected++
		} else {
			fmt.Fprintln(os.Stderr, e)
		}
	}
	if connected == 0 {
		fail(fmt.Errorf("no bootstrap connection"))
	}
	fail(kad.Bootstrap(ctx))
	for kad.RoutingTable().Size() == 0 {
		select {
		case <-ctx.Done():
			fail(fmt.Errorf("no identified DHT peer admitted: %w", ctx.Err()))
		case <-time.After(20 * time.Millisecond):
		}
	}
	n, err := ipns.NameFromString(*name)
	fail(err)
	wire, err := kad.GetValue(ctx, string(n.RoutingKey()))
	fail(err)
	rec, err := ipns.UnmarshalRecord(wire)
	fail(err)
	fail(ipns.ValidateWithName(rec, n))
	value, err := rec.Value()
	fail(err)
	seq, err := rec.Sequence()
	fail(err)
	eol, err := rec.Validity()
	fail(err)
	if *expect != "" && value.String() != *expect {
		fail(fmt.Errorf("value mismatch: %s != %s", value.String(), *expect))
	}
	emit(map[string]any{"name": n.String(), "value": value.String(), "sequence": seq, "validUntil": eol, "wireBase64": base64.StdEncoding.EncodeToString(wire), "verifierPeer": h.ID().String(), "connected": connected, "valid": true})
}
