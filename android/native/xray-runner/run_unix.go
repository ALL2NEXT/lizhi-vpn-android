//go:build android || linux || darwin

package main

import (
	"context"
	"fmt"
	"net"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/xtls/xray-core/app/observatory"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/extension"
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
	if err := server.Start(); err != nil {
		server.Close()
		return err
	}
	emit(map[string]any{"event": "ready"})
	stopReporting := startLanTrafficReporter(lanTrafficReader(server))
	stopOutboundReporting := startOutboundSelectionReporter(ctx, server)
	<-ctx.Done()
	stopOutboundReporting()
	err = server.Close()
	stopReporting()
	return err
}

// 将核心的出站选择同步给服务状态，不触发 VPN 隧道重建。
func startOutboundSelectionReporter(ctx context.Context, server *core.Instance) func() {
	observer, ok := server.GetFeature(extension.ObservatoryType()).(extension.Observatory)
	if !ok {
		return func() {}
	}
	done := make(chan struct{})
	go func() {
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		lastTag := ""
		report := func() {
			result, err := observer.GetObservation(ctx)
			if err != nil {
				return
			}
			observation, ok := result.(*observatory.ObservationResult)
			if !ok {
				return
			}
			selectedTag := ""
			selectedDelay := int64(1<<63 - 1)
			for _, status := range observation.GetStatus() {
				if status.GetAlive() &&
					strings.HasPrefix(status.GetOutboundTag(), "chimera-failover-node-") &&
					status.GetDelay() < selectedDelay {
					selectedTag = status.GetOutboundTag()
					selectedDelay = status.GetDelay()
				}
			}
			if selectedTag == "" {
				selectedTag = "chimera-failover-node-0"
			}
			if selectedTag != lastTag {
				emit(map[string]any{"event": "outbound", "tag": selectedTag})
				lastTag = selectedTag
			}
		}
		report()
		for {
			select {
			case <-ctx.Done():
				return
			case <-done:
				return
			case <-ticker.C:
				report()
			}
		}
	}()
	return func() { close(done) }
}
