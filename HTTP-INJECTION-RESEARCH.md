# HTTP injection layer for SSH profiles

V2-TUN keeps JSch for SSH authentication and `direct-tcpip` forwarding, but JSch itself is an SSH library rather than a WebSocket or HTTP-injection library. Its custom `SocketFactory` is the correct extension point for preparing the TCP stream before the SSH handshake.

The new `HttpInjectTransport` sits inside that socket factory. It supports the payload patterns used by SSH-over-HTTP tools without adding WS/WSS fields to the editor. It expands `[host]`, `[host_port]`, `[port]`, `[path]`, `[proxy_host]`, `[proxy_port]`, `[proxy_auth]`, `[sni]`, `[front_domain]`, `[protocol]`, `[crlf]`, and `[lf]`. It sends multi-block payload flows, handles `100 Continue`, accepts normal successful HTTP responses, accepts `101 Switching Protocols`, follows bounded HTTP redirects through `Location` while preserving the redirect path and query, and rejects failed HTTP responses before JSch starts SSH.

When the accepted response is a real `101` WebSocket upgrade with `Upgrade: websocket`, the adapter uses masked client binary frames, reads server binary/continuation frames, and answers server ping frames with pong frames before exposing the stream to JSch. This is different from the raw HTTP payload mode, where the post-header stream is passed directly to SSH. A 3xx response is never treated as a working tunnel and is never rewritten locally into a false 200 response. The transport follows `Location`, retries the payload, and only hands the socket to JSch after a final 2xx or 101 response. The editor remains intentionally simple: Payload and TLS are toggles, while the payload controls the HTTP method, path, headers, Upgrade request, and other carrier-specific details.

This design is based on the public reference implementation `Custom-Internet`, which describes three modes—direct, HTTP payload, and SNI-fronted—and leaves the upgraded socket in a ready-for-SSH state. The HTTP `101` semantics follow MDN’s description: the server agrees to switch protocols and identifies the selected protocol with the `Upgrade` response header. HTTP Injector’s public product description confirms the relevant product-level feature set includes SSH, SSL/TLS, WebSocket, and a payload generator, but it does not publish the proprietary implementation details.

The importer also accepts common JSON SSH aliases such as `server`, `sshServer`, `username`, `sshUsername`, `password`, `payload`, `customPayload`, `proxy`, `remoteProxy`, `tls`, `ssl`, and `sni`. This helps with JSON-based exports, but it does not claim to decrypt or parse every proprietary Dark Tunnel binary or encrypted export format.

This adapter cannot guarantee compatibility with every carrier, CDN, or private server payload without testing against that endpoint. Redirects and header replacement are implemented generically; server-specific authentication, cookies, proprietary replacement syntax, or nonstandard framing may still require a matching payload.

## References

- [1] https://github.com/tavgar/Custom-Internet "Public SSH-over-WebSocket and HTTP payload reference implementation"
- [2] https://raw.githubusercontent.com/tavgar/Custom-Internet/main/ws_tunnel.py "Reference payload expansion and handshake flow"
- [3] https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Status/101 "HTTP 101 Switching Protocols semantics"
- [4] https://play.google.com/store/apps/details?id=com.evozi.injector&hl=en_US "HTTP Injector public feature description"
- [5] https://github.com/mwiede/jsch "Maintained JSch fork and SocketFactory-based SSH integration"
