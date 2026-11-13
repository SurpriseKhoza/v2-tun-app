package payloadengine

import (
	"bufio"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"sync"
)

type webSocketConn struct {
	net.Conn
	in     *bufio.Reader
	mu     sync.Mutex
	frame  []byte
	offset int
	closed bool
}

func newWebSocketConn(conn net.Conn) net.Conn {
	return &webSocketConn{Conn: conn, in: bufio.NewReaderSize(conn, 32*1024)}
}

func (c *webSocketConn) Read(p []byte) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	for len(c.frame) == c.offset {
		if err := c.nextMessage(); err != nil {
			return 0, err
		}
	}
	n := copy(p, c.frame[c.offset:])
	c.offset += n
	return n, nil
}

func (c *webSocketConn) Write(p []byte) (int, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return 0, net.ErrClosed
	}
	if err := writeFrame(c.Conn, 0x82, p); err != nil {
		return 0, err
	}
	return len(p), nil
}

func (c *webSocketConn) Close() error {
	c.mu.Lock()
	if !c.closed {
		c.closed = true
		_ = writeFrame(c.Conn, 0x88, nil)
	}
	c.mu.Unlock()
	return c.Conn.Close()
}

func (c *webSocketConn) nextMessage() error {
	var message []byte
	messageOpcode := byte(0)
	for {
		first, err := c.in.ReadByte()
		if err != nil {
			return err
		}
		second, err := c.in.ReadByte()
		if err != nil {
			return err
		}
		fin := first&0x80 != 0
		opcode := first & 0x0f
		length, err := readLength(c.in, second&0x7f)
		if err != nil {
			return err
		}
		if length > maxFrameBytes {
			return errors.New("WebSocket frame is too large")
		}
		if opcode >= 8 && (!fin || length > 125) {
			return errors.New("invalid WebSocket control frame")
		}
		masked := second&0x80 != 0
		var mask [4]byte
		if masked {
			if _, err := io.ReadFull(c.in, mask[:]); err != nil {
				return err
			}
		}
		data := make([]byte, int(length))
		if _, err := io.ReadFull(c.in, data); err != nil {
			return err
		}
		if masked {
			for i := range data {
				data[i] ^= mask[i%4]
			}
		}
		switch opcode {
		case 0x8:
			_ = writeFrame(c.Conn, 0x88, data[:min(len(data), 125)])
			return errors.New("WebSocket closed by peer")
		case 0x9:
			if err := writeControl(c.Conn, 0xA, data); err != nil {
				return err
			}
			continue
		case 0xA:
			continue
		case 0x1, 0x2:
			if messageOpcode != 0 {
				return errors.New("nested WebSocket data frame")
			}
			messageOpcode = opcode
		case 0x0:
			if messageOpcode == 0 {
				return errors.New("unexpected WebSocket continuation")
			}
		default:
			return errors.New("unsupported WebSocket opcode")
		}
		message = append(message, data...)
		if fin {
			c.frame, c.offset = message, 0
			return nil
		}
	}
}

func readLength(in *bufio.Reader, value byte) (uint64, error) {
	switch value {
	case 126:
		var b [2]byte
		if _, err := io.ReadFull(in, b[:]); err != nil {
			return 0, err
		}
		return uint64(binary.BigEndian.Uint16(b[:])), nil
	case 127:
		var b [8]byte
		if _, err := io.ReadFull(in, b[:]); err != nil {
			return 0, err
		}
		return binary.BigEndian.Uint64(b[:]), nil
	default:
		return uint64(value), nil
	}
}

func writeControl(conn net.Conn, opcode byte, data []byte) error {
	if len(data) > 125 {
		return errors.New("control frame too large")
	}
	return writeFrame(conn, 0x80|opcode, data)
}
func writeFrame(conn net.Conn, first byte, data []byte) error {
	var header [10]byte
	header[0] = first
	n := 2
	if len(data) < 126 {
		header[1] = byte(len(data))
	} else if len(data) <= 65535 {
		header[1] = 126
		binary.BigEndian.PutUint16(header[2:4], uint16(len(data)))
		n = 4
	} else {
		header[1] = 127
		binary.BigEndian.PutUint64(header[2:10], uint64(len(data)))
		n = 10
	}
	if _, err := conn.Write(header[:n]); err != nil {
		return err
	}
	_, err := conn.Write(data)
	return err
}
func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}
