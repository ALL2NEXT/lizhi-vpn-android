package main

import (
	"context"
	"encoding/json"
	"net/netip"
	"os"
	"path/filepath"
	"testing"

	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/session"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/routing"
	routingSession "github.com/xtls/xray-core/features/routing/session"
)

// The Android unit suite exports actual Kotlin-composed configurations here.
func TestKotlinConfigurationFixtures(t *testing.T) {
	files, err := filepath.Glob("../../app/build/native-config-fixtures/*.json")
	if err != nil {
		t.Fatal(err)
	}
	if len(files) == 0 {
		t.Skip("run :app:testDebugUnitTest first to export Kotlin fixtures")
	}
	for _, path := range files {
		t.Run(filepath.Base(path), func(t *testing.T) {
			if _, err := loadConfig(path); err != nil {
				t.Fatal(err)
			}
		})
	}
}

func TestSmartUsesGeoIPAfterDomainLookup(t *testing.T) {
	raw, err := os.ReadFile("../../app/build/native-config-fixtures/SMART-DISABLED.json")
	if os.IsNotExist(err) {
		t.Skip("run Android unit tests to generate configuration")
	}
	if err != nil {
		t.Fatal(err)
	}
	var config map[string]any
	if err := json.Unmarshal(raw, &config); err != nil {
		t.Fatal(err)
	}
	delete(config, "inbounds") // Instantiate routing only; no TUN or listener.
	var snapshot routeSnapshot
	rules, err := os.ReadFile("../../app/src/test/resources/routes-cn.json")
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(rules, &snapshot); err != nil {
		t.Fatal(err)
	}
	address := netip.MustParsePrefix(snapshot.IPs[0]).Addr().String()
	const domain = "not-in-geosite.example"
	config["dns"].(map[string]any)["hosts"] = map[string]string{domain: address}
	data, err := json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "config.json")
	if err := os.WriteFile(path, data, 0600); err != nil {
		t.Fatal(err)
	}
	built, err := loadConfig(path)
	if err != nil {
		t.Fatal(err)
	}
	server, err := core.New(built)
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	ctx := session.ContextWithOutbounds(context.Background(), []*session.Outbound{{
		Target: net.TCPDestination(net.DomainAddress(domain), 443),
	}})
	router := server.GetFeature(routing.RouterType()).(routing.Router)
	route, err := router.PickRoute(routingSession.AsRoutingContext(ctx))
	if err != nil {
		t.Fatal(err)
	}
	if route.GetOutboundTag() != "direct" {
		t.Fatalf("domain resolved to CN IP but got route %s", route.GetOutboundTag())
	}
}
