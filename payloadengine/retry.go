package payloadengine

import (
	"context"
	"errors"
	"fmt"
	"net"
	"strings"
	"time"
)

// RetryPolicy controls transient carrier retries. It is deliberately bounded;
// it never retries a 4xx authorization response indefinitely and never treats a
// redirect/error page as an SSH stream.
type RetryPolicy struct {
	MaxAttempts int
	InitialWait time.Duration
	MaxWait     time.Duration
}

func (p RetryPolicy) normalized() RetryPolicy {
	if p.MaxAttempts <= 0 || p.MaxAttempts > 10 {
		p.MaxAttempts = 4
	}
	if p.InitialWait <= 0 {
		p.InitialWait = 500 * time.Millisecond
	}
	if p.MaxWait <= 0 || p.MaxWait < p.InitialWait {
		p.MaxWait = 8 * time.Second
	}
	return p
}

func OpenWithRetry(ctx context.Context, cfg Config, policy RetryPolicy) (net.Conn, Result, error) {
	policy = policy.normalized()
	var last error
	wait := policy.InitialWait
	for attempt := 1; attempt <= policy.MaxAttempts; attempt++ {
		conn, result, err := Open(ctx, cfg)
		if err == nil {
			return conn, result, nil
		}
		last = err
		if !retryableCarrierError(err) || attempt == policy.MaxAttempts {
			break
		}
		timer := time.NewTimer(wait)
		select {
		case <-ctx.Done():
			timer.Stop()
			return nil, Result{}, ctx.Err()
		case <-timer.C:
		}
		wait *= 2
		if wait > policy.MaxWait {
			wait = policy.MaxWait
		}
	}
	return nil, Result{}, fmt.Errorf("payload tunnel failed after retries: %w", last)
}

func retryableCarrierError(err error) bool {
	if err == nil {
		return false
	}
	text := strings.ToLower(err.Error())
	for _, code := range []string{" 401", " 403", " 404", " 407", " 409", " 410", " 413", " 421", " 429"} {
		if strings.Contains(text, code) {
			return false
		}
	}
	for _, code := range []string{" 500", " 502", " 503", " 504", " 521", " 522", " 523", " 524"} {
		if strings.Contains(text, code) {
			return true
		}
	}
	var netErr net.Error
	return errors.As(err, &netErr) && (netErr.Timeout() || netErr.Temporary()) || strings.Contains(text, "connection reset") || strings.Contains(text, "eof")
}
