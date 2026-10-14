//go:build windows

package main

import (
	"errors"
)

func run(string, string, string, string) error {
	return errors.New("Android runner cannot run on Windows")
}
