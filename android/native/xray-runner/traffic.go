package main

import (
	"time"

	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/stats"
)

const lanInboundTag = "lan-socks-in"

func lanTrafficReader(server *core.Instance) func() (int64, int64) {
	manager := server.GetFeature(stats.ManagerType()).(stats.Manager)
	// Keep direct references for the final snapshot after core shutdown.
	upload := manager.GetCounter("inbound>>>" + lanInboundTag + ">>>traffic>>>uplink")
	download := manager.GetCounter("inbound>>>" + lanInboundTag + ">>>traffic>>>downlink")
	return func() (int64, int64) {
		var up, down int64
		if upload != nil {
			up = upload.Value()
		}
		if download != nil {
			down = download.Value()
		}
		return up, down
	}
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
		report()
	}
}
