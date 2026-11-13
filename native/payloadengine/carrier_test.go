package payloadengine

import (
	"net/http"
	"strings"
	"testing"
)

func TestRenderPayload(t *testing.T) {
	got := RenderPayload("GET /[path] HTTP/1.1[crlf]Host: [host][crlf]X-SNI: [sni][crlf][crlf]", "edge.example", 443, "/health?q=1", "front.example")
	want := "GET /health?q=1 HTTP/1.1\r\nHost: edge.example\r\nX-SNI: front.example\r\n\r\n"
	if got != want {
		t.Fatalf("rendered payload mismatch: %q", got)
	}
}

func TestRenderPayloadRepairsWebSocketHeaders(t *testing.T) {
	got := RenderPayload("GET /ws HTTP/1.1[crlf]Host: crlf]User-Agent: [ua][crlf]Upgrade: websocket[crlf][crlf]", "front.example", 80, "/ws", "front.example")
	for _, required := range []string{"Host: front.example", "User-Agent: Mozilla/5.0", "Connection: Upgrade", "Upgrade: websocket", "Sec-WebSocket-Version: 13"} {
		if !strings.Contains(got, required) {
			t.Fatalf("missing %q in normalized payload: %q", required, got)
		}
	}
}

func TestWebSocketAcceptValidation(t *testing.T) {
	if err := validateAccept("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", "dGhlIHNhbXBsZSBub25jZQ=="); err != nil {
		t.Fatal(err)
	}
	if err := validateAccept("wrong", "dGhlIHNhbXBsZSBub25jZQ=="); err == nil {
		t.Fatal("expected invalid accept to fail")
	}
}

func TestResolveRedirect(t *testing.T) {
	host, port, path, tlsOn, err := resolveRedirectURL("https://edge.example/next?x=1", "edge.example", 80, "/start", false)
	if err != nil {
		t.Fatal(err)
	}
	if host != "edge.example" || port != 443 || path != "/next?x=1" || !tlsOn {
		t.Fatalf("unexpected redirect: %s:%d%s tls=%t", host, port, path, tlsOn)
	}
}

func TestHeaderReplacerNormalizesRedirectMetadata(t *testing.T) {
	result := (HeaderReplacer{RewriteRedirectStatus: true}).Apply(http.StatusMovedPermanently, http.Header{"Location": []string{"https://edge.example/ws"}})
	if !result.Rewritten || result.Status != http.StatusOK || result.OriginalStatus != http.StatusMovedPermanently {
		t.Fatalf("unexpected rewritten response: %+v", result)
	}
	if result.Location != "https://edge.example/ws" {
		t.Fatalf("redirect location was lost: %q", result.Location)
	}
}

func TestHeaderReplacerDoesNotRewriteWithoutLocation(t *testing.T) {
	result := (HeaderReplacer{RewriteRedirectStatus: true}).Apply(http.StatusMovedPermanently, http.Header{})
	if result.Rewritten || result.Status != http.StatusMovedPermanently {
		t.Fatalf("redirect without a location must not be normalized: %+v", result)
	}
}

func TestHeaderReplacerLeaves4xxUnchanged(t *testing.T) {
	result := (HeaderReplacer{RewriteRedirectStatus: true}).Apply(http.StatusForbidden, http.Header{})
	if result.Rewritten || result.Status != http.StatusForbidden {
		t.Fatalf("4xx response must remain unchanged: %+v", result)
	}
}
