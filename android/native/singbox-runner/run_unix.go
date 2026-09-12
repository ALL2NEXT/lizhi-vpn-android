//go:build android || linux || darwin

package main

import (
	"context"
	"fmt"
	"net"
	"os"
	"os/signal"
	"syscall"
)

func controlConnection(path string, token string) (*net.UnixConn, error) {
	conn, err := net.DialUnix("unix", nil, &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		return nil, err
	}
	if _, err = fmt.Fprintf(conn, "WATCH %s\n", token); err != nil {
		conn.Close()
		return nil, err
	}
	return conn, nil
}

func run(configPath string, controlPath string, token string, directory string) error {
	if controlPath == "" || len(token) < 16 {
		return fmt.Errorf("missing control session")
	}

	watcher, err := controlConnection(controlPath, token)
	if err != nil {
		return err
	}
	defer watcher.Close()

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	go func() {
		var b [1]byte
		_, _ = watcher.Read(b[:])
		cancel()
	}()

	instance, err := create(ctx, configPath, directory)
	if err != nil {
		return err
	}
	if err = instance.Start(); err != nil {
		instance.Close()
		return err
	}
	emit(map[string]any{"event": "ready"})

	<-ctx.Done()
	return instance.Close()
}
