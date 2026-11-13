package payloadengine

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/sha1"
	"crypto/tls"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

const (
	maxHeaderBytes = 64 * 1024
	maxFrameBytes  = 8 * 1024 * 1024
	maxRedirects   = 5
	webSocketGUID  = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
)

type Config struct {
	ProxyHost       string
	ProxyPort       int
	TargetHost      string
	TargetPort      int
	SNI             string
	TLS             bool
	Payload         string
	ProxyUser       string
	ProxyPassword   string
	Timeout         time.Duration
	MaxRedirects    int
	AllowRedirectTo bool
	Log             func(string)
	FollowRedirects bool
}

type Result struct {
	StatusCode int
	Redirects  int
	Host       string
	Path       string
	WebSocket  bool
}

func RenderPayload(template, host string, port int, path, sni string) string {
	if path == "" {
		path = "/"
	}
	if sni == "" {
		sni = host
	}
	hostPort := host
	if port != 80 && port != 443 {
		hostPort = net.JoinHostPort(host, strconv.Itoa(port))
	}
	values := map[string]string{"[host]": hostPort, "[host_port]": hostPort, "[port]": strconv.Itoa(port), "[path]": path, "[sni]": sni, "[front_domain]": sni, "[protocol]": "HTTP/1.1", "[crlf]": "\r\n", "[lf]": "\n"}
	values["[ua]"] = "Mozilla/5.0"
	values["[user_agent]"] = values["[ua]"]
	for key, value := range values {
		template = strings.ReplaceAll(template, key, value)
		template = strings.ReplaceAll(template, strings.ToUpper(key), value)
	}
	if !strings.Contains(template, "\r\n") {
		template = strings.ReplaceAll(template, "\n", "\r\n")
	}
	// Some mobile payload editors export a truncated placeholder such as
	// `Host: crlf]`. Treat it as a CRLF token rather than sending malformed
	// header syntax to the front end.
	template = strings.ReplaceAll(template, "crlf]", "\r\n")
	template = normalizeWebSocketHeaders(template, hostPort)
	return rewritePath(template, path)
}

func normalizeWebSocketHeaders(payload, hostPort string) string {
	if !strings.Contains(strings.ToLower(payload), "upgrade: websocket") {
		return payload
	}
	parts := strings.SplitN(payload, "\r\n\r\n", 2)
	lines := strings.Split(parts[0], "\r\n")
	seen := map[string]bool{}
	for i, line := range lines {
		if i == 0 || !strings.Contains(line, ":") {
			continue
		}
		name, value, _ := strings.Cut(line, ":")
		key := strings.ToLower(strings.TrimSpace(name))
		seen[key] = true
		if key == "host" && strings.TrimSpace(value) == "" {
			lines[i] = "Host: " + hostPort
		}
		if key == "user-agent" && strings.TrimSpace(value) == "" {
			lines[i] = "User-Agent: Mozilla/5.0"
		}
	}
	if !seen["host"] {
		lines = append(lines, "Host: "+hostPort)
	}
	if !seen["connection"] {
		lines = append(lines, "Connection: Upgrade")
	}
	if !seen["upgrade"] {
		lines = append(lines, "Upgrade: websocket")
	}
	if !seen["user-agent"] {
		lines = append(lines, "User-Agent: Mozilla/5.0")
	}
	if !seen["sec-websocket-version"] {
		lines = append(lines, "Sec-WebSocket-Version: 13")
	}
	body := ""
	if len(parts) == 2 {
		body = "\r\n\r\n" + parts[1]
	}
	return strings.Join(lines, "\r\n") + body
}

func Open(ctx context.Context, cfg Config) (net.Conn, Result, error) {
	if cfg.ProxyHost == "" || cfg.TargetHost == "" {
		return nil, Result{}, errors.New("payload endpoint is incomplete")
	}
	if cfg.ProxyPort < 1 || cfg.ProxyPort > 65535 || cfg.TargetPort < 1 || cfg.TargetPort > 65535 {
		return nil, Result{}, errors.New("payload endpoint port is invalid")
	}
	if cfg.Timeout <= 0 {
		cfg.Timeout = 15 * time.Second
	}
	if cfg.MaxRedirects <= 0 || cfg.MaxRedirects > maxRedirects {
		cfg.MaxRedirects = maxRedirects
	}
	host, port, path, redirects := cfg.TargetHost, cfg.TargetPort, "/", 0
	useTLS, serverName := cfg.TLS, cfg.SNI
	if !cfg.FollowRedirects {
		cfg.FollowRedirects = true
	}
	for {
		connectHost, connectPort := cfg.ProxyHost, cfg.ProxyPort
		if redirects > 0 {
			connectHost, connectPort = host, port
		}
		conn, err := dial(ctx, cfg, connectHost, connectPort, useTLS, serverName)
		if err != nil {
			return nil, Result{}, err
		}
		payload := RenderPayload(cfg.Payload, host, port, path, cfg.SNI)
		if strings.TrimSpace(payload) == "" {
			return conn, Result{Host: host, Path: path, Redirects: redirects}, nil
		}
		prepared, key := prepareWebSocket(payload)
		if err = writeBlocks(conn, prepared, cfg.Timeout); err != nil {
			conn.Close()
			return nil, Result{}, err
		}
		response, err := readFinalResponse(conn, cfg.Timeout)
		if err != nil {
			conn.Close()
			return nil, Result{}, err
		}
		if cfg.FollowRedirects && response.StatusCode >= 300 && response.StatusCode <= 399 && response.Header.Get("Location") != "" && redirects < cfg.MaxRedirects {
			if !cfg.AllowRedirectTo && !sameHost(response.Header.Get("Location"), host) {
				conn.Close()
				return nil, Result{}, errors.New("redirect target is not allowed")
			}
			var redirectedTLS bool
			host, port, path, redirectedTLS, err = resolveRedirectURL(response.Header.Get("Location"), host, port, path, useTLS)
			conn.Close()
			if err != nil {
				return nil, Result{}, err
			}
			redirects++
			useTLS = redirectedTLS
			serverName = host
			continue
		}
		if !acceptedStatus(response.StatusCode) {
			conn.Close()
			return nil, Result{}, fmt.Errorf("payload rejected with HTTP %d", response.StatusCode)
		}
		ws := response.StatusCode == http.StatusSwitchingProtocols && strings.EqualFold(response.Header.Get("Upgrade"), "websocket")
		if ws {
			if err := validateAccept(response.Header.Get("Sec-WebSocket-Accept"), key); err != nil {
				conn.Close()
				return nil, Result{}, err
			}
			conn = newWebSocketConn(conn)
		}
		return conn, Result{StatusCode: response.StatusCode, Redirects: redirects, Host: host, Path: path, WebSocket: ws}, nil
	}
}

// OpenOnConn negotiates on an already-connected, VpnService-protected socket.
func OpenOnConn(ctx context.Context, cfg Config, conn net.Conn) (net.Conn, Result, error) {
	if conn == nil {
		return nil, Result{}, errors.New("payload connection is nil")
	}
	if err := ctx.Err(); err != nil {
		return nil, Result{}, err
	}
	if cfg.Timeout <= 0 {
		cfg.Timeout = 15 * time.Second
	}
	payload := RenderPayload(cfg.Payload, cfg.TargetHost, cfg.TargetPort, "/", cfg.SNI)
	if strings.TrimSpace(payload) == "" {
		return conn, Result{Host: cfg.TargetHost, Path: "/"}, nil
	}
	prepared, key := prepareWebSocket(payload)
	if err := writeBlocks(conn, prepared, cfg.Timeout); err != nil {
		return nil, Result{}, err
	}
	response, err := readFinalResponse(conn, cfg.Timeout)
	if err != nil {
		return nil, Result{}, err
	}
	if response.StatusCode >= 300 && response.StatusCode <= 399 && response.Header.Get("Location") != "" {
		return nil, Result{}, fmt.Errorf("redirect:%s", response.Header.Get("Location"))
	}
	if !acceptedStatus(response.StatusCode) {
		return nil, Result{}, fmt.Errorf("payload rejected with HTTP %d", response.StatusCode)
	}
	ws := response.StatusCode == http.StatusSwitchingProtocols && strings.EqualFold(response.Header.Get("Upgrade"), "websocket")
	if ws {
		if err := validateAccept(response.Header.Get("Sec-WebSocket-Accept"), key); err != nil {
			return nil, Result{}, err
		}
		conn = newWebSocketConn(conn)
	}
	return conn, Result{StatusCode: response.StatusCode, Host: cfg.TargetHost, Path: "/", WebSocket: ws}, nil
}

func acceptedStatus(code int) bool {
	return code == 101 || code == 200 || code == 201 || code == 202 || code == 204 || code == 206
}
func dial(ctx context.Context, cfg Config, host string, port int, useTLS bool, serverName string) (net.Conn, error) {
	c, err := (&net.Dialer{Timeout: cfg.Timeout}).DialContext(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return nil, err
	}
	if !useTLS {
		return c, nil
	}
	s := serverName
	if s == "" {
		s = host
	}
	t := tls.Client(c, &tls.Config{ServerName: s, MinVersion: tls.VersionTLS12})
	if err = t.HandshakeContext(ctx); err != nil {
		c.Close()
		return nil, err
	}
	return t, nil
}
func prepareWebSocket(payload string) (string, string) {
	if !strings.Contains(strings.ToLower(payload), "upgrade: websocket") {
		return payload, ""
	}
	for _, l := range strings.Split(payload, "\r\n") {
		if strings.HasPrefix(strings.ToLower(l), "sec-websocket-key:") {
			return payload, strings.TrimSpace(strings.SplitN(l, ":", 2)[1])
		}
	}
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	key := base64.StdEncoding.EncodeToString(b)
	at := strings.Index(payload, "\r\n\r\n")
	add := "\r\nSec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13"
	if at < 0 {
		return payload + add + "\r\n\r\n", key
	}
	return payload[:at] + add + payload[at:], key
}
func writeBlocks(c net.Conn, payload string, timeout time.Duration) error {
	for _, b := range strings.Split(payload, "\r\n\r\n") {
		if strings.TrimSpace(b) == "" {
			continue
		}
		if err := c.SetWriteDeadline(time.Now().Add(timeout)); err != nil {
			return err
		}
		if _, err := io.WriteString(c, b+"\r\n\r\n"); err != nil {
			return err
		}
	}
	return nil
}

type response struct {
	StatusCode int
	Header     http.Header
}

func readFinalResponse(c net.Conn, timeout time.Duration) (response, error) {
	if err := c.SetReadDeadline(time.Now().Add(timeout)); err != nil {
		return response{}, err
	}
	r := bufio.NewReaderSize(c, 8192)
	for {
		b, err := readHeaderBlock(r)
		if err != nil {
			return response{}, err
		}
		p, err := http.ReadResponse(bufio.NewReader(strings.NewReader(b)), nil)
		if err != nil {
			return response{}, err
		}
		x := response{StatusCode: p.StatusCode, Header: p.Header.Clone()}
		if p.StatusCode != http.StatusContinue {
			return x, nil
		}
	}
}
func readHeaderBlock(r *bufio.Reader) (string, error) {
	var b strings.Builder
	for b.Len() < maxHeaderBytes {
		line, err := r.ReadString('\n')
		if err != nil {
			return "", err
		}
		b.WriteString(line)
		if strings.HasSuffix(b.String(), "\r\n\r\n") {
			return b.String(), nil
		}
	}
	return "", errors.New("HTTP response headers exceed limit")
}
func validateAccept(actual, key string) error {
	if key == "" {
		return errors.New("WebSocket request key missing")
	}
	sum := sha1.Sum([]byte(key + webSocketGUID))
	if actual != base64.StdEncoding.EncodeToString(sum[:]) {
		return errors.New("WebSocket Sec-WebSocket-Accept validation failed")
	}
	return nil
}
func rewritePath(payload, path string) string {
	if path == "" || path == "/" {
		return payload
	}
	lines := strings.Split(payload, "\r\n")
	if len(lines) == 0 {
		return payload
	}
	p := strings.Split(lines[0], " ")
	if len(p) >= 3 {
		p[1] = path
		lines[0] = strings.Join(p, " ")
	}
	return strings.Join(lines, "\r\n")
}
func sameHost(location, host string) bool {
	u, e := url.Parse(location)
	return e == nil && (u.Hostname() == "" || strings.EqualFold(u.Hostname(), host))
}
func resolveRedirectURL(location, host string, port int, path string, tlsOn bool) (string, int, string, bool, error) {
	scheme := "http"
	if tlsOn {
		scheme = "https"
	}
	base, _ := url.Parse(fmt.Sprintf("%s://%s:%d%s", scheme, host, port, path))
	u, e := base.Parse(location)
	if e != nil {
		return "", 0, "", false, e
	}
	h := u.Hostname()
	if h == "" {
		h = host
	}
	p := u.Port()
	if p == "" {
		if strings.EqualFold(u.Scheme, "https") {
			p = "443"
		} else {
			p = "80"
		}
	}
	q := u.EscapedPath()
	if q == "" {
		q = "/"
	}
	if u.RawQuery != "" {
		q += "?" + u.RawQuery
	}
	n, _ := strconv.Atoi(p)
	return h, n, q, strings.EqualFold(u.Scheme, "https"), nil
}
