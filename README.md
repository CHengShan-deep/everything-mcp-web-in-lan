# everything-mcp-web in LAN

[![ci](https://github.com/CHengShan-deep/everything-mcp-web-in-lan/actions/workflows/ci.yml/badge.svg)](https://github.com/CHengShan-deep/everything-mcp-web-in-lan/actions/workflows/ci.yml)

**PocketServe** — host real websites from an Android phone on your local network, managed by an
agent over MCP.

## What it is

- **PocketServe app**: a foreground service that serves dynamic sites over HTTP from the phone.
- **MCP control plane** on `127.0.0.1` (loopback only): 31 tools so an on-device agent can create
  sites, deploy content, change schemas, manage auth, files, backups and revisions.
- **Admin console** at `/admin`: token login, sites, deploy, backups, revisions, traffic, audit log.
- **LAN data plane**: one port per site, plus a hub on `47101` that routes by `Host` header, so a
  single reverse-proxy rule can serve every `<name>.<base domain>`.

Current version: **0.2.0** (minSdk 29, targetSdk 36; verified on Android 10 and 14).

## Features

- **Sites** — per-site SQLite, manifest-declared tables/fields/indexes, generated REST
  (`GET|POST|PATCH|DELETE /api/{table}[/{id}]`), static content with SPA fallback.
- **Auth** — read `none|token`, write `token|none` with hashed, rotatable tokens; anonymous writes
  require an explicit confirmation.
- **Files** — per-site uploads with MIME sniffing, executable-type rejection, quota, `ETag`/`Range`
  downloads.
- **Backups** — full archive (manifest, data snapshot, uploads, static tree), WAL-safe snapshots
  (`VACUUM INTO` with a quiesce fallback for older SQLite), verified restores, restoring an
  archive into a brand-new site, and passphrase-sealed export/import (AES-256-GCM over PBKDF2)
  for moving a site off the device.
- **Revisions** — immutable content snapshots with content-hash dedupe, atomic staged swaps, and
  rollback that leaves data and uploads untouched.
- **Schema safety** — destructive migrations (dropped table/column, type change, backfills) are
  refused unless explicitly confirmed; rebuilds are transactional and keep row ids.
- **Observability** — per-site request log (last 1000), traffic buckets by minute/hour/day, audit
  log of every control-plane change.
- **App UX** — first-run guide, English/Chinese switch, per-site start/stop switches, battery and
  OEM autostart guidance, and a one-tap **Share MCP document** that hands the endpoint, token,
  protocol notes and full tool list to your agent.

## Install

Grab `app-debug.apk` from the latest successful `ci` run (Actions → artifacts) and sideload it:

```bash
adb install -r app-debug.apk
```

A fresh install may need the OEM installer's in-person confirmation; on MIUI also enable
"Install via USB" in developer options.

### Release builds

The keystore is never committed. Provide it through environment variables (or `-PemwlKeystore=…`):

```bash
export EMWL_KEYSTORE=/path/to/pocketserve.jks
export EMWL_KEYSTORE_PASSWORD=…
export EMWL_KEY_ALIAS=…
export EMWL_KEY_PASSWORD=…
./gradlew assembleRelease
```

Without those variables `assembleRelease` still builds, but produces an unsigned APK.

## Quick start

1. Open the app and tap **Start hosting**. The service keeps running in the background.
2. Allow **Battery exemption** — without it, Android Doze cuts LAN access even for the loopback.
3. Allow **Autostart** in your vendor settings (`MIUI`/`ColorOS` block it by default). If you skip
   this, open the app once after each reboot; the app tells you when a system start was blocked.
4. Copy the **MCP token** from the app (Settings → Copy MCP token).
5. Create and deploy a site over MCP:

```bash
curl -s http://127.0.0.1:47100/mcp \
  -H "Authorization: Bearer $MCP_TOKEN" -H 'content-type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"site_create",
       "arguments":{"slug":"hello","name":"Hello"}}}'
```

Then `site_deploy` with a manifest, `site_exposure_set` to open it to the LAN, and `site_url_get`
for the URLs. After each permission change, reload the app screen to see the updated state.

## LAN access and the gateway

- Each site listens on its own port (`47200`–`47231`, stable across restarts).
- The hub on `47101` routes by `Host`: `Host: hello.lan` → the site named `hello`. Local-only sites
  are not routed there.
- **Port 80 cannot be bound from the app** on Android 10 or 14 (`EACCES`, measured and reported in
  `/diag`), so a bare-name gateway needs an external reverse proxy in front of the hub, for example:

```
# Caddyfile
*.lan {
    reverse_proxy 192.168.1.50:47101
}
```

## Device caveats

- Battery-optimization exemption is a hard prerequisite for LAN availability in Doze.
- Some OEM builds block autostart after reboot (`process is not permitted to auto start` on MIUI);
  ColorOS may freeze the process on screen-off until a packet arrives.
- A fresh sideload may need a 10-second in-person confirmation from the OEM installer.

## Limits

- 32 registered and 32 concurrently served sites.
- 20 revisions and 20 backups kept per site; request log keeps the last 1000 entries.
- v1 hosts platform-managed backends only; a Node.js runtime is planned for v2.

## Build and test

```bash
./gradlew assembleDebug        # debug APK
./gradlew testDebugUnitTest    # JVM unit tests
./gradlew lintDebug            # Android lint
```

Requires JDK 21 and Android SDK 36.

## Docs

Design notes are intentionally not published in this repository.

## License

MIT. See [LICENSE](LICENSE).

The GitHub repository name uses hyphens because GitHub does not allow spaces in repository names.
