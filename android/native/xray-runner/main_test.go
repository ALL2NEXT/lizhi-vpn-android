package main

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/xtls/xray-core/common/serial"
	"xyz.a202132/mobile/xrayrunner/statsconfig"
)

func TestValidationDoesNotOpenTun(t *testing.T) {
	t.Setenv("XRAY_TUN_FD", "not-a-descriptor")
	path := filepath.Join(t.TempDir(), "config.json")
	config := `{"inbounds":[{"protocol":"tun","tag":"tun-in","settings":{"name":"firefly","MTU":9000}}],"outbounds":[{"protocol":"freedom","tag":"direct"}]}`
	if err := os.WriteFile(path, []byte(config), 0600); err != nil {
		t.Fatal(err)
	}
	built, err := loadConfig(path)
	if err != nil {
		t.Fatalf("static validation must not initialize TUN: %v", err)
	}
	found := false
	for _, app := range built.App {
		if app.Type == serial.ToTypedMessage(&statsconfig.Config{}).Type {
			found = true
		}
	}
	if !found {
		t.Fatal("plaintext counting dispatcher was not installed")
	}
}

func TestValidationRejectsUnsupportedProtocol(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	if err := os.WriteFile(path, []byte(`{"outbounds":[{"protocol":"unavailable"}]}`), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := loadConfig(path); err == nil {
		t.Fatal("unsupported protocol accepted")
	}
}
