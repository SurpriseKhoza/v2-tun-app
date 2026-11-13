# How HTTP Injector, HTTP Custom, and DarkTunnel Tunnel SSH

## Main finding

All three apps appear to use the same general architecture for their SSH mode:

1. Create an Android `VpnService` TUN interface that captures device traffic.
2. Establish an SSH client session to a server using username/password or key authentication.
3. Optionally wrap the initial SSH transport in an HTTP payload, HTTP proxy, TLS/SNI connection, or WebSocket upgrade.
4. Request dynamic forwarding from the SSH server, effectively providing a SOCKS-style connection broker.
5. Translate captured device traffic into TCP connections through that local forwarding layer.

The exact internal native libraries and reconnect algorithms are proprietary, so the public evidence supports the transport model and app feature set, not a claim about each app's private implementation.

## The common SSH-over-VPN pipeline

A normal SSH tunnel encrypts arbitrary application traffic inside an authenticated SSH connection. The SSH client can use a dynamic forward, conceptually similar to `ssh -D 1080`, where each local SOCKS connection becomes an SSH `direct-tcpip` channel. The remote SSH server then opens the destination connection. SSH's encrypted channel protects the traffic between the client and SSH server, while the server's own network connection reaches the final destination. [1]

For an Android system-wide VPN, the app adds a second layer. Android's `VpnService.Builder` establishes a local TUN interface and routes selected or all traffic into it. The app reads packets from the TUN file descriptor, sends them through its proxy/tunnel engine, and writes returning packets back. Android requires the VPN app to call `VpnService.protect()` on the tunnel's own socket so that the SSH control connection does not get routed back into the VPN and create a loop. [2]

An open-source Android VPN-over-SSH implementation documents this exact design: connect with an SSH library, create a dynamic port forward and local SOCKS5 server, then use `tun2socks` to send Android TUN traffic to that SOCKS5 listener. It also documents an important limitation: a basic OpenSSH dynamic forward carries TCP, not UDP, unless an additional UDP-capable design is added. [3]

## HTTP Injector

HTTP Injector publicly advertises SSH as one of several independent tunnel technologies, alongside SSL/TLS, DNS tunneling, WebSocket, V2Ray/Xray, Hysteria, Shadowsocks, and WireGuard. It also lists a payload generator, host checker, app filter, DNS changer, and custom server/config support. [4]

Its SSH modes can therefore be understood as combinations of these layers:

- **Direct SSH:** the app opens a protected TCP connection directly to the SSH server, normally on an SSH-compatible port.
- **SSH with HTTP payload or proxy:** before SSH negotiation, the client connects to an HTTP proxy or gateway and sends a user-defined HTTP request/payload. If the gateway accepts the request or upgrades the connection, the resulting byte stream is handed to the SSH client.
- **SSH over TLS/SNI:** the client first establishes TLS to a host selected through the SNI value, then carries the SSH byte stream through the resulting connection or gateway.
- **SSH over WebSocket:** the client sends an HTTP Upgrade request, obtains a WebSocket stream, and runs SSH inside the upgraded stream.

A public reference implementation demonstrates the mechanics of the last two patterns: it creates a direct, HTTP-payload, or TLS/SNI-fronted socket; performs an HTTP/WebSocket handshake; hands the raw socket to an SSH transport; and exposes a local SOCKS4/5 proxy whose connections become SSH `direct-tcpip` channels. [5]

The public listing confirms the available feature families, but it does not prove that every HTTP Injector mode uses exactly the same open-source sequence or the same placeholder syntax.

## HTTP Custom

HTTP Custom explicitly describes its SSH support as **direct, over SSL/TLS, or with a custom HTTP payload**. It also advertises SNI, a DNS changer, per-app VPN routing, MTU/IPv6 controls, auto-reconnect, load balancing, routing rules, and saved configuration files. [6]

Its SSH flow is therefore likely organized as a selectable connection mode:

- **Direct:** SSH connects directly to the configured server and port.
- **Payload:** a custom HTTP request is sent to a proxy or front-end first. The payload can include request method, path, Host header, and other headers. After the front-end accepts or forwards the connection, the SSH client starts on the resulting byte stream.
- **SSL/TLS/SNI:** a TLS socket is created with a chosen SNI/server name. SSH is carried after the TLS layer or through an SSL gateway.
- **Payload plus TLS:** the HTTP payload is sent through the TLS connection, combining HTTP camouflage/routing with encrypted transport.

The app's official developer site independently confirms that HTTP Custom focuses on custom HTTP headers, SSH, SNI, DNS control, and hotspot sharing. [7] A public implementation describing the same family of modes labels them direct SSH, payload with or without proxy, SSL/TLS using SNI, and payload plus SNI. It is useful as an architectural reference, not proof of HTTP Custom's private code. [8]

## DarkTunnel

DarkTunnel's official Play listing is the most explicit about the mode matrix. It lists SSH, SSH through DNSTT/SlowDNS, VMess, VLESS, Trojan, Shadowsocks, and custom V2Ray configuration. It separately lists TCP, WebSocket, and gRPC transports, then names five injection modes: **Direct, Direct with SNI, Proxy, Proxy with SNI, and DNSTT**. [9]

For SSH, those modes map naturally to:

- **Direct:** raw TCP connection to the SSH endpoint.
- **Direct with SNI:** TLS is established directly to the endpoint or a TLS-capable SSH gateway, with a selected SNI name.
- **Proxy:** the app connects through an HTTP proxy and uses a custom request or CONNECT-style setup before SSH bytes flow.
- **Proxy with SNI:** combines an HTTP proxy path with TLS/SNI.
- **DNSTT:** SSH is carried through a DNS-tunnel transport rather than an ordinary TCP/WebSocket stream. This is a separate, slower transport family, not merely an SSH payload variation.

DarkTunnel also lists WebSocket and gRPC as transport networks, so its SSH-related profiles may use a gateway that upgrades or multiplexes the stream before SSH authentication. However, its public listing does not disclose the exact handshake, DNS packet format, or internal forwarding implementation. [9]

## Important distinction: SNI is not SSH encryption

SSH already provides its own encryption after key exchange. HTTP payloads, proxies, TLS, WebSocket, SNI, and DNSTT mainly change how the initial connection reaches the SSH service or how it appears to an intermediary. TLS protects the outer transport; SNI selects the hostname presented during TLS setup; an HTTP payload is an application-layer preamble; and WebSocket is an HTTP upgrade followed by framed transport. These layers do not make an untrusted SSH server trustworthy, and they do not guarantee that a carrier or firewall cannot identify the traffic.

“Domain fronting” or SNI camouflage is also environment-dependent. Modern CDNs and providers may reject mismatched hostnames, and using a third party's domain without authorization can violate terms or law. The safe implementation assumption is that the user owns or is authorized to use the SSH server, proxy, TLS certificate, WebSocket gateway, and DNS tunnel domain.

## What V2-TUN would need to add SSH support

V2-TUN already has the Android VPN/TUN and Xray lifecycle foundation. To reproduce the SSH family cleanly, it would need a separate SSH engine and an explicit bridge:

1. SSH client library or native OpenSSH-compatible engine with password and key authentication.
2. Direct TCP connection protected with `VpnService.protect()`.
3. HTTP payload and HTTP proxy connector that produces a raw stream before SSH negotiation.
4. TLS connector with configurable SNI and certificate-verification policy.
5. WebSocket connector with a documented gateway contract.
6. Local SOCKS5 or a native TUN-to-SOCKS bridge for TCP forwarding.
7. A policy for UDP, because ordinary SSH dynamic forwarding is TCP-only.
8. Clear profile fields for SSH host/port, username, password/key, proxy, payload, TLS/SNI, WebSocket path, and routing mode.
9. Security safeguards: encrypted local secrets, no unsafe default certificate bypass, payload escaping/validation, host-key verification where practical, and logs that redact credentials.

The important design decision is whether SSH is used as a **transport inside an existing TUN-to-proxy engine** or as a **first-class outbound protocol**. The first approach is quicker but requires a reliable local SOCKS bridge. The second provides better lifecycle and routing control but requires more native networking code and careful TCP/UDP handling.

## References

[1]: https://www.ssh.com/academy/ssh/tunneling "SSH Tunneling and Port Forwarding"
[2]: https://developer.android.com/develop/connectivity/vpn "Android VPN developer documentation"
[3]: https://github.com/Anton2319/VPNoverSSH "VPNoverSSH Android reference implementation"
[4]: https://play.google.com/store/apps/details?id=com.evozi.injector&hl=en_US "HTTP Injector official Google Play listing"
[5]: https://github.com/tavgar/Custom-Internet/blob/main/README.md "SSH-over-WebSocket with SOCKS proxy reference implementation"
[6]: https://play.google.com/store/apps/details?id=xyz.easypro.httpcustom&hl=en_US "HTTP Custom official Google Play listing"
[7]: https://eprodev.org/ "ePro Dev Team official website"
[8]: https://github.com/abdoxfox/HTTP-CUSTOM-HEADERS-VPN "Public HTTP custom headers SSH VPN reference"
[9]: https://play.google.com/store/apps/details?id=net.darktunnel.app&hl=en_US "DarkTunnel official Google Play listing"
