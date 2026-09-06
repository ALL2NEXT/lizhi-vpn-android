// Generates an Android-only Go overlay; the pinned upstream checkout stays clean.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"go/format"
	"os"
	"path/filepath"
	"strings"
)

func main() {
	source := flag.String("source", "../../../xray-core", "pinned Xray source")
	output := flag.String("output", "../../app/build/generated/xray/tun-overlay", "generated overlay directory")
	flag.Parse()
	original, err := filepath.Abs(filepath.Join(*source, "proxy/tun/udp_fullcone.go"))
	must(err)
	directory, err := filepath.Abs(*output)
	must(err)
	data, err := os.ReadFile(original)
	must(err)
	code := strings.ReplaceAll(string(data), "\r\n", "\n")
	replace := func(before, after string, count int) {
		if strings.Count(code, before) != count {
			panic("Pinned Xray UDP source changed; review Android TUN overlay: " + before)
		}
		code = strings.ReplaceAll(code, before, after)
	}
	// Destination is per connection in this pinned handler. A source-only key
	// incorrectly sends later destinations through the first destination's route.
	replace("map[net.Destination]*udpConn", "map[udpFlow]*udpConn", 2)
	replace("// sub-handler specifically", "type udpFlow struct { src, dst net.Destination }\n\n// sub-handler specifically", 1)
	replace("// this custom handler support FullCone NAT of returning packets, binding connection only by the source addr:port", "// Preserve each source/destination flow while accepting endpoint metadata on replies.", 1)
	replace("data []byte) bool {\n\tu.Lock()\n\tconn, found := u.udpConns[src]", "data []byte) bool {\n\tu.Lock()\n\tdefer u.Unlock()\n\tkey := udpFlow{src, dst}\n\tconn, found := u.udpConns[key]", 1)
	replace("u.udpConns[src] = conn", "u.udpConns[key] = conn", 1)
	// Enqueue under the same lock as close to avoid send-on-closed-channel races.
	replace("\tu.Unlock()\n\n\t// send packet", "\n\t// send packet", 1)
	replace("func (u *udpConnectionHandler) connectionFinished(src net.Destination)", "func (u *udpConnectionHandler) connectionFinished(closing *udpConn)", 1)
	replace("\tconn, found := u.udpConns[src]\n\tif found {\n\t\tdelete(u.udpConns, src)", "\tkey := udpFlow{closing.src, closing.dst}\n\tconn, found := u.udpConns[key]\n\tif found && conn == closing {\n\t\tdelete(u.udpConns, key)", 1)
	replace("c.handler.connectionFinished(c.src)", "c.handler.connectionFinished(c)", 1)
	// buf.NewReader otherwise wraps net.Conn in an 8 KiB stream reader, truncating
	// UDP datagrams larger than that even though the configured TUN MTU is 9000.
	replace("// Read packets from the connection", `func (c *udpConn) ReadMultiBuffer() (buf.MultiBuffer, error) {
	data, ok := <-c.egress
	if !ok { return nil, io.EOF }
	return buf.MultiBuffer{buf.FromBytes(data)}, nil
}

// Read packets from the connection`, 1)
	replace("\t\treturn 0, nil", "\t\treturn 0, err", 1)
	replace("func (c *udpConn) WriteMultiBuffer(mb buf.MultiBuffer) error {", "func (c *udpConn) WriteMultiBuffer(mb buf.MultiBuffer) error {\n\tdefer buf.ReleaseMulti(mb)", 1)
	patched, err := format.Source([]byte(code))
	must(err)
	must(os.MkdirAll(directory, 0755))
	target := filepath.Join(directory, "udp_fullcone.go")
	must(os.WriteFile(target, patched, 0644))
	tests, err := filepath.Abs("patches/udp_fullcone_test.go.txt")
	must(err)
	overlay, err := json.Marshal(struct{ Replace map[string]string }{map[string]string{
		original: target,
		filepath.Join(filepath.Dir(original), "android_udp_test.go"): tests,
	}})
	must(err)
	must(os.WriteFile(filepath.Join(directory, "overlay.json"), overlay, 0644))
	fmt.Println(filepath.Join(directory, "overlay.json"))
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}
