package main

import (
	"bytes"
	"compress/zlib"
	"encoding/json"
	"os"
	"reflect"
	"slices"
	"testing"
)

func TestRulesMatchPinnedSingBoxDecompile(t *testing.T) {
	// Oracle produced by sing-box rule-set decompile, not this reader.
	raw, err := os.ReadFile("../../app/src/test/resources/routes-cn.json")
	if err != nil {
		t.Fatal(err)
	}
	var expected routeSnapshot
	if err := json.Unmarshal(raw, &expected); err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		name string
		ip   bool
		want []string
	}{
		{"geosite-cn.srs", false, expected.Domains}, {"geoip-cn.srs", true, expected.IPs},
	} {
		t.Run(tc.name, func(t *testing.T) {
			data, err := os.ReadFile("../../app/src/main/assets/rule-sets/" + tc.name)
			if err != nil {
				t.Fatal(err)
			}
			got, err := readRuleList(data, tc.ip)
			if err != nil {
				t.Fatal(err)
			}
			slices.Sort(got)
			slices.Sort(tc.want)
			if !reflect.DeepEqual(got, tc.want) {
				t.Fatalf("rule content differs: got %d, want %d", len(got), len(tc.want))
			}
			if _, err := readRuleList(data, !tc.ip); err == nil {
				t.Fatal("mixed policy must not flatten into OR")
			}
		})
	}
}

func TestRulesRejectLossyOrInvalidPolicy(t *testing.T) {
	for _, body := range [][]byte{
		{1, 1},                       // logical rule
		{1, 0, 3, 1, 1, 'a', 255, 1}, // inverted rule
		{1, 0, 255, 0},               // empty rule matches everything
		{1, 0, 2, 0, 255, 255, 255, 255, 255, 255, 255, 255, 255, 1}, // oversized matcher
	} {
		var input bytes.Buffer
		input.Write([]byte{'S', 'R', 'S', 3})
		z := zlib.NewWriter(&input)
		z.Write(body)
		z.Close()
		if _, err := readRuleList(input.Bytes(), false); err == nil {
			t.Fatal("invalid policy accepted")
		}
	}
}
