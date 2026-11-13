// Package sshtunnel provides an additive SSH transport for libXray.
//
// The engine authenticates to an SSH server over a direct TCP socket or the
// payloadengine carrier, then exposes a loopback SOCKS5 listener. Xray can
// route its TCP traffic to that listener through a normal socks outbound.
package sshtunnel

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/xtls/libxray/controller"
	"github.com/xtls/libxray/payloadengine"
	golangssh "golang.org/x/crypto/ssh"
)

const (
	defaultTimeout = 15 * time.Second
	maxSocksName   = 255
)

// Config describes an SSH session and its optional HTTP/WebSocket carrier.
type Config struct {
	Server          string `json:"server"`
	Port            int    `json:"port"`
	Username        string `json:"username"`
	Password        string `json:"password,omitempty"`
	PrivateKey      string `json:"privateKey,omitempty"`
	ProxyHost       string `json:"proxyHost,omitempty"`
	ProxyPort       int    `json:"proxyPort,omitempty"`
	SNI             string `json:"sni,omitempty"`
	TLS             bool   `json:"tls,omitempty"`
	Payload         string `json:"payload,omitempty"`
	TimeoutMS       int    `json:"timeoutMs,omitempty"`
	MaxRedirects    int    `json:"maxRedirects,omitempty"`
	AllowRedirectTo bool   `json:"allowRedirectTo,omitempty"`
	FollowRedirects bool   `json:"followRedirects"`
	// InsecureSkipHostKey must be explicitly enabled when no host-key
	// fingerprint is supplied. It is false by default.
	InsecureSkipHostKey bool   `json:"insecureSkipHostKey,omitempty"`
	HostKeySHA256       string `json:"hostKeySha256,omitempty"`
}

func (c Config) timeout() time.Duration {
	if c.TimeoutMS <= 0 {
		return defaultTimeout
	}
	return time.Duration(c.TimeoutMS) * time.Millisecond
}

// Session owns one SSH connection and its loopback SOCKS5 bridge.
type Session struct {
	cfg       Config
	client    *golangssh.Client
	listener  net.Listener
	ctx       context.Context
	cancel    context.CancelFunc
	closeOnce sync.Once
	result    payloadengine.Result
}

// Start authenticates the SSH server and starts a loopback SOCKS5 listener.
func Start(ctx context.Context, cfg Config) (*Session, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	if cfg.Server == "" || cfg.Username == "" {
		return nil, errors.New("SSH server and username are required")
	}
	if cfg.Port == 0 {
		cfg.Port = 22
	}
	if cfg.Port < 1 || cfg.Port > 65535 {
		return nil, errors.New("SSH port is invalid")
	}
	if cfg.ProxyHost != "" && (cfg.ProxyPort < 1 || cfg.ProxyPort > 65535) {
		return nil, errors.New("payload proxy port is invalid")
	}
	if cfg.FollowRedirects == false && cfg.MaxRedirects == 0 {
		// Preserve the caller's explicit false while keeping the carrier's
		// bounded default behavior when no setting was provided.
		cfg.FollowRedirects = true
	}

	var auth []golangssh.AuthMethod
	if cfg.Password != "" {
		auth = append(auth, golangssh.Password(cfg.Password))
	}
	if cfg.PrivateKey != "" {
		signer, err := golangssh.ParsePrivateKey([]byte(cfg.PrivateKey))
		if err != nil {
			return nil, fmt.Errorf("parse SSH private key: %w", err)
		}
		auth = append(auth, golangssh.PublicKeys(signer))
	}
	if len(auth) == 0 {
		return nil, errors.New("SSH password or private key is required")
	}

	clientConfig := &golangssh.ClientConfig{
		User:            cfg.Username,
		Auth:            auth,
		Timeout:         cfg.timeout(),
		HostKeyCallback: hostKeyCallback(cfg),
	}
	conn, result, err := dialCarrier(ctx, cfg)
	if err != nil {
		return nil, fmt.Errorf("dial SSH carrier: %w", err)
	}
	cc, chans, reqs, err := golangssh.NewClientConn(conn, net.JoinHostPort(cfg.Server, strconv.Itoa(cfg.Port)), clientConfig)
	if err != nil {
		_ = conn.Close()
		return nil, fmt.Errorf("SSH handshake: %w", err)
	}
	client := golangssh.NewClient(cc, chans, reqs)
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		_ = client.Close()
		return nil, fmt.Errorf("start SOCKS listener: %w", err)
	}
	child, cancel := context.WithCancel(ctx)
	s := &Session{cfg: cfg, client: client, listener: listener, ctx: child, cancel: cancel, result: result}
	go s.acceptLoop()
	return s, nil
}

// Result reports the HTTP/payload negotiation that established this session.
func (s *Session) Result() payloadengine.Result { return s.result }

// LocalAddr returns the loopback address Xray should use as a socks outbound.
func (s *Session) LocalAddr() string { return s.listener.Addr().String() }

// LocalPort returns the dynamically allocated loopback SOCKS port.
func (s *Session) LocalPort() int {
	_, port, _ := net.SplitHostPort(s.LocalAddr())
	value, _ := strconv.Atoi(port)
	return value
}

func (s *Session) acceptLoop() {
	for {
		conn, err := s.listener.Accept()
		if err != nil {
			select {
			case <-s.ctx.Done():
			default:
			}
			return
		}
		go s.handleSocks(conn)
	}
}

func (s *Session) handleSocks(conn net.Conn) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(s.cfg.timeout()))
	if err := negotiateSocks5(conn); err != nil {
		return
	}
	host, port, err := readSocksConnect(conn)
	if err != nil {
		return
	}
	remote, err := s.client.Dial("tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		_, _ = conn.Write([]byte{5, 5, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	defer remote.Close()
	_, _ = conn.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0})
	_ = conn.SetDeadline(time.Time{})
	copyDuplex(conn, remote)
}

func negotiateSocks5(conn net.Conn) error {
	var head [2]byte
	if _, err := io.ReadFull(conn, head[:]); err != nil || head[0] != 5 {
		return errors.New("invalid SOCKS5 greeting")
	}
	methods := make([]byte, int(head[1]))
	if _, err := io.ReadFull(conn, methods); err != nil {
		return err
	}
	if _, err := conn.Write([]byte{5, 0}); err != nil {
		return err
	}
	return nil
}

func readSocksConnect(conn net.Conn) (string, int, error) {
	var head [4]byte
	if _, err := io.ReadFull(conn, head[:]); err != nil || head[0] != 5 || head[1] != 1 {
		return "", 0, errors.New("SOCKS5 CONNECT is required")
	}
	var host string
	switch head[3] {
	case 1:
		var b [4]byte
		if _, err := io.ReadFull(conn, b[:]); err != nil {
			return "", 0, err
		}
		host = net.IP(b[:]).String()
	case 3:
		var size [1]byte
		if _, err := io.ReadFull(conn, size[:]); err != nil || size[0] == 0 || int(size[0]) > maxSocksName {
			return "", 0, errors.New("invalid SOCKS5 domain")
		}
		b := make([]byte, int(size[0]))
		if _, err := io.ReadFull(conn, b); err != nil {
			return "", 0, err
		}
		host = string(b)
	case 4:
		b := make([]byte, net.IPv6len)
		if _, err := io.ReadFull(conn, b); err != nil {
			return "", 0, err
		}
		host = net.IP(b).String()
	default:
		return "", 0, errors.New("unsupported SOCKS5 address type")
	}
	var port [2]byte
	if _, err := io.ReadFull(conn, port[:]); err != nil {
		return "", 0, err
	}
	return host, int(port[0])<<8 | int(port[1]), nil
}

func copyDuplex(a, b net.Conn) {
	done := make(chan struct{}, 2)
	go func() { _, _ = io.Copy(a, b); done <- struct{}{} }()
	go func() { _, _ = io.Copy(b, a); done <- struct{}{} }()
	<-done
}

func dialCarrier(ctx context.Context, cfg Config) (net.Conn, payloadengine.Result, error) {
	dial := func(ctx context.Context, network, address string) (net.Conn, error) {
		conn, err := (&net.Dialer{Timeout: cfg.timeout()}).DialContext(ctx, network, address)
		if err != nil {
			return nil, err
		}
		if err := controller.ProtectNetConn(conn); err != nil {
			_ = conn.Close()
			return nil, err
		}
		return conn, nil
	}
	if cfg.ProxyHost == "" {
		conn, err := dial(ctx, "tcp", net.JoinHostPort(cfg.Server, strconv.Itoa(cfg.Port)))
		return conn, payloadengine.Result{Host: cfg.Server, Path: "/"}, err
	}
	conn, result, err := payloadengine.OpenWithRetry(ctx, payloadengine.Config{
		ProxyHost: cfg.ProxyHost, ProxyPort: cfg.ProxyPort,
		TargetHost: cfg.Server, TargetPort: cfg.Port,
		SNI: cfg.SNI, TLS: cfg.TLS, Payload: cfg.Payload,
		Timeout: cfg.timeout(), MaxRedirects: cfg.MaxRedirects,
		AllowRedirectTo: cfg.AllowRedirectTo, FollowRedirects: cfg.FollowRedirects,
		DialContext: dial,
	}, payloadengine.RetryPolicy{MaxAttempts: 4, InitialWait: 500 * time.Millisecond, MaxWait: 8 * time.Second})
	return conn, result, err
}

func hostKeyCallback(cfg Config) golangssh.HostKeyCallback {
	if cfg.InsecureSkipHostKey {
		return golangssh.InsecureIgnoreHostKey()
	}
	if cfg.HostKeySHA256 == "" {
		return func(hostname string, remote net.Addr, key golangssh.PublicKey) error {
			return errors.New("SSH host key verification required: provide hostKeySha256 or enable insecureSkipHostKey")
		}
	}
	return func(hostname string, remote net.Addr, key golangssh.PublicKey) error {
		sum := sha256.Sum256(key.Marshal())
		actual := "SHA256:" + base64.RawStdEncoding.EncodeToString(sum[:])
		if !strings.EqualFold(actual, cfg.HostKeySHA256) {
			return fmt.Errorf("SSH host key fingerprint mismatch for %s", hostname)
		}
		return nil
	}
}

// Close stops the SOCKS bridge and SSH session.
func (s *Session) Close() error {
	var err error
	s.closeOnce.Do(func() {
		s.cancel()
		_ = s.listener.Close()
		err = s.client.Close()
	})
	return err
}
