package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/experimental/deprecated"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	singjson "github.com/sagernet/sing/common/json"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/filemanager"
)

var outputLock = make(chan struct{}, 1)

func emit(event any) {
	outputLock <- struct{}{}
	defer func() { <-outputLock }()
	_ = json.NewEncoder(os.Stdout).Encode(event)
}

func loadConfig(ctx context.Context, path string) (option.Options, error) {
	content, err := os.ReadFile(path)
	if err != nil {
		return option.Options{}, err
	}
	return singjson.UnmarshalExtendedContext[option.Options](ctx, content)
}

func runnerContext(ctx context.Context, directory string, checkOnly ...bool) context.Context {
	if directory != "" {
		uid, gid := currentUserIDs()
		ctx = filemanager.WithDefault(ctx, directory, directory, uid, gid)
	}
	ctx = service.ContextWith(ctx, deprecated.NewStderrManager(log.StdLogger()))
	if len(checkOnly) > 0 && checkOnly[0] {
		// libbox.CheckConfig used this Android platform stub. It lets Box.New
		// validate auto-detect related options without starting a monitor.
		ctx = service.ContextWith[adapter.PlatformInterface](ctx, (*platformInterfaceStub)(nil))
	}
	return include.Context(ctx)
}

func create(ctx context.Context, configPath string, directory string, checkOnly ...bool) (*box.Box, error) {
	ctx = runnerContext(ctx, directory, checkOnly...)
	options, err := loadConfig(ctx, configPath)
	if err != nil {
		return nil, err
	}
	return box.New(box.Options{
		Context: ctx,
		Options: options,
	})
}

func validate(configPath string, directory string) error {
	instance, err := create(context.Background(), configPath, directory, true)
	if err != nil {
		return err
	}
	// Match libbox.CheckConfig: validation is about construction. Closing an
	// instance that was never started may report a cleanup-only error, which
	// must not turn an otherwise valid configuration into a rejection.
	_ = instance.Close()
	return nil
}

func main() {
	configPath := flag.String("config", "", "private configuration file")
	controlPath := flag.String("control", "", "private Android control socket")
	token := flag.String("token", "", "session token")
	directory := flag.String("directory", "", "private working directory")
	check := flag.Bool("check", false, "parse and build without starting sing-box")
	flag.Parse()

	var err error
	switch {
	case *configPath == "":
		err = fmt.Errorf("missing config path")
	case *check:
		err = validate(*configPath, *directory)
	default:
		err = run(*configPath, *controlPath, *token, *directory)
	}
	if err != nil {
		emit(map[string]any{
			"event":   "error",
			"message": err.Error(),
		})
		fmt.Fprintln(os.Stderr, "sing-box runner failed:", err)
		os.Exit(1)
	}
}
