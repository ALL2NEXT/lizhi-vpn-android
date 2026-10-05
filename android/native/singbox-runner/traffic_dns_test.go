package main

import (
	"context"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"testing"

	"github.com/miekg/dns"
	"github.com/sagernet/sing/service"
)

func TestLanTrafficIncludesHijackedDNS(t *testing.T) {
	packetConn, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	dnsServer := &dns.Server{PacketConn: packetConn, Handler: dns.HandlerFunc(func(writer dns.ResponseWriter, request *dns.Msg) {
		response := new(dns.Msg)
		response.SetReply(request)
		response.Answer = []dns.RR{&dns.A{
			Hdr: dns.RR_Header{Name: request.Question[0].Name, Rrtype: dns.TypeA, Class: dns.ClassINET, Ttl: 60},
			A:   net.IPv4(192, 0, 2, 1),
		}}
		writer.WriteMsg(response)
	})}
	go dnsServer.ActivateAndServe()
	t.Cleanup(func() { dnsServer.Shutdown(); packetConn.Close() })
	port := freeTCPPort(t)
	directory := t.TempDir()
	path := filepath.Join(directory, "config.json")
	config := fmt.Sprintf(`{"inbounds":[{"type":"socks","tag":"lan-socks-in","listen":"127.0.0.1","listen_port":%d}],"outbounds":[{"type":"direct","tag":"direct"}],"dns":{"servers":[{"type":"udp","tag":"test","server":"127.0.0.1","server_port":%d}]},"route":{"rules":[{"port":53,"action":"hijack-dns"}]}}`, port, packetConn.LocalAddr().(*net.UDPAddr).Port)
	if err := os.WriteFile(path, []byte(config), 0600); err != nil {
		t.Fatal(err)
	}
	traffic := new(lanTrafficCounters)
	instance, err := create(service.ContextWith(context.Background(), traffic), path, directory)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { instance.Close() })
	if err := instance.Start(); err != nil {
		t.Fatal(err)
	}
	request := new(dns.Msg)
	request.SetQuestion("lan-traffic.test.", dns.TypeA)
	payload, err := request.Pack()
	if err != nil {
		t.Fatal(err)
	}
	reply := udpViaSocks(t, port, "192.0.2.53:53", payload)
	response := new(dns.Msg)
	if err := response.Unpack(reply); err != nil {
		t.Fatal(err)
	}
	if len(response.Answer) != 1 {
		t.Fatalf("missing DNS answer: %v", response)
	}
	waitTraffic(t, traffic.snapshot, int64(len(payload)), int64(len(reply)))
}
