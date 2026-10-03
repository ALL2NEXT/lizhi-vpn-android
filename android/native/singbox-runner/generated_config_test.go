package main

import (
	"context"
	"path/filepath"
	"testing"

	C "github.com/sagernet/sing-box/constant"
)

// Exercise the exact URI-generated configurations with the pinned core, including
// startup: parsing alone does not catch invalid DNS dependencies and TLS options.
func TestKotlinConfigurationFixtures(t *testing.T) {
	// An "unknown" version only warns about removed configuration fields;
	// match the actual Android build so these regressions fail here too.
	originalVersion := C.Version
	C.Version = "1.14.1"
	defer func() { C.Version = originalVersion }()
	files, err := filepath.Glob("../../app/build/singbox-config-fixtures/*.json")
	if err != nil {
		t.Fatal(err)
	}
	if len(files) == 0 {
		t.Skip("run :app:testDebugUnitTest to export Kotlin configuration fixtures")
	}
	for _, path := range files {
		path, err := filepath.Abs(path)
		if err != nil {
			t.Fatal(err)
		}
		t.Run(filepath.Base(path), func(t *testing.T) {
			t.Chdir(t.TempDir())
			instance, err := create(context.Background(), path, "")
			if err != nil {
				t.Fatal(err)
			}
			defer instance.Close()
			if err := instance.Start(); err != nil {
				t.Fatal(err)
			}
		})
	}
}
