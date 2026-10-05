package main

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing/service"
	"golang.org/x/net/proxy"
)

func startTestCore(t *testing.T, internal, lan int) (func() (int64, int64), func()) {
	t.Helper()
	directory := t.TempDir()
	path := filepath.Join(directory, "config.json")
	config := fmt.Sprintf(`{"inbounds":[{"type":"socks","tag":"internal-socks-in","listen":"127.0.0.1","listen_port":%d},{"type":"socks","tag":"lan-socks-in","listen":"127.0.0.1","listen_port":%d}],"outbounds":[{"type":"direct","tag":"direct"}]}`, internal, lan)
	if err := os.WriteFile(path, []byte(config), 0600); err != nil {
		t.Fatal(err)
	}
	traffic := new(lanTrafficCounters)
	instance, err := create(service.ContextWith(context.Background(), traffic), path, directory)
	if err != nil {
		t.Fatal(err)
	}
	var once sync.Once
	closeCore := func() { once.Do(func() { instance.Close() }) }
	t.Cleanup(closeCore)
	if err := instance.Start(); err != nil {
		t.Fatal(err)
	}
	return traffic.snapshot, closeCore
}

func freeTCPPort(t *testing.T) int {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	listener.Close()
	return port
}

func tcpEcho(t *testing.T) string {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { listener.Close() })
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go func() { defer conn.Close(); io.Copy(conn, conn) }()
		}
	}()
	return listener.Addr().String()
}

func tcpViaSocks(t *testing.T, port int, target string, payload []byte) {
	t.Helper()
	dialer, err := proxy.SOCKS5("tcp", fmt.Sprintf("127.0.0.1:%d", port), nil, &net.Dialer{Timeout: 3 * time.Second})
	if err != nil {
		t.Fatal(err)
	}
	conn, err := dialer.Dial("tcp", target)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := conn.Write(payload); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, len(payload))
	if _, err := io.ReadFull(conn, reply); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(reply, payload) {
		t.Fatalf("TCP echo mismatch: %q", reply)
	}
}

func udpEcho(t *testing.T) string {
	t.Helper()
	conn, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { conn.Close() })
	go func() {
		buffer := make([]byte, 2048)
		for {
			n, peer, err := conn.ReadFrom(buffer)
			if err != nil {
				return
			}
			conn.WriteTo(buffer[:n], peer)
		}
	}()
	return conn.LocalAddr().String()
}

func udpViaSocks(t *testing.T, port int, target string, payload []byte) []byte {
	t.Helper()
	control, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), 3*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer control.Close()
	control.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := control.Write([]byte{5, 1, 0}); err != nil {
		t.Fatal(err)
	}
	greeting := make([]byte, 2)
	if _, err := io.ReadFull(control, greeting); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(greeting, []byte{5, 0}) {
		t.Fatalf("unexpected greeting %v", greeting)
	}
	if _, err := control.Write([]byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
		t.Fatal(err)
	}
	header := make([]byte, 4)
	if _, err := io.ReadFull(control, header); err != nil {
		t.Fatal(err)
	}
	if header[1] != 0 || header[3] != 1 {
		t.Fatalf("unexpected UDP reply %v", header)
	}
	address := make([]byte, 6)
	if _, err := io.ReadFull(control, address); err != nil {
		t.Fatal(err)
	}
	relay := &net.UDPAddr{IP: net.IP(address[:4]), Port: int(binary.BigEndian.Uint16(address[4:]))}
	if relay.IP.IsUnspecified() {
		relay.IP = net.IPv4(127, 0, 0, 1)
	}
	conn, err := net.DialUDP("udp", nil, relay)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(3 * time.Second))
	destination, err := net.ResolveUDPAddr("udp4", target)
	if err != nil {
		t.Fatal(err)
	}
	packet := append([]byte{0, 0, 0, 1}, destination.IP.To4()...)
	packet = binary.BigEndian.AppendUint16(packet, uint16(destination.Port))
	packet = append(packet, payload...)
	if _, err := conn.Write(packet); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, 2048)
	n, err := conn.Read(reply)
	if err != nil {
		t.Fatal(err)
	}
	if n < 10 || reply[3] != 1 {
		t.Fatalf("unexpected UDP payload %v", reply[:n])
	}
	return reply[10:n]
}

func waitTraffic(t *testing.T, read func() (int64, int64), minUp, minDown int64) (int64, int64) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for {
		up, down := read()
		if up >= minUp && down >= minDown {
			return up, down
		}
		if time.Now().After(deadline) {
			t.Fatalf("traffic = %d/%d, want at least %d/%d", up, down, minUp, minDown)
		}
		time.Sleep(5 * time.Millisecond)
	}
}

func TestLanTrafficCountsTCPAndUDPButExcludesInternalSocks(t *testing.T) {
	internal, lan := freeTCPPort(t), freeTCPPort(t)
	read, closeCore := startTestCore(t, internal, lan)
	tcp, udp := tcpEcho(t), udpEcho(t)
	payload := []byte("traffic from a shared device")
	tcpViaSocks(t, internal, tcp, payload)
	if reply := udpViaSocks(t, internal, udp, payload); !bytes.Equal(reply, payload) {
		t.Fatalf("UDP echo mismatch: %q", reply)
	}
	if up, down := read(); up != 0 || down != 0 {
		t.Fatalf("internal SOCKS was counted: %d/%d", up, down)
	}
	tcpViaSocks(t, lan, tcp, payload)
	beforeUp, beforeDown := waitTraffic(t, read, int64(len(payload)), int64(len(payload)))
	if reply := udpViaSocks(t, lan, udp, payload); !bytes.Equal(reply, payload) {
		t.Fatalf("UDP echo mismatch: %q", reply)
	}
	up, down := waitTraffic(t, read, beforeUp+int64(len(payload)), beforeDown+int64(len(payload)))
	closeCore()
	finalUp, finalDown := read()
	if finalUp < up || finalDown < down {
		t.Fatalf("shutdown lost totals: %d/%d -> %d/%d", up, down, finalUp, finalDown)
	}
}

func TestReporterFlushesFinalSnapshotWithoutWaitingForTicker(t *testing.T) {
	output, err := os.CreateTemp(t.TempDir(), "events")
	if err != nil {
		t.Fatal(err)
	}
	original := os.Stdout
	os.Stdout = output
	defer func() { os.Stdout = original; output.Close() }()
	var up, down atomic.Int64
	stop := startLanTrafficReporter(func() (int64, int64) { return up.Load(), down.Load() })
	up.Store(150)
	down.Store(280)
	stop()
	if _, err := output.Seek(0, 0); err != nil {
		t.Fatal(err)
	}
	decoder := json.NewDecoder(output)
	var last struct {
		Event            string
		Upload, Download int64
	}
	for {
		var event struct {
			Event            string
			Upload, Download int64
		}
		if err := decoder.Decode(&event); err == io.EOF {
			break
		} else if err != nil {
			t.Fatal(err)
		}
		last = event
	}
	if last.Event != "lan-traffic" || last.Upload != 150 || last.Download != 280 {
		t.Fatalf("missing final totals: %+v", last)
	}
}
