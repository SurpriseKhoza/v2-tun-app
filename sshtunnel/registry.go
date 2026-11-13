package sshtunnel

import (
	"context"
	"fmt"
	"strconv"
	"sync"

	"github.com/xtls/libxray/payloadengine"
)

// Manager owns SSH sessions created through the libXray invoke API.
type Manager struct {
	mu       sync.Mutex
	nextID   uint64
	sessions map[string]*Session
}

func NewManager() *Manager {
	return &Manager{nextID: 1, sessions: make(map[string]*Session)}
}

var DefaultManager = NewManager()

func (m *Manager) Start(ctx context.Context, cfg Config) (string, int, payloadengine.Result, error) {
	session, err := Start(ctx, cfg)
	if err != nil {
		return "", 0, payloadengine.Result{}, err
	}
	m.mu.Lock()
	id := fmt.Sprintf("ssh-%s", strconv.FormatUint(m.nextID, 10))
	m.nextID++
	m.sessions[id] = session
	m.mu.Unlock()
	return id, session.LocalPort(), session.Result(), nil
}

func (m *Manager) Stop(id string) error {
	m.mu.Lock()
	session := m.sessions[id]
	delete(m.sessions, id)
	m.mu.Unlock()
	if session == nil {
		return fmt.Errorf("SSH session %q not found", id)
	}
	return session.Close()
}

func (m *Manager) CloseAll() {
	m.mu.Lock()
	sessions := m.sessions
	m.sessions = make(map[string]*Session)
	m.mu.Unlock()
	for _, session := range sessions {
		_ = session.Close()
	}
}

func (m *Manager) Count() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.sessions)
}
