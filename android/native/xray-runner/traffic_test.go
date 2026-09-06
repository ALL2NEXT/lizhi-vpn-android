package main

import (
	"io"
	"sync/atomic"
	"testing"

	"github.com/xtls/xray-core/common/buf"
)

type sampleReader struct {
	data        buf.MultiBuffer
	interrupted bool
}

func (r *sampleReader) ReadMultiBuffer() (buf.MultiBuffer, error) {
	data := r.data
	r.data = nil
	return data, io.EOF
}
func (r *sampleReader) Interrupt() { r.interrupted = true }

type sampleWriter struct {
	size int32
	fail bool
}

func (w *sampleWriter) WriteMultiBuffer(data buf.MultiBuffer) error {
	w.size += data.Len()
	buf.ReleaseMulti(data)
	if w.fail {
		return io.ErrClosedPipe
	}
	return nil
}
func data(value string) buf.MultiBuffer {
	b := buf.New()
	_, _ = b.Write([]byte(value))
	return buf.MultiBuffer{b}
}

func TestCountsPayloadWithoutCopyAndPreservesEOF(t *testing.T) {
	original := data("hello")
	source := &sampleReader{data: original}
	var count atomic.Int64
	reader := &countingReader{Reader: source, counter: &count}
	received, err := reader.ReadMultiBuffer()
	defer buf.ReleaseMulti(received)
	if err != io.EOF || count.Load() != 5 || received[0] != original[0] {
		t.Fatal("read semantics changed")
	}
	reader.Interrupt()
	if !source.interrupted {
		t.Fatal("interrupt was not forwarded")
	}
}

func TestWritesCountOnlySuccessfulBuffers(t *testing.T) {
	var count atomic.Int64
	output := &sampleWriter{}
	writer := &countingWriter{Writer: output, counter: &count}
	if err := writer.WriteMultiBuffer(data("abc")); err != nil {
		t.Fatal(err)
	}
	output.fail = true
	if err := writer.WriteMultiBuffer(data("error")); err == nil {
		t.Fatal("write error lost")
	}
	if count.Load() != 3 {
		t.Fatalf("counted failed write: %d", count.Load())
	}
}
