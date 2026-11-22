//go:build linux

package sshtunnel

import "syscall"

// tcpFastOpenConnect is TCP_FASTOPEN_CONNECT (Linux 4.11+, including Android).
// With it set, connect() returns immediately and the SYN is sent together with
// the first write, saving a round trip on the carrier or SSH handshake.
const tcpFastOpenConnect = 30

// tcpFastOpenControl is a net.Dialer.Control hook that turns on client-side TCP
// Fast Open. It is deliberately best effort: an old kernel, a restrictive
// SELinux policy or a disabled net.ipv4.tcp_fastopen client bit simply leave
// the socket on a normal three-way handshake, so errors are ignored.
func tcpFastOpenControl(network, address string, c syscall.RawConn) error {
	_ = c.Control(func(fd uintptr) {
		_ = syscall.SetsockoptInt(int(fd), syscall.IPPROTO_TCP, tcpFastOpenConnect, 1)
	})
	return nil
}
