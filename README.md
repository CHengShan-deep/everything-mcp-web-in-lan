# everything-mcp-web in LAN

[![ci](https://github.com/CHengShan-deep/everything-mcp-web-in-lan/actions/workflows/ci.yml/badge.svg)](https://github.com/CHengShan-deep/everything-mcp-web-in-lan/actions/workflows/ci.yml)

Local-first website hosting for Android agents, managed over MCP.

**Status: early skeleton.** Design derisking is done; v1 feature work
(per-site data, REST, auth, admin console, MCP tools) is in progress.

## What it is

- An Android app that hosts dynamic websites on the local network.
- An MCP control plane bound to loopback (`127.0.0.1`) so on-device agents can create sites, deploy content, manage schemas/data, configure auth, and get URLs.
- A LAN data plane with per-site ports, optional FQDN access through internal DNS, and an optional 80-port gateway for bare names.
- Per-site SQLite, generated REST, optional write protection, file storage, versions, and backups.
- Default local-only; LAN exposure is opt-in per site. Admin and MCP are always authenticated.

## Scope

- v1: platform-managed backend (SQLite + declarative schema + generated REST + optional constrained JS hooks), Android 10+ (minSdk 29), sideload/F-Droid.
- v2+: full Node.js runtime; Python later.

## Current skeleton

The repository now contains the app skeleton, not the final v1 feature set:

- a `specialUse` foreground service that survives reboots, app updates and process death
- the loopback control plane on `127.0.0.1:47100` with automatic fallback when the port is taken
- a LAN hub on `47101` and one listener per hosted site on `47200+`
- a machine-readable UI (`/ui`, `/ui/action`) so on-device agents can read the screen and drive it without screenshots
- diagnostics at `/diag` and `/sites` on the control plane

## Build

Requires JDK 21 and Android SDK 36.

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Device notes

- Battery-optimization exemption is required for LAN availability while the device is in Doze.
- Some OEM builds (for example MIUI) block autostart after reboot: open the app once after a reboot, or allow autostart for it in system settings.
- The control plane is loopback-only. LAN exposure is explicit per site.

## Docs

Design notes are intentionally not published in this repository.

## License

MIT. See [LICENSE](LICENSE).

The GitHub repository name uses hyphens because GitHub does not allow spaces in repository names.
