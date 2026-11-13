package payloadengine

import (
	"net/http"
	"strconv"
)

// HeaderReplacer provides compatibility metadata for HTTP-injection diagnostics.
// It never changes bytes on the wire and never turns an HTML redirect body into
// an SSH stream. Redirects must still be followed and renegotiated normally.
type HeaderReplacer struct {
	RewriteRedirectStatus bool
}

type RewrittenResponse struct {
	OriginalStatus int
	Status         int
	Location       string
	Rewritten      bool
}

func (r HeaderReplacer) Apply(status int, headers http.Header) RewrittenResponse {
	result := RewrittenResponse{OriginalStatus: status, Status: status, Location: headers.Get("Location")}
	if r.RewriteRedirectStatus && status >= 300 && status <= 399 && result.Location != "" {
		result.Status = http.StatusOK
		result.Rewritten = true
	}
	return result
}

func (r RewrittenResponse) String() string { return strconv.Itoa(r.Status) }
