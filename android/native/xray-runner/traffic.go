package main

import (
	"context"
	"sync/atomic"
	"time"

	"github.com/xtls/xray-core/app/dispatcher"
	"github.com/xtls/xray-core/common"
	"github.com/xtls/xray-core/common/buf"
	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/session"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/routing"
	"github.com/xtls/xray-core/transport"
	"xyz.a202132/mobile/xrayrunner/statsconfig"
)

var uploaded, downloaded atomic.Int64

type countingDispatcher struct{ routing.Dispatcher }

func counted(ctx context.Context) bool {
	inbound := session.InboundFromContext(ctx)
	return inbound != nil && (inbound.Tag == "tun-in" || inbound.Tag == "internal-socks-in")
}

func (d *countingDispatcher) Dispatch(ctx context.Context, dest net.Destination) (*transport.Link, error) {
	link, err := d.Dispatcher.Dispatch(ctx, dest)
	if err == nil && counted(ctx) {
		link.Reader = &countingReader{Reader: link.Reader, counter: &downloaded}
		link.Writer = &countingWriter{Writer: link.Writer, counter: &uploaded}
	}
	return link, err
}

func (d *countingDispatcher) DispatchLink(ctx context.Context, dest net.Destination, link *transport.Link) error {
	if counted(ctx) {
		link.Reader = &countingReader{Reader: link.Reader, counter: &uploaded}
		link.Writer = &countingWriter{Writer: link.Writer, counter: &downloaded}
	}
	return d.Dispatcher.DispatchLink(ctx, dest, link)
}

type countingReader struct {
	buf.Reader
	counter *atomic.Int64
}

func (r *countingReader) ReadMultiBuffer() (buf.MultiBuffer, error) {
	data, err := r.Reader.ReadMultiBuffer()
	r.counter.Add(int64(data.Len()))
	return data, err
}
func (r *countingReader) ReadMultiBufferTimeout(timeout time.Duration) (buf.MultiBuffer, error) {
	if reader, ok := r.Reader.(buf.TimeoutReader); ok {
		data, err := reader.ReadMultiBufferTimeout(timeout)
		r.counter.Add(int64(data.Len()))
		return data, err
	}
	return nil, buf.ErrReadTimeout
}
func (r *countingReader) Interrupt()   { common.Interrupt(r.Reader) }
func (r *countingReader) Close() error { return common.Close(r.Reader) }

type countingWriter struct {
	buf.Writer
	counter *atomic.Int64
}

func (w *countingWriter) WriteMultiBuffer(data buf.MultiBuffer) error {
	size := data.Len()
	err := w.Writer.WriteMultiBuffer(data)
	if err == nil {
		w.counter.Add(int64(size))
	}
	return err
}
func (w *countingWriter) Interrupt()   { common.Interrupt(w.Writer) }
func (w *countingWriter) Close() error { return common.Close(w.Writer) }

func init() {
	common.Must(common.RegisterConfig((*statsconfig.Config)(nil), func(ctx context.Context, _ any) (any, error) {
		inner, err := core.CreateObject(core.MustFromContext(ctx), &dispatcher.Config{})
		if err != nil {
			return nil, err
		}
		return &countingDispatcher{Dispatcher: inner.(routing.Dispatcher)}, nil
	}))
}
