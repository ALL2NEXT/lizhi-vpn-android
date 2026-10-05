package main

import (
	"context"
	"net"
	"sync/atomic"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing/common/bufio"
	N "github.com/sagernet/sing/common/network"
)

const lanInboundTag = "lan-socks-in"

type lanTrafficCounters struct {
	upload   atomic.Int64
	download atomic.Int64
}

func (c *lanTrafficCounters) snapshot() (int64, int64) {
	return c.upload.Load(), c.download.Load()
}

// Wrap the LAN inbound's router before sniffing or DNS hijacking. A routed
// connection tracker would miss requests handled by hijack-dns.
type lanInboundRegistry struct {
	adapter.InboundRegistry
	traffic *lanTrafficCounters
}

func (r *lanInboundRegistry) Create(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag, inboundType string, options any) (adapter.Inbound, error) {
	if tag == lanInboundTag {
		router = &lanTrafficRouter{Router: router, traffic: r.traffic}
	}
	return r.InboundRegistry.Create(ctx, router, logger, tag, inboundType, options)
}

type lanTrafficRouter struct {
	adapter.Router
	traffic *lanTrafficCounters
}

func (r *lanTrafficRouter) countConnection(conn net.Conn) net.Conn {
	return bufio.NewInt64CounterConn(conn, []*atomic.Int64{&r.traffic.upload}, []*atomic.Int64{&r.traffic.download})
}

func (r *lanTrafficRouter) countPacketConnection(conn N.PacketConn) N.PacketConn {
	return bufio.NewInt64CounterPacketConn(conn, []*atomic.Int64{&r.traffic.upload}, nil, []*atomic.Int64{&r.traffic.download}, nil)
}

func (r *lanTrafficRouter) RouteConnection(ctx context.Context, conn net.Conn, metadata adapter.InboundContext) error {
	return r.Router.RouteConnection(ctx, r.countConnection(conn), metadata)
}

func (r *lanTrafficRouter) RoutePacketConnection(ctx context.Context, conn N.PacketConn, metadata adapter.InboundContext) error {
	return r.Router.RoutePacketConnection(ctx, r.countPacketConnection(conn), metadata)
}

func (r *lanTrafficRouter) RouteConnectionEx(ctx context.Context, conn net.Conn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	r.Router.RouteConnectionEx(ctx, r.countConnection(conn), metadata, onClose)
}

func (r *lanTrafficRouter) RoutePacketConnectionEx(ctx context.Context, conn N.PacketConn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	r.Router.RoutePacketConnectionEx(ctx, r.countPacketConnection(conn), metadata, onClose)
}

func startLanTrafficReporter(read func() (int64, int64)) func() {
	report := func() {
		upload, download := read()
		emit(map[string]any{"event": "lan-traffic", "upload": upload, "download": download})
	}
	stop, done := make(chan struct{}), make(chan struct{})
	go func() {
		defer close(done)
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				report()
			case <-stop:
				return
			}
		}
	}()
	return func() {
		close(stop)
		<-done
		// The caller closes the core first, then flushes the final totals.
		report()
	}
}
