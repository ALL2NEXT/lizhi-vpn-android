//go:build android || linux || darwin

package main

import (
	"context"
	"fmt"
	"net"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/xtls/xray-core/core"
)

func controlConnection(path, token, command string) (*net.UnixConn, error) {
	conn, err := net.DialUnix("unix", nil, &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		return nil, err
	}
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err = fmt.Fprintf(conn, "%s %s\n", command, token); err != nil {
		conn.Close()
		return nil, err
	}
	return conn, nil
}

func run(config *core.Config, path, token string) error {
	if path == "" || len(token) < 16 {
		return fmt.Errorf("missing control session")
	}
	watcher, err := controlConnection(path, token, "WATCH")
	if err != nil {
		return err
	}
	defer watcher.Close()
	_ = watcher.SetDeadline(time.Time{})
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	// Bound teardown even on Android 7, where Process.destroyForcibly is
	// unavailable to the host.
	go func() { <-ctx.Done(); time.Sleep(2 * time.Second); os.Exit(0) }()
	go func() { var b [1]byte; _, _ = watcher.Read(b[:]); cancel() }()

	server, err := core.NewWithContext(ctx, config)
	if err != nil {
		return err
	}
	defer server.Close()
	if err := server.Start(); err != nil {
		return err
	}
	emit(map[string]any{"event": "ready"})
	<-ctx.Done()
	return nil
}
