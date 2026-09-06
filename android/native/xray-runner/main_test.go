package main

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/xtls/xray-core/common/serial"
	"xyz.a202132/mobile/xrayrunner/statsconfig"
)

func TestValidationBuildsWithoutStartingRuntime(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	config := `{"inbounds":[{"protocol":"socks","tag":"internal-socks-in","listen":"127.0.0.1","port":20800,"settings":{"udp":true}}],"outbounds":[{"protocol":"freedom","tag":"direct"}]}`
	if err := os.WriteFile(path, []byte(config), 0600); err != nil {
		t.Fatal(err)
	}
	built, err := loadConfig(path)
	if err != nil {
		t.Fatalf("static validation must build without starting Xray: %v", err)
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
