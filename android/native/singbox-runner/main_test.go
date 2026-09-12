package main

import (
	"context"
	"os"
	"path/filepath"
	"testing"
)

func TestValidationParsesSupportedConfig(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	config := `{"inbounds":[{"type":"socks","tag":"internal-socks-in","listen":"127.0.0.1","listen_port":20800}],"outbounds":[{"type":"direct","tag":"direct"}]}`
	if err := os.WriteFile(path, []byte(config), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := loadConfig(runnerContext(context.Background(), t.TempDir()), path); err != nil {
		t.Fatalf("sing-box config was not parsed: %v", err)
	}
}

func TestValidationRejectsUnsupportedConfig(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	if err := os.WriteFile(path, []byte(`{"outbounds":[{"type":"unavailable"}]}`), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := loadConfig(runnerContext(context.Background(), t.TempDir()), path); err == nil {
		t.Fatal("unsupported outbound was accepted")
	}
}

func TestValidationAcceptsAutoDetectRoute(t *testing.T) {
	directory := t.TempDir()
	path := filepath.Join(directory, "config.json")
	config := `{"route":{"auto_detect_interface":true},"outbounds":[{"type":"direct","tag":"direct"}]}`
	if err := os.WriteFile(path, []byte(config), 0600); err != nil {
		t.Fatal(err)
	}
	if err := validate(path, directory); err != nil {
		t.Fatalf("auto-detect route was rejected during validation: %v", err)
	}
}
