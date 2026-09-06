package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"sync"

	"github.com/xtls/xray-core/app/dispatcher"
	"github.com/xtls/xray-core/common/serial"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf"
	_ "github.com/xtls/xray-core/main/distro/all"
	"xyz.a202132/mobile/xrayrunner/statsconfig"
)

var eventLock sync.Mutex

func emit(event any) {
	eventLock.Lock()
	defer eventLock.Unlock()
	_ = json.NewEncoder(os.Stdout).Encode(event)
}

func loadConfig(path string) (*core.Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var config conf.Config
	if err := json.Unmarshal(data, &config); err != nil {
		return nil, err
	}
	// Build protobuf configuration only. core.New initializes TUN even without Start.
	result, err := config.Build()
	if err != nil {
		return nil, err
	}
	for i, app := range result.App {
		if app.Type == serial.ToTypedMessage(&dispatcher.Config{}).Type {
			result.App[i] = serial.ToTypedMessage(&statsconfig.Config{})
		}
	}
	return result, nil
}

func main() {
	path := flag.String("config", "", "private configuration file")
	control := flag.String("control", "", "private Android control socket")
	token := flag.String("token", "", "session token")
	check := flag.Bool("check", false, "parse/build without opening a TUN")
	export := flag.String("export-rules", "", "export shared domain/IP rule snapshot to this file")
	geosite := flag.String("geosite", "", "source geosite SRS")
	geoip := flag.String("geoip", "", "source geoip SRS")
	flag.Parse()
	var err error
	if *export != "" {
		err = exportRules(*geosite, *geoip, *export)
	} else {
		var config *core.Config
		config, err = loadConfig(*path)
		if err == nil && !*check {
			err = run(config, *control, *token)
		}
	}
	if err != nil {
		// Core errors may contain node credentials. Do not forward raw configuration errors.
		emit(map[string]any{"event": "error", "message": "Xray configuration or runtime failed"})
		fmt.Fprintln(os.Stderr, "Xray runner failed; validate node parameters and platform resources")
		os.Exit(1)
	}
}
