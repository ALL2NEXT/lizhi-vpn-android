package main

import (
	"os"
	"path/filepath"
	"testing"
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
	if built == nil || len(built.App) == 0 {
		t.Fatal("validated config did not include runtime applications")
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
