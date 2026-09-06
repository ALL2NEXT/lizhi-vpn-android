//go:build windows

package main

import (
	"errors"
	"github.com/xtls/xray-core/core"
)

func run(*core.Config, string, string) error {
	return errors.New("Android runner cannot run on Windows")
}
