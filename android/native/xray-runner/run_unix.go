//go:build android || linux || darwin

package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/transport/internet"
	"golang.org/x/sys/unix"
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

func protectSocket(path, token string, fd int) error {
	conn, err := controlConnection(path, token, "PROTECT")
	if err != nil {
		return err
	}
	defer conn.Close()
	if _, _, err := conn.WriteMsgUnix([]byte{1}, unix.UnixRights(fd), nil); err != nil {
		return err
	}
	var reply [1]byte
	if _, err := io.ReadFull(conn, reply[:]); err != nil {
		return err
	}
	if reply[0] != 1 {
		return errors.New("socket protection rejected")
	}
	return nil
}

func run(config *core.Config, path, token string) error {
	if path == "" || len(token) < 16 {
		return errors.New("missing control session")
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

	controller := func(_, _ string, raw syscall.RawConn) error {
		var protectErr error
		err := raw.Control(func(fd uintptr) { protectErr = protectSocket(path, token, int(fd)) })
		if err != nil || protectErr != nil {
			// Pinned Xray logs controller errors and continues. Never return into that path.
			emit(map[string]any{"event": "error", "message": "Unable to protect Xray socket"})
			os.Exit(2)
		}
		return nil
	}
	if err := internet.RegisterDialerController(controller); err != nil {
		return err
	}
	if err := internet.RegisterListenerController(controller); err != nil {
		return err
	}
	server, err := core.NewWithContext(ctx, config)
	if err != nil {
		return err
	}
	defer server.Close()
	if err := server.Start(); err != nil {
		return err
	}
	emit(map[string]any{"event": "ready"})
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			emit(map[string]any{"event": "traffic", "upload": uploaded.Load(), "download": downloaded.Load()})
			return nil
		case <-ticker.C:
			emit(map[string]any{"event": "traffic", "upload": uploaded.Load(), "download": downloaded.Load()})
		}
	}
}
