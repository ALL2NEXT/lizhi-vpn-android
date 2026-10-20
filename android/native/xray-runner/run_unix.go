//go:build android || linux || darwin

package main

import (
	"bufio"
	"context"
	"fmt"
	"net"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/routing"
)

const (
	failoverBalancerTag    = "chimera-failover"
	failoverOutboundPrefix = "chimera-failover-node-"
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
	server, err := core.NewWithContext(ctx, config)
	if err != nil {
		return err
	}
	if err := server.Start(); err != nil {
		server.Close()
		return err
	}
	balancer := failoverBalancer(server)
	if balancer != nil {
		if err := balancer.SetOverrideTarget(failoverBalancerTag, failoverOutboundPrefix+"0"); err != nil {
			server.Close()
			return err
		}
	}
	go watchControlCommands(ctx, watcher, balancer, cancel)
	stopOutboundReporting := startOutboundSelectionReporter(ctx, balancer)
	emit(map[string]any{"event": "ready"})
	stopReporting := startLanTrafficReporter(lanTrafficReader(server))
	<-ctx.Done()
	stopOutboundReporting()
	err = server.Close()
	stopReporting()
	return err
}

func failoverBalancer(server *core.Instance) routing.BalancerOverrider {
	router, ok := server.GetFeature(routing.RouterType()).(routing.Router)
	if !ok {
		return nil
	}
	balancer, ok := router.(routing.BalancerOverrider)
	if !ok {
		return nil
	}
	if _, err := balancer.GetOverrideTarget(failoverBalancerTag); err != nil {
		return nil
	}
	return balancer
}

func watchControlCommands(
	ctx context.Context,
	watcher *net.UnixConn,
	balancer routing.BalancerOverrider,
	cancel context.CancelFunc,
) {
	scanner := bufio.NewScanner(watcher)
	for scanner.Scan() {
		select {
		case <-ctx.Done():
			return
		default:
		}
		fields := strings.Fields(scanner.Text())
		if len(fields) != 2 || fields[0] != "SELECT" || balancer == nil ||
			!strings.HasPrefix(fields[1], failoverOutboundPrefix) {
			continue
		}
		if err := balancer.SetOverrideTarget(failoverBalancerTag, fields[1]); err != nil {
			emit(map[string]any{"event": "control-error", "message": "Xray 出站切换失败"})
		}
	}
	// Kotlin 关闭 WATCH 连接以结束核心会话。
	cancel()
}

// 将核心中手动覆盖的出站同步给服务状态，不触发 VPN 隧道重建。
func startOutboundSelectionReporter(ctx context.Context, balancer routing.BalancerOverrider) func() {
	if balancer == nil {
		return func() {}
	}
	done := make(chan struct{})
	go func() {
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		lastTag := ""
		report := func() {
			selectedTag, err := balancer.GetOverrideTarget(failoverBalancerTag)
			if err != nil {
				return
			}
			if selectedTag == "" {
				selectedTag = failoverOutboundPrefix + "0"
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
