# SSH library choice for V2-TUN

## Recommendation

V2-TUN should use the maintained `com.github.mwiede:jsch` fork rather than Apache MINA SSHD for the current Android VPN bridge. The current project already uses JSch's compact session API, `SocketFactory`, public-key/password authentication, and direct-tcpip channels. The dependency is upgraded to `2.28.8`, the latest version visible in Maven Central on 2026-11-12.

JSch fits this editor because the SSH profile needs a custom pre-SSH socket path: optional remote proxy, payload/header writes, optional TLS/SNI wrapping, and then SSH authentication. The fork exposes the original JSch API, supports custom socket factories and port-forwarding primitives, and is designed as a maintained drop-in replacement for the unmaintained SourceForge JSch. Its README also documents modern RSA SHA-2 behavior and newer algorithm support.

Apache MINA SSHD is broader and more feature-rich. Its official project page describes a pure-Java client/server library with built-in asynchronous I/O and optional MINA or Netty backends. However, the project's Android documentation explicitly says Android compatibility is not a stated support goal and has not been thoroughly tested. That makes it a weaker fit for this app's API 24 minimum and its focused SSH-over-TUN use case, despite its stronger general SSH feature set.

## Implementation decision

The app uses mwiede JSch `2.28.8` with a reusable `SshTunnelEngine`. The engine creates the SSH session through the configured host, port, username, password, or private key. It exposes a loopback SOCKS5 bridge backed by SSH `direct-tcpip` channels. Xray remains responsible for Android TUN routing, DNS routing, lifecycle, metrics, and protected sockets. This avoids duplicating the existing VPN service architecture.

The editor's Payload and TLS toggles control the socket setup. Payload writes the configured request after placeholder expansion. Remote proxy selects the outer TCP endpoint. TLS wraps the outer socket and applies the configured SNI. Disabling Payload removes the payload and proxy fields from the UI; disabling TLS removes the SNI field. The SSH dynamic bridge is TCP-only.

## Sources

- [1] https://github.com/mwiede/jsch "mwiede JSch official repository"
- [2] https://github.com/mwiede/jsch/blob/master/Readme.md "mwiede JSch README and feature notes"
- [3] https://repo1.maven.org/maven2/com/github/mwiede/jsch/ "Maven Central version directory"
- [4] https://mina.apache.org/sshd-project/ "Apache MINA SSHD official overview"
- [5] https://github.com/apache/mina-sshd/blob/master/docs/android.md "Apache MINA SSHD Android support notes"
