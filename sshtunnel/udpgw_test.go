package sshtunnel

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"io"
	"net"
	"testing"
	"time"
)

func TestConfigDecodesUDPGWAndFastOpen(t *testing.T) {
	var cfg Config
	raw := `{"server":"s","username":"u","udpGwPort":7300,"tcpFastOpen":true}`
	if err := json.Unmarshal([]byte(raw), &cfg); err != nil {
		t.Fatal(err)
	}
	if cfg.UDPGWPort != 7300 || !cfg.TCPFastOpen {
		t.Fatalf("udpGwPort/tcpFastOpen were not decoded: %+v", cfg)
	}
}

func TestUDPGWPacketRoundTripIPv4(t *testing.T) {
	frame, err := encodeUDPGWPacket(udpgwFlagRebind, 0x0102, net.ParseIP("203.0.113.9"), 5353, []byte("hello"))
	if err != nil {
		t.Fatal(err)
	}
	// Layout: len(LE) flags conid(LE) ip port(BE) payload.
	if got := binary.LittleEndian.Uint16(frame[:2]); int(got) != len(frame)-2 {
		t.Fatalf("length prefix %d does not match body %d", got, len(frame)-2)
	}
	if frame[2] != udpgwFlagRebind || frame[3] != 0x02 || frame[4] != 0x01 {
		t.Fatalf("flags/conid wrong: % x", frame[:5])
	}
	if !bytes.Equal(frame[5:9], []byte{203, 0, 113, 9}) || frame[9] != 0x14 || frame[10] != 0xe9 {
		t.Fatalf("address/port wrong (port must be big-endian): % x", frame[5:11])
	}
	pkt, err := readUDPGWPacket(bytes.NewReader(frame))
	if err != nil {
		t.Fatal(err)
	}
	if pkt.conID != 0x0102 || !pkt.ip.Equal(net.ParseIP("203.0.113.9")) || pkt.port != 5353 || string(pkt.payload) != "hello" {
		t.Fatalf("round trip mismatch: %+v", pkt)
	}
}

func TestUDPGWPacketRoundTripIPv6(t *testing.T) {
	ip := net.ParseIP("2001:db8::53")
	frame, err := encodeUDPGWPacket(0, 7, ip, 443, []byte{1, 2, 3})
	if err != nil {
		t.Fatal(err)
	}
	if frame[2]&udpgwFlagIPv6 == 0 {
		t.Fatal("IPv6 flag missing")
	}
	pkt, err := readUDPGWPacket(bytes.NewReader(frame))
	if err != nil {
		t.Fatal(err)
	}
	if !pkt.ip.Equal(ip) || pkt.port != 443 || !bytes.Equal(pkt.payload, []byte{1, 2, 3}) {
		t.Fatalf("round trip mismatch: %+v", pkt)
	}
}

func TestUDPGWKeepaliveAndOversize(t *testing.T) {
	pkt, err := readUDPGWPacket(bytes.NewReader(encodeUDPGWKeepalive()))
	if err != nil || pkt.flags&udpgwFlagKeepalive == 0 || pkt.ip != nil {
		t.Fatalf("keepalive not parsed: %+v %v", pkt, err)
	}
	if _, err := encodeUDPGWPacket(0, 1, net.ParseIP("1.2.3.4"), 1, make([]byte, 70000)); err == nil {
		t.Fatal("expected oversize datagram to be rejected")
	}
}

func TestParseAndBuildSocksUDP(t *testing.T) {
	dg := append([]byte{0, 0, 0, 1, 8, 8, 8, 8, 0, 53}, []byte("q")...)
	host, ip, port, payload, err := parseSocksUDP(dg)
	if err != nil || host != "" || !ip.Equal(net.ParseIP("8.8.8.8")) || port != 53 || string(payload) != "q" {
		t.Fatalf("ipv4 parse: %q %v %d %q %v", host, ip, port, payload, err)
	}
	dom := append([]byte{0, 0, 0, 3, 11}, []byte("example.com")...)
	dom = append(dom, 0x01, 0xbb, 'x')
	host, ip, port, payload, err = parseSocksUDP(dom)
	if err != nil || host != "example.com" || ip != nil || port != 443 || string(payload) != "x" {
		t.Fatalf("domain parse: %q %v %d %q %v", host, ip, port, payload, err)
	}
	if _, _, _, _, err := parseSocksUDP([]byte{0, 0, 1, 1, 1, 1, 1, 1, 0, 1}); err == nil {
		t.Fatal("fragmented datagram must be rejected")
	}
	back := buildSocksUDP(net.ParseIP("8.8.8.8"), 53, []byte("a"))
	if !bytes.Equal(back, []byte{0, 0, 0, 1, 8, 8, 8, 8, 0, 53, 'a'}) {
		t.Fatalf("build: % x", back)
	}
}

func TestTCPFastOpenControlIsBestEffort(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		c, err := ln.Accept()
		if err == nil {
			_, _ = io.Copy(io.Discard, c)
			_ = c.Close()
		}
	}()
	d := &net.Dialer{Timeout: 2 * time.Second, Control: tcpFastOpenControl}
	c, err := d.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatalf("a Fast Open dial must still connect even if the kernel ignores the option: %v", err)
	}
	if _, err := c.Write([]byte("ping")); err != nil {
		t.Fatal(err)
	}
	_ = c.Close()
}

// fakeUDPGW speaks the server side of the udpgw stream: it answers every packet
// with "echo:"+payload from the same destination and conid.
func fakeUDPGW(t *testing.T, conn net.Conn) {
	t.Helper()
	for {
		pkt, err := readUDPGWPacket(conn)
		if err != nil {
			return
		}
		if pkt.flags&udpgwFlagKeepalive != 0 {
			continue
		}
		reply, err := encodeUDPGWPacket(0, pkt.conID, pkt.ip, pkt.port, append([]byte("echo:"), pkt.payload...))
		if err != nil {
			return
		}
		if _, err := conn.Write(reply); err != nil {
			return
		}
	}
}

func newTestSession(port int, dial func(network, address string) (net.Conn, error)) *Session {
	ctx, cancel := context.WithCancel(context.Background())
	return &Session{cfg: Config{UDPGWPort: port, TimeoutMS: 3000}, ctx: ctx, cancel: cancel, dialRemote: dial}
}

func TestUDPAssociateRelaysThroughUDPGW(t *testing.T) {
	var dialed string
	s := newTestSession(7300, func(network, address string) (net.Conn, error) {
		dialed = network + " " + address
		server, client := net.Pipe()
		go fakeUDPGW(t, server)
		return client, nil
	})
	defer s.cancel()

	socksServer, socksClient := net.Pipe()
	defer socksClient.Close()
	go s.handleSocks(socksServer)

	_ = socksClient.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := socksClient.Write([]byte{5, 1, 0}); err != nil {
		t.Fatal(err)
	}
	var method [2]byte
	if _, err := io.ReadFull(socksClient, method[:]); err != nil || method != [2]byte{5, 0} {
		t.Fatalf("greeting: %v %v", method, err)
	}
	if _, err := socksClient.Write([]byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
		t.Fatal(err)
	}
	var reply [10]byte
	if _, err := io.ReadFull(socksClient, reply[:]); err != nil || reply[1] != 0 {
		t.Fatalf("associate reply: % x %v", reply, err)
	}
	relayPort := int(binary.BigEndian.Uint16(reply[8:10]))
	if relayPort == 0 || dialed != "tcp 127.0.0.1:7300" {
		t.Fatalf("relay port %d, udpgw dial %q", relayPort, dialed)
	}

	udp, err := net.DialUDP("udp", nil, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: relayPort})
	if err != nil {
		t.Fatal(err)
	}
	defer udp.Close()
	_ = udp.SetDeadline(time.Now().Add(5 * time.Second))

	for i, msg := range []string{"one", "two"} {
		dg := append([]byte{0, 0, 0, 1, 203, 0, 113, 9, 0x14, 0xe9}, []byte(msg)...)
		if _, err := udp.Write(dg); err != nil {
			t.Fatal(err)
		}
		buf := make([]byte, 2048)
		n, err := udp.Read(buf)
		if err != nil {
			t.Fatalf("datagram %d: no reply: %v", i, err)
		}
		_, ip, port, payload, err := parseSocksUDP(buf[:n])
		if err != nil || !ip.Equal(net.ParseIP("203.0.113.9")) || port != 5353 || string(payload) != "echo:"+msg {
			t.Fatalf("datagram %d reply: %v %d %q %v", i, ip, port, payload, err)
		}
	}
}

func TestUDPAssociateDisabledAndUnreachable(t *testing.T) {
	cases := []struct {
		name string
		port int
		dial func(string, string) (net.Conn, error)
		want byte
	}{
		{"disabled", 0, nil, 7},
		{"no udpgw daemon", 7300, func(string, string) (net.Conn, error) { return nil, io.ErrClosedPipe }, 4},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			s := newTestSession(tc.port, tc.dial)
			defer s.cancel()
			server, client := net.Pipe()
			defer client.Close()
			go s.handleSocks(server)
			_ = client.SetDeadline(time.Now().Add(5 * time.Second))
			_, _ = client.Write([]byte{5, 1, 0})
			var m [2]byte
			_, _ = io.ReadFull(client, m[:])
			_, _ = client.Write([]byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0})
			var reply [10]byte
			if _, err := io.ReadFull(client, reply[:]); err != nil || reply[1] != tc.want {
				t.Fatalf("want reply code %d, got % x (%v)", tc.want, reply, err)
			}
		})
	}
}

func TestConnectStillWorksAndBindIsRejected(t *testing.T) {
	target, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer target.Close()
	go func() {
		c, err := target.Accept()
		if err == nil {
			_, _ = c.Write([]byte("hi"))
			_ = c.Close()
		}
	}()
	s := newTestSession(0, net.Dial)
	defer s.cancel()

	server, client := net.Pipe()
	defer client.Close()
	go s.handleSocks(server)
	_ = client.SetDeadline(time.Now().Add(5 * time.Second))
	_, _ = client.Write([]byte{5, 1, 0})
	var m [2]byte
	_, _ = io.ReadFull(client, m[:])
	addr := target.Addr().(*net.TCPAddr)
	req := []byte{5, 1, 0, 1, 127, 0, 0, 1, byte(addr.Port >> 8), byte(addr.Port)}
	_, _ = client.Write(req)
	var reply [10]byte
	if _, err := io.ReadFull(client, reply[:]); err != nil || reply[1] != 0 {
		t.Fatalf("connect reply: % x %v", reply, err)
	}
	got := make([]byte, 2)
	if _, err := io.ReadFull(client, got); err != nil || string(got) != "hi" {
		t.Fatalf("tunnelled bytes: %q %v", got, err)
	}

	server2, client2 := net.Pipe()
	defer client2.Close()
	go s.handleSocks(server2)
	_ = client2.SetDeadline(time.Now().Add(5 * time.Second))
	_, _ = client2.Write([]byte{5, 1, 0})
	_, _ = io.ReadFull(client2, m[:])
	_, _ = client2.Write([]byte{5, 2, 0, 1, 0, 0, 0, 0, 0, 0}) // BIND
	if _, err := io.ReadFull(client2, reply[:]); err != nil || reply[1] != 7 {
		t.Fatalf("BIND must be refused with 'command not supported': % x %v", reply, err)
	}
}
