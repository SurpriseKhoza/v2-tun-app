package sshtunnel

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"strconv"
	"sync"
	"time"
)

// SOCKS5 commands and reply codes used by the bridge.
const (
	socksCmdConnect      = 1
	socksCmdUDPAssociate = 3

	socksReplySucceeded           = 0
	socksReplyGeneralFailure      = 1
	socksReplyHostUnreachable     = 4
	socksReplyCommandNotSupported = 7

	// socksUDPHeaderMin is RSV(2) + FRAG(1) + ATYP(1).
	socksUDPHeaderMin = 4
)

// BadVPN udpgw packet flags.
const (
	udpgwFlagKeepalive byte = 1 << 0
	udpgwFlagRebind    byte = 1 << 1
	udpgwFlagIPv6      byte = 1 << 3
)

const (
	// udpgwHeaderLen is flags(1) + conid(2).
	udpgwHeaderLen = 3
	// udpgwMaxFrame is the largest value the 16-bit frame length can carry.
	udpgwMaxFrame = 0xFFFF
	// udpgwMaxConnections bounds the destination -> conid table. The udpgw
	// daemon evicts its oldest connection when a client opens too many, so a
	// reset here only costs a fresh server-side socket per destination.
	udpgwMaxConnections = 512
	udpgwKeepaliveEvery = 15 * time.Second
	udpDomainResolveTTL = 5 * time.Second
)

// socksReply builds a SOCKS5 reply. bnd is the bound UDP relay address for a
// successful UDP ASSOCIATE; it is zeroed for every other reply.
func socksReply(code byte, bnd *net.UDPAddr) []byte {
	reply := []byte{5, code, 0, 1, 0, 0, 0, 0, 0, 0}
	if bnd != nil {
		if ip4 := bnd.IP.To4(); ip4 != nil {
			copy(reply[4:8], ip4)
		}
		binary.BigEndian.PutUint16(reply[8:10], uint16(bnd.Port))
	}
	return reply
}

// encodeUDPGWPacket builds one framed BadVPN udpgw packet:
//
//	len(u16 LE) | flags(u8) | conid(u16 LE) | addr(4|16) | port(u16 BE) | payload
//
// len counts everything after itself. The address and port are the datagram's
// destination, which the udpgw daemon uses as the UDP target.
func encodeUDPGWPacket(flags byte, conID uint16, ip net.IP, port uint16, payload []byte) ([]byte, error) {
	addr := ip.To4()
	if addr == nil {
		addr = ip.To16()
		flags |= udpgwFlagIPv6
	}
	if addr == nil {
		return nil, errors.New("invalid UDP destination address")
	}
	total := udpgwHeaderLen + len(addr) + 2 + len(payload)
	if total > udpgwMaxFrame {
		return nil, errors.New("UDP datagram too large for udpgw")
	}
	buf := make([]byte, 2+total)
	binary.LittleEndian.PutUint16(buf[0:2], uint16(total))
	buf[2] = flags
	binary.LittleEndian.PutUint16(buf[3:5], conID)
	n := copy(buf[5:], addr)
	binary.BigEndian.PutUint16(buf[5+n:], port)
	copy(buf[5+n+2:], payload)
	return buf, nil
}

// encodeUDPGWKeepalive builds the header-only keepalive frame.
func encodeUDPGWKeepalive() []byte {
	buf := make([]byte, 2+udpgwHeaderLen)
	binary.LittleEndian.PutUint16(buf[0:2], udpgwHeaderLen)
	buf[2] = udpgwFlagKeepalive
	return buf
}

type udpgwPacket struct {
	flags   byte
	conID   uint16
	ip      net.IP
	port    uint16
	payload []byte
}

// readUDPGWPacket reads one frame. Keepalive frames come back with a nil ip.
func readUDPGWPacket(r io.Reader) (udpgwPacket, error) {
	var lenBuf [2]byte
	if _, err := io.ReadFull(r, lenBuf[:]); err != nil {
		return udpgwPacket{}, err
	}
	size := int(binary.LittleEndian.Uint16(lenBuf[:]))
	if size < udpgwHeaderLen {
		return udpgwPacket{}, errors.New("udpgw frame too short")
	}
	body := make([]byte, size)
	if _, err := io.ReadFull(r, body); err != nil {
		return udpgwPacket{}, err
	}
	pkt := udpgwPacket{flags: body[0], conID: binary.LittleEndian.Uint16(body[1:3])}
	if pkt.flags&udpgwFlagKeepalive != 0 {
		return pkt, nil
	}
	addrLen := 4
	if pkt.flags&udpgwFlagIPv6 != 0 {
		addrLen = 16
	}
	if size < udpgwHeaderLen+addrLen+2 {
		return udpgwPacket{}, errors.New("udpgw frame missing address")
	}
	pkt.ip = net.IP(append([]byte(nil), body[3:3+addrLen]...))
	pkt.port = binary.BigEndian.Uint16(body[3+addrLen : 3+addrLen+2])
	pkt.payload = body[3+addrLen+2:]
	return pkt, nil
}

// parseSocksUDP decodes a SOCKS5 UDP request datagram:
// RSV(2) FRAG(1) ATYP ADDR PORT DATA. Fragmented datagrams are rejected.
func parseSocksUDP(b []byte) (host string, ip net.IP, port uint16, payload []byte, err error) {
	if len(b) < socksUDPHeaderMin || b[0] != 0 || b[1] != 0 {
		return "", nil, 0, nil, errors.New("invalid SOCKS5 UDP header")
	}
	if b[2] != 0 {
		return "", nil, 0, nil, errors.New("fragmented SOCKS5 UDP datagrams are not supported")
	}
	rest := b[4:]
	switch b[3] {
	case 1:
		if len(rest) < 4+2 {
			return "", nil, 0, nil, errors.New("short SOCKS5 UDP datagram")
		}
		ip = net.IP(append([]byte(nil), rest[:4]...))
		rest = rest[4:]
	case 4:
		if len(rest) < 16+2 {
			return "", nil, 0, nil, errors.New("short SOCKS5 UDP datagram")
		}
		ip = net.IP(append([]byte(nil), rest[:16]...))
		rest = rest[16:]
	case 3:
		if len(rest) < 1 || int(rest[0]) == 0 || len(rest) < 1+int(rest[0])+2 {
			return "", nil, 0, nil, errors.New("short SOCKS5 UDP datagram")
		}
		n := int(rest[0])
		host = string(rest[1 : 1+n])
		rest = rest[1+n:]
	default:
		return "", nil, 0, nil, errors.New("unsupported SOCKS5 UDP address type")
	}
	port = binary.BigEndian.Uint16(rest[:2])
	return host, ip, port, rest[2:], nil
}

// buildSocksUDP wraps a reply payload in a SOCKS5 UDP header naming the remote
// peer it came from.
func buildSocksUDP(ip net.IP, port uint16, payload []byte) []byte {
	addr := ip.To4()
	atyp := byte(1)
	if addr == nil {
		addr = ip.To16()
		atyp = 4
	}
	buf := make([]byte, 0, 4+len(addr)+2+len(payload))
	buf = append(buf, 0, 0, 0, atyp)
	buf = append(buf, addr...)
	buf = append(buf, byte(port>>8), byte(port))
	return append(buf, payload...)
}

// udpRelay shuttles datagrams between a loopback UDP socket (Xray's SOCKS5 UDP
// client) and one udpgw channel on the SSH server.
type udpRelay struct {
	pc      net.PacketConn
	remote  net.Conn
	resolve func(host string) (net.IP, error)

	writeMu sync.Mutex // frames must not interleave on the udpgw stream

	mu     sync.Mutex
	client net.Addr
	conIDs map[string]uint16
	next   uint16

	done chan struct{}
	once sync.Once
}

func newUDPRelay(pc net.PacketConn, remote net.Conn, resolve func(string) (net.IP, error)) *udpRelay {
	return &udpRelay{
		pc: pc, remote: remote, resolve: resolve,
		conIDs: make(map[string]uint16), done: make(chan struct{}),
	}
}

func (r *udpRelay) stop() {
	r.once.Do(func() {
		close(r.done)
		_ = r.pc.Close()
		_ = r.remote.Close()
	})
}

// conID returns the udpgw connection id for a destination, allocating one on
// first use. The boolean reports a first use so the caller can set REBIND.
func (r *udpRelay) conID(ip net.IP, port uint16) (uint16, bool) {
	key := net.JoinHostPort(ip.String(), strconv.Itoa(int(port)))
	r.mu.Lock()
	defer r.mu.Unlock()
	if id, ok := r.conIDs[key]; ok {
		return id, false
	}
	if len(r.conIDs) >= udpgwMaxConnections {
		r.conIDs = make(map[string]uint16)
	}
	id := r.next
	r.next++
	r.conIDs[key] = id
	return id, true
}

func (r *udpRelay) write(frame []byte) error {
	r.writeMu.Lock()
	defer r.writeMu.Unlock()
	_, err := r.remote.Write(frame)
	return err
}

// clientToRemote forwards datagrams from Xray to the udpgw daemon.
func (r *udpRelay) clientToRemote() {
	buf := make([]byte, 65535)
	for {
		n, from, err := r.pc.ReadFrom(buf)
		if err != nil {
			r.stop()
			return
		}
		udpFrom, ok := from.(*net.UDPAddr)
		if !ok || !udpFrom.IP.IsLoopback() {
			continue
		}
		r.mu.Lock()
		if r.client == nil {
			r.client = from
		} else if r.client.String() != from.String() {
			r.mu.Unlock()
			continue
		}
		r.mu.Unlock()

		host, ip, port, payload, err := parseSocksUDP(buf[:n])
		if err != nil {
			continue
		}
		if ip == nil {
			if ip, err = r.resolve(host); err != nil {
				continue
			}
		}
		id, first := r.conID(ip, port)
		var flags byte
		if first {
			flags |= udpgwFlagRebind
		}
		frame, err := encodeUDPGWPacket(flags, id, ip, port, payload)
		if err != nil {
			continue
		}
		if err := r.write(frame); err != nil {
			r.stop()
			return
		}
	}
}

// remoteToClient forwards replies from the udpgw daemon back to Xray.
func (r *udpRelay) remoteToClient() {
	for {
		pkt, err := readUDPGWPacket(r.remote)
		if err != nil {
			r.stop()
			return
		}
		if pkt.flags&udpgwFlagKeepalive != 0 || pkt.ip == nil {
			continue
		}
		r.mu.Lock()
		client := r.client
		r.mu.Unlock()
		if client == nil {
			continue
		}
		_, _ = r.pc.WriteTo(buildSocksUDP(pkt.ip, pkt.port, pkt.payload), client)
	}
}

// keepalive keeps the SSH channel from looking idle to carriers and proxies.
func (r *udpRelay) keepalive() {
	ticker := time.NewTicker(udpgwKeepaliveEvery)
	defer ticker.Stop()
	for {
		select {
		case <-r.done:
			return
		case <-ticker.C:
			if err := r.write(encodeUDPGWKeepalive()); err != nil {
				r.stop()
				return
			}
		}
	}
}

func resolveUDPHost(host string) (net.IP, error) {
	ctx, cancel := context.WithTimeout(context.Background(), udpDomainResolveTTL)
	defer cancel()
	ips, err := net.DefaultResolver.LookupIPAddr(ctx, host)
	if err != nil {
		return nil, err
	}
	var v6 net.IP
	for _, candidate := range ips {
		if candidate.IP.To4() != nil {
			return candidate.IP, nil
		}
		if v6 == nil {
			v6 = candidate.IP
		}
	}
	if v6 == nil {
		return nil, errors.New("no address for " + host)
	}
	return v6, nil
}

// handleUDPAssociate serves SOCKS5 UDP ASSOCIATE by relaying through udpgw. The
// association lives exactly as long as the SOCKS control connection, as RFC 1928
// requires.
func (s *Session) handleUDPAssociate(conn net.Conn) {
	if s.cfg.UDPGWPort <= 0 {
		_, _ = conn.Write(socksReply(socksReplyCommandNotSupported, nil))
		return
	}
	remote, err := s.dialRemote("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(s.cfg.UDPGWPort)))
	if err != nil {
		// Most often no udpgw daemon is listening on the server.
		_, _ = conn.Write(socksReply(socksReplyHostUnreachable, nil))
		return
	}
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		_ = remote.Close()
		_, _ = conn.Write(socksReply(socksReplyGeneralFailure, nil))
		return
	}
	relay := newUDPRelay(pc, remote, resolveUDPHost)
	defer relay.stop()

	bnd, _ := pc.LocalAddr().(*net.UDPAddr)
	if _, err := conn.Write(socksReply(socksReplySucceeded, bnd)); err != nil {
		return
	}
	_ = conn.SetDeadline(time.Time{})

	go relay.clientToRemote()
	go relay.remoteToClient()
	go relay.keepalive()

	controlClosed := make(chan struct{})
	go func() {
		_, _ = io.Copy(io.Discard, conn)
		close(controlClosed)
	}()
	select {
	case <-controlClosed:
	case <-relay.done:
	case <-s.ctx.Done():
	}
}
