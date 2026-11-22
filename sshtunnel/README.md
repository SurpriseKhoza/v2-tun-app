# sshtunnel

SSH transport for libXray. `Start` authenticates to an SSH server (directly or
through the `payloadengine` carrier) and exposes a loopback SOCKS5 listener that
an Xray `socks` outbound can use.

## Config fields beyond the basics

| Field | Default | Meaning |
| --- | --- | --- |
| `udpGwPort` | `0` (UDP off) | Enables SOCKS5 `UDP ASSOCIATE`. Datagrams are relayed through a [BadVPN `udpgw`](https://github.com/ambrop72/badvpn) daemon that must listen on `127.0.0.1:<udpGwPort>` on the SSH server (conventionally `7300`). The bridge reaches it with an SSH `direct-tcpip` channel. If nothing listens there, `UDP ASSOCIATE` fails immediately with "host unreachable" and TCP is unaffected. |
| `tcpFastOpen` | `false` | Best-effort TCP Fast Open (`TCP_FASTOPEN_CONNECT`) on the outgoing carrier/SSH connection. Silently ignored where the kernel or platform does not support it. |

### UDP relay details

- One udpgw channel is opened per SOCKS5 UDP association and closed with its
  control TCP connection.
- Datagrams use the udpgw framing: `len(u16 LE) | flags | conid(u16 LE) |
  addr(4 or 16) | port(u16 BE) | payload`. Each destination gets its own `conid`;
  the first packet for a `conid` sets the `REBIND` flag.
- Domain destinations are resolved locally (IPv4 preferred) because udpgw only
  carries IP addresses. SOCKS5 UDP fragmentation is not supported.
- A header-only keepalive frame is sent every 15 seconds.
- Only loopback sources are accepted on the UDP relay socket.
