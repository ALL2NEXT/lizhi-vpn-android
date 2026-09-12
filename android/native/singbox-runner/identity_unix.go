//go:build android || linux || darwin

package main

import "os"

func currentUserIDs() (int, int) {
	return os.Getuid(), os.Getgid()
}
