package mobile

import (
	"context"
	"net"
	"sync"

	"io.surprise.vpn/payloadengine"
)

// Engine is an optional Go carrier runtime. It is deliberately separate from
// V2-TUN's Kotlin/Xray transport and can be enabled only by an explicit adapter.
type Engine struct {
	mu       sync.Mutex
	nextID   int
	conns    map[int]net.Conn
	lastErr  string
}

func NewEngine() *Engine { return &Engine{nextID: 1, conns: make(map[int]net.Conn)} }

func (e *Engine) Open(proxyHost string, proxyPort int, targetHost string, targetPort int, sni string, useTLS bool, payload string) int {
	e.mu.Lock(); defer e.mu.Unlock()
	conn, _, err := payloadengine.Open(context.Background(), payloadengine.Config{
		ProxyHost: proxyHost, ProxyPort: proxyPort, TargetHost: targetHost,
		TargetPort: targetPort, SNI: sni, TLS: useTLS, Payload: payload,
	})
	if err != nil { e.lastErr = err.Error(); return 0 }
	id := e.nextID; e.nextID++; e.conns[id] = conn; e.lastErr = ""; return id
}

func (e *Engine) Read(id int, maxBytes int) []byte {
	e.mu.Lock(); conn := e.conns[id]; e.mu.Unlock()
	if conn == nil || maxBytes <= 0 { return nil }
	buffer := make([]byte, maxBytes); n, err := conn.Read(buffer)
	if err != nil && n == 0 { return nil }
	return buffer[:n]
}

func (e *Engine) Write(id int, data []byte) int {
	e.mu.Lock(); conn := e.conns[id]; e.mu.Unlock()
	if conn == nil { return -1 }
	n, err := conn.Write(data); if err != nil { return -1 }; return n
}

func (e *Engine) Close(id int) {
	e.mu.Lock(); conn := e.conns[id]; delete(e.conns, id); e.mu.Unlock()
	if conn != nil { _ = conn.Close() }
}

func (e *Engine) LastError() string { e.mu.Lock(); defer e.mu.Unlock(); return e.lastErr }
