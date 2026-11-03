# everything-mcp-web in LAN

[![ci](https://github.com/CHengShan-deep/everything-mcp-web-in-lan/actions/workflows/ci.yml/badge.svg)](https://github.com/CHengShan-deep/everything-mcp-web-in-lan/actions/workflows/ci.yml)

Local-first website hosting for Android agents, managed over MCP.

**Status: planning (v1).** Implementation has not started yet. Design notes are kept private.

## What it is

- An Android app that hosts dynamic websites on the local network.
- An MCP control plane bound to loopback (`127.0.0.1`) so on-device agents can create sites, deploy content, manage schemas/data, configure auth, and get URLs.
- A LAN data plane with per-site ports, optional FQDN access through an internal DNS wildcard, and an optional 80-port gateway for bare names.
- Per-site SQLite, generated REST, optional write protection (off by default for reads; write token by default), file storage, versions, and backups.
- Default local-only; LAN exposure is opt-in per site. Admin and MCP are always authenticated.

## Scope

- v1: platform-managed backend (SQLite + declarative schema + generated REST + optional constrained JS hooks), Android 10+, sideload/F-Droid.
- v2+: full Node.js runtime; Python later.

## Docs

Design notes are intentionally not published in this repository.

## License

MIT. See [LICENSE](LICENSE).

The GitHub repository name uses hyphens because GitHub does not allow spaces in repository names.
