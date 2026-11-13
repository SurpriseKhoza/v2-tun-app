# V2-TUN

A modern Android VPN client built around the Xray core, with a guided configuration editor, a raw JSON editor, live diagnostics, and a four-tab navigation shell.

![Package](https://img.shields.io/badge/package-io.surprise.vpn-43F5C7)
![Min SDK](https://img.shields.io/badge/minSdk-24-8B7CFF)
![Target SDK](https://img.shields.io/badge/targetSdk-35-FF8FA3)

## Download

The SSH-enabled sideloadable release build is in [`dist/V2-TUN-SSH-1.0.0.apk`](dist/V2-TUN-SSH-1.0.0.apk).

- Package: `io.surprise.vpn`
- Version: `1.0.0` (`versionCode 1`)
- Min Android: 8.0 / API 24
- Target Android: 15 / API 35
- ABIs: `arm64-v8a`, `armeabi-v7a`
- Release APK is signed with the project's local release keystore for direct installation. Replace it with your production keystore before publishing to a store.

Verify the download:

```bash
sha256sum -c dist/V2-TUN-SSH-1.0.0.apk.sha256
```

## What is inside

### Four-tab shell

- **Dashboard** — animated connection orb, live tunnel state, upload/download counters, latency, active route, protocol/security badges, and a compact settings gear in the top-left corner.
- **Configs** — searchable configuration vault, protocol filters, one-tap connect, share-link export, duplicate, edit, and delete.
- **Tools** — exit-IP check (through the tunnel when connected), DNS lookup, TCP reachability, system VPN/battery/notification shortcuts, backup/restore, and log viewer.
- **Settings** — theme mode, dynamic color, haptics, auto-connect on boot, notification updates, DNS servers, MTU, IPv6, log level, system access, privacy notes, and licenses.

### Configuration editing

Every configuration can be created or edited in either form:

1. **GUI form** — VLESS, VMess, Trojan, Shadowsocks, or SSH. SSH uses the restored reference-matched accordion editor with Payload and TLS toggles, conditional Remote proxy and SNI fields, SSH host/port, username, password, and optional private key. The broader shell uses lighter surfaces, while protocol editors retain their approved design.
2. **JSON form** — a syntax-highlighted, validated Xray JSON editor with a live preview, formatting, validation, and clipboard import. The tunnel injects its own TUN inbound, authenticated local diagnostics proxy, and `xray.tun.fd` environment value at run time, so a normal Xray client configuration can be pasted directly.

Supported share-link imports: SSH, VLESS, VMess, Trojan, Shadowsocks, and raw Xray JSON. Exports use standard share-link formats, with the raw JSON preserved for JSON-backed profiles.

### Tunnel engine

- Xray-core via the official [`XTLS/libXray`](https://github.com/XTLS/libXray) Android AAR (`26.11.9`).
- Android `VpnService` with a TUN file descriptor handed to Xray through `xray.tun.fd`.
- Xray outbound sockets protected with `LibXray.registerDialerController` so they do not loop back into the TUN.
- Bootstrap DNS protected with `LibXray.setDNS`, then reset on disconnect.
- Foreground service, notification controls, Quick Settings tile, boot auto-connect, and always-on VPN metadata.
- Live logs from both the app and the Xray engine.
- SSH profiles use the maintained `com.github.mwiede:jsch:2.28.8` session library and a protected local SOCKS5 bridge. The HTTP-injection adapter supports multi-block payload flows, header placeholders, proxy CONNECT/authentication, redirects, `100 Continue`, `101 Switching Protocols`, TLS/SNI, and binary WebSocket framing without adding WS/WSS fields to the editor. Xray routes TUN traffic into that bridge, keeping the VPN lifecycle in the existing service. See [`SSH-LIBRARY-RESEARCH.md`](SSH-LIBRARY-RESEARCH.md) and [`HTTP-INJECTION-RESEARCH.md`](HTTP-INJECTION-RESEARCH.md).

## Permissions

| Permission | Why it is used |
| --- | --- |
| `INTERNET` | Connect to the configured proxy server and run local diagnostics. |
| `ACCESS_NETWORK_STATE` | Show the active underlying network and monitor connectivity. |
| `ACCESS_WIFI_STATE` | Support network-state labeling on Wi-Fi. |
| `FOREGROUND_SERVICE` | Keep the tunnel alive while the app is not visible. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Android 14+ VPN foreground-service type. |
| `POST_NOTIFICATIONS` | Show the required ongoing VPN notification and disconnect action. |
| `WAKE_LOCK` | Declared for tunnel reliability on aggressive OEM power managers. |
| `RECEIVE_BOOT_COMPLETED` | Optional auto-connect after reboot when enabled in Settings. |

The VPN service itself is protected by `android.permission.BIND_VPN_SERVICE`, declared on the service rather than requested by the app. The app requests VPN consent through `VpnService.prepare()` the first time you connect.

## Security and privacy

- No analytics, advertising, tracking SDKs, or telemetry endpoints.
- Configurations are stored in app-private storage. The JSON backup is deliberately plaintext and can contain credentials; only export it to a location you trust.
- The app excludes its own package from the VPN so diagnostics can use the authenticated local Xray proxy without creating a routing loop.
- The local diagnostics proxy listens on `127.0.0.1` only and uses a random per-session username/password.
- `android:allowBackup="false"` prevents Android cloud backup of the profile vault.
- Release APK is currently signed with a local project keystore. Generate a private production keystore and update `app/build.gradle.kts` before distributing through a store.

## Build

Requirements: JDK 17+ (JDK 21 works), Android SDK Platform 35, Build Tools 35.0.0, and Gradle 8.9+ (the wrapper is included).

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

The release APK is written to `app/build/outputs/apk/release/app-release.apk`. The packaged delivery copy is `dist/V2-TUN-1.0.0.apk`.

## Project layout

```text
app/src/main/java/io/surprise/vpn/
├── core/        VpnService, Xray bridge, TUN/config factory, tile, boot receiver
├── data/        profile repository, share-link parser, diagnostics
├── ui/          Compose screens, glass components, theme, navigation
└── util/        formatting and API-safe Base64
```

## Tests

18 JVM unit tests cover:

- VLESS/VMess/Trojan/Shadowsocks share-link parsing and round trips.
- Raw Xray JSON outbound import.
- TUN/socks inbound generation, REALITY/TLS stream settings, routing, and TUN fd injection.
- Custom JSON injection and rejection of configs without a proxy outbound.
- The API-24-safe Base64 codec.
- SSH editor fields, SSH share-link round trips, SSH SOCKS outbound generation, and conditional SSH validation.

## Known limitations

- The native Xray runtime and real-server handshake require validation on a physical Android device with a working server. This build was compiled and unit-tested in a Linux sandbox; no Android device was attached to the sandbox.
- SSH dynamic forwarding is TCP-only; UDP-heavy applications may need a different transport strategy. Payload/proxy/TLS behavior should be verified against the intended server and carrier before production use.
- The APK includes ARM phone ABIs only. It will not install on an x86/x86_64 emulator.
- The release key is a project-local key for sideloading, not a Play Store production key.
- Store distribution of `specialUse` foreground services requires the corresponding Play Console declaration.

## Third-party notices

Bundled license texts are in `app/src/main/assets/licenses/`:

- Xray-core — MPL-2.0
- libXray — MIT
- Manrope — SIL Open Font License
- JetBrains Mono — SIL Open Font License
