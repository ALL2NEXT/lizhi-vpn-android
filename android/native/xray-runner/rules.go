package main

import (
	"bytes"
	"compress/zlib"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"net/netip"
	"os"
	"regexp"
	"strings"

	"github.com/sagernet/sing/common/domain"
	"go4.org/netipx"
)

// This deliberately accepts only positive, flat domain/IP lists, not arbitrary
// sing-box policy. Format reference: pinned sing-box/common/srs/{binary,ip_set}.go.
// The succinct matcher decoder is already a dependency of the pinned Xray core.
type routeSnapshot struct {
	Schema  int      `json:"schema"`
	Domains []string `json:"domains"`
	IPs     []string `json:"ips"`
}

var errRuleFormat = errors.New("unsupported or invalid common rule set")

const maxRuleBytes = 32 << 20

func readRuleList(data []byte, ipList bool) (result []string, err error) {
	defer func() {
		if recover() != nil {
			result, err = nil, errRuleFormat
		}
	}()
	if len(data) < 4 || len(data) > maxRuleBytes || string(data[:3]) != "SRS" || data[3] < 1 || data[3] > 4 {
		return nil, errRuleFormat
	}
	z, err := zlib.NewReader(bytes.NewReader(data[4:]))
	if err != nil {
		return nil, err
	}
	defer z.Close()
	body, err := io.ReadAll(io.LimitReader(z, maxRuleBytes+1))
	if err != nil || len(body) > maxRuleBytes {
		return nil, errRuleFormat
	}
	r := bytes.NewReader(body)
	count, err := binary.ReadUvarint(r)
	if err != nil || count == 0 || count > 100_000 {
		return nil, errRuleFormat
	}
	for range count {
		kind, err := r.ReadByte()
		if err != nil || kind != 0 {
			return nil, errRuleFormat
		}
		before := len(result)
	items:
		for {
			item, err := r.ReadByte()
			if err != nil {
				return nil, err
			}
			switch {
			case item == 255:
				invert, err := r.ReadByte()
				if err != nil || invert != 0 || len(result) == before {
					return nil, errRuleFormat
				}
				break items
			case item == 2 && !ipList:
				// Validate encoded slice lengths before the shared decoder allocates.
				start, _ := r.Seek(0, io.SeekCurrent)
				if _, err := r.ReadByte(); err != nil {
					return nil, err
				}
				for _, width := range []uint64{8, 8, 1} {
					n, err := binary.ReadUvarint(r)
					if err != nil || n > uint64(r.Len())/width {
						return nil, errRuleFormat
					}
					r.Seek(int64(n*width), io.SeekCurrent)
				}
				r.Seek(start, io.SeekStart)
				matcher, err := domain.ReadMatcher(r)
				if err != nil {
					return nil, err
				}
				exact, suffix := matcher.Dump()
				for _, value := range exact {
					result = append(result, "full:"+value)
				}
				for _, value := range suffix {
					if strings.HasPrefix(value, ".") {
						result = append(result, "regexp:"+regexp.QuoteMeta(value)+"$")
					} else {
						result = append(result, "domain:"+value)
					}
				}
			case (item == 3 || item == 4) && !ipList:
				n, err := binary.ReadUvarint(r)
				if err != nil || n > uint64(r.Len()) {
					return nil, errRuleFormat
				}
				for range n {
					length, err := binary.ReadUvarint(r)
					if err != nil || length > uint64(r.Len()) {
						return nil, errRuleFormat
					}
					value := make([]byte, length)
					io.ReadFull(r, value)
					prefix := "keyword:"
					if item == 4 {
						prefix = "regexp:"
					}
					result = append(result, prefix+string(value))
				}
			case item == 6 && ipList:
				version, err := r.ReadByte()
				if err != nil || version != 1 {
					return nil, errRuleFormat
				}
				var n uint64
				if binary.Read(r, binary.BigEndian, &n) != nil || n > uint64(r.Len())/10 {
					return nil, errRuleFormat
				}
				for range n {
					from, err := readRuleAddress(r)
					if err != nil {
						return nil, err
					}
					to, err := readRuleAddress(r)
					if err != nil {
						return nil, err
					}
					rangeOfIPs := netipx.IPRangeFrom(from, to)
					if !rangeOfIPs.IsValid() {
						return nil, errRuleFormat
					}
					for _, prefix := range rangeOfIPs.Prefixes() {
						result = append(result, prefix.String())
					}
				}
			default:
				return nil, errRuleFormat
			}
		}
	}
	if r.Len() != 0 {
		return nil, errRuleFormat
	}
	return result, nil
}

func readRuleAddress(r *bytes.Reader) (netip.Addr, error) {
	n, err := binary.ReadUvarint(r)
	if err != nil || (n != 4 && n != 16) {
		return netip.Addr{}, errRuleFormat
	}
	data := make([]byte, n)
	if _, err := io.ReadFull(r, data); err != nil {
		return netip.Addr{}, err
	}
	address, ok := netip.AddrFromSlice(data)
	if !ok {
		return netip.Addr{}, errRuleFormat
	}
	return address, nil
}

func exportRules(sitePath, ipPath, output string) error {
	read := func(path string, ip bool) ([]string, error) {
		file, err := os.Open(path)
		if err != nil {
			return nil, err
		}
		defer file.Close()
		data, err := io.ReadAll(io.LimitReader(file, maxRuleBytes+1))
		if err != nil {
			return nil, err
		}
		return readRuleList(data, ip)
	}
	domains, err := read(sitePath, false)
	if err != nil {
		return err
	}
	ips, err := read(ipPath, true)
	if err != nil {
		return err
	}
	data, err := json.Marshal(routeSnapshot{1, domains, ips})
	if err != nil {
		return err
	}
	return os.WriteFile(output, data, 0600)
}
