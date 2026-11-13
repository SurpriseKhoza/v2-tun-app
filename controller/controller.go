package controller

import (
	"errors"
	"net"
	"sync"
	"syscall"
)

var errProtectSocket = errors.New("protect Android VPN socket failed")

var (
	protectMu       sync.RWMutex
	socketProtector func(fd uintptr) bool
)

// SetSocketProtector registers the process-wide callback used by auxiliary
// dialers such as the SSH payload carrier. Passing nil clears the callback.
func SetSocketProtector(protector func(fd uintptr) bool) {
	protectMu.Lock()
	socketProtector = protector
	protectMu.Unlock()
}

// ProtectNetConn protects a raw socket when a platform callback has been
// registered. Desktop builds intentionally treat the absent callback as a
// no-op so the same SSH package remains portable.
func ProtectNetConn(conn net.Conn) error {
	protectMu.RLock()
	protector := socketProtector
	protectMu.RUnlock()
	if protector == nil {
		return nil
	}
	raw, ok := conn.(syscall.Conn)
	if !ok {
		return errors.New("connection does not expose a raw socket")
	}
	rawConn, err := raw.SyscallConn()
	if err != nil {
		return err
	}
	var protectErr error
	err = rawConn.Control(func(fd uintptr) {
		if !protector(fd) {
			protectErr = errProtectSocket
		}
	})
	if err != nil {
		return err
	}
	return protectErr
}

func protectSocket(conn syscall.RawConn, controller func(fd uintptr) bool) error {
	var protectErr error
	if err := conn.Control(func(fd uintptr) {
		if !controller(fd) {
			protectErr = errProtectSocket
		}
	}); err != nil {
		return err
	}
	return protectErr
}
