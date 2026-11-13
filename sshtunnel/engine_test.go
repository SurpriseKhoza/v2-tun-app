package sshtunnel

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"net"
	"testing"

	golangssh "golang.org/x/crypto/ssh"
)

func TestStartValidatesRequiredSSHFields(t *testing.T) {
	if _, err := Start(nil, Config{}); err == nil {
		t.Fatal("expected missing SSH fields to fail")
	}
}

func TestReadSocksConnectDomain(t *testing.T) {
	server, client := net.Pipe()
	defer server.Close()
	defer client.Close()
	go func() {
		_, _ = client.Write([]byte{5, 1, 0, 3, 11})
		_, _ = client.Write([]byte("example.com"))
		_, _ = client.Write([]byte{1, 187})
	}()
	host, port, err := readSocksConnect(server)
	if err != nil {
		t.Fatal(err)
	}
	if host != "example.com" || port != 443 {
		t.Fatalf("unexpected SOCKS target: %s:%d", host, port)
	}
}

func TestHostKeyFingerprint(t *testing.T) {
	key, err := golangssh.NewSignerFromKey(testSignerKey(t))
	if err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(key.PublicKey().Marshal())
	fingerprint := "SHA256:" + base64.RawStdEncoding.EncodeToString(sum[:])
	callback := hostKeyCallback(Config{HostKeySHA256: fingerprint})
	if err := callback("ssh.example", nil, key.PublicKey()); err != nil {
		t.Fatal(err)
	}
	bad := hostKeyCallback(Config{HostKeySHA256: "SHA256:invalid"})
	if err := bad("ssh.example", nil, key.PublicKey()); err == nil {
		t.Fatal("expected fingerprint mismatch")
	}
}

func testSignerKey(t *testing.T) any {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 1024)
	if err != nil {
		t.Fatal(err)
	}
	return key
}
