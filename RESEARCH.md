# V2-TUN research notes

These are the primary sources used while designing the Android VPN architecture and Xray integration. They explain why the implementation uses a TUN file descriptor, a special-use foreground service, and protected outbound sockets.

## Android VPN service

- [Android Developers — Build a VPN service](https://developer.android.com/develop/connectivity/vpn)
  - A VPN app extends `VpnService`, builds a TUN interface with `VpnService.Builder`, and must receive user consent through `VpnService.prepare()`.
  - The service is declared with `android.permission.BIND_VPN_SERVICE` and an `android.net.VpnService` intent filter.
  - `establish()` returns a `ParcelFileDescriptor` that represents the TUN interface.
- [Android 15 behavior changes](https://developer.android.com/about/versions/15/behavior-changes-15)
  - Apps targeting Android 15 need to account for edge-to-edge enforcement and foreground-service restrictions.
- [Foreground service types](https://developer.android.com/develop/background-work/services/fg-service-types)
  - A VPN that does not fit the connected-device type uses `specialUse` with a manifest subtype property on Android 14+.

Implementation decisions:

- `minSdk 24`, `targetSdk 35`.
- `FOREGROUND_SERVICE_SPECIAL_USE` and `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` are declared for the tunnel service.
- `POST_NOTIFICATIONS` is requested before the first connection, but the foreground notification remains the Android-required ongoing notification.
- The app declares `SUPPORTS_ALWAYS_ON` metadata so Android can offer always-on VPN behavior.

## Xray TUN and libXray

- [XTLS/libXray](https://github.com/XTLS/libXray)
  - Official Go mobile binding used by the app. The release inspected for this build was `v26.11.9`.
  - The Java API exposes `LibXray.invoke(String)`, `registerDialerController`, and `setDNS`.
  - The invoke envelope is `{apiVersion, method, payload}`; `runXray` accepts `{"xrayJson": "..."}`.
- [Xray-core TUN inbound documentation](https://xtls.github.io/en/config/inbounds/tun.html)
  - On Android and iOS, the TUN FD is passed from the host app through the `XRAY_TUN_FD` environment variable. Xray cannot create the Android interface standalone.
  - The Xray source normalizes this key as `xray.tun.fd`; both names resolve to the same setting.
- [Xray-core Android TUN implementation](https://github.com/XTLS/Xray-core/blob/main/proxy/tun/tun_android.go)
  - `NewTun` reads `platform.TunFdKey` (`xray.tun.fd`), calls `SetNonblock`, duplicates the descriptor, and wraps it in the packet stack.
- [Xray-core TUN config schema](https://github.com/XTLS/Xray-core/blob/main/proxy/tun/config.proto)
  - The TUN inbound accepts `name`, `MTU`, `gateway`, DNS, routing-table, and interface options. Android fd mode only needs the descriptor and MTU.

Implementation decisions:

- The service calls `ParcelFileDescriptor.detachFd()` and injects the number into the generated config under `env.xray.tun.fd`.
- Xray is started with `LibXray.invoke` on a background dispatcher; the app keeps the original descriptor and closes it after Xray stops.
- `LibXray.registerDialerController` protects Xray's own outbound sockets so they bypass the VPN route instead of looping.
- `LibXray.setDNS` installs a protected bootstrap resolver before Xray starts; `resetDNS()` runs on stop and on failure.
- The generated config uses a TUN inbound, an authenticated loopback SOCKS inbound for in-app diagnostics, DNS routing, private-network direct routing, and a default proxy route.

## Local diagnostics proxy

- [Xray SOCKS inbound](https://xtls.github.io/en/config/inbounds/socks.html)
  - Supports username/password authentication. The implementation uses `auth: "password"` with a `users` array containing `user` and `pass`.
- [Xray HTTP inbound](https://xtls.github.io/en/config/inbounds/http.html)
  - Xray's SOCKS inbound also accepts HTTP proxy requests when password authentication is configured, which lets the app use OkHttp with an HTTP proxy and `Proxy-Authorization` for HTTPS exit-IP checks.

Implementation decisions:

- The diagnostics proxy listens only on `127.0.0.1` and uses a random per-session token.
- The VPN excludes its own package, so OkHttp can reach the loopback proxy without its traffic entering the TUN.
- The exit-IP check uses HTTPS (`ipwho.is`, with `api.ipify.org` as a fallback), so the app can keep `usesCleartextTraffic="false"`.

## UI and design references

- Material 3 color roles, tonal surfaces, and dynamic color were used as the accessibility baseline.
- The visual direction is a dark "aurora glass" system: deep ink backgrounds, aqua/violet/coral gradients, animated orbital rings, translucent cards, and restrained haptics.
- Manrope is the product typeface; JetBrains Mono is used for JSON and logs. Both are bundled with their OFL license texts.

## Build validation performed

- `:app:testDebugUnitTest` — 15 tests, 0 failures.
- `:app:lintDebug` — no blocking errors after the API-24 Base64 fix and the deliberate tile-API suppression.
- `:app:assembleRelease` — APK produced and signature/metadata inspected with `aapt2` and `apksigner`.
- Native `libgojni.so` for `arm64-v8a` is 16 KB segment-aligned, which is relevant for Android 15 16 KB page-size devices.

## What still needs device validation

The sandbox had no Android device or KVM-enabled emulator. A real-device smoke test is still required for:

- The Xray handshake against an actual VLESS/VMess/Trojan/Shadowsocks server.
- TUN packet flow and DNS behavior on a physical phone.
- Foreground-service and notification behavior on Android 13–15.
- Always-on VPN and boot auto-connect behavior on an OEM build.
