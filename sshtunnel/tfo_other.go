//go:build !linux

package sshtunnel

import "syscall"

// tcpFastOpenControl is a no-op on platforms without TCP_FASTOPEN_CONNECT.
func tcpFastOpenControl(network, address string, c syscall.RawConn) error {
	return nil
}
