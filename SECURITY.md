# Security policy

## Scope

TV MCP runs an HTTP MCP server and can control a TV or media player. It is
intended for a trusted local network only.

## Reporting a vulnerability

Please report security issues privately through GitHub Security Advisories
for this repository. Do not open a public issue for an unfixed vulnerability.
Include reproduction steps, the affected version/commit, and the impact.

## Threat model and recommendations

- **Do not expose port 8765 (or the fallback ports 8766–8769) to the
  internet.** Use a LAN, firewall, or VPN.
- The pairing token is the only authentication mechanism. Treat it as a
  password. If it leaks, rotate it from the pairing screen.
- The server uses cleartext HTTP so it can reach LAN sources. Do not use it
  over untrusted networks.
- CORS is permissive so browser-based MCP clients can connect. The token is
  still required for `/mcp`, `/setup` and `/api/setup`.
- SMB credentials are stored in the app's private SharedPreferences in
  plaintext. Use a dedicated, read-only NAS account.
- The `/setup` page sends source credentials over HTTP. Use it only on a
  trusted network.
- No analytics, telemetry, or cloud service is used.

## Supported versions

Security fixes are applied to the `main` branch. There are no maintained
release branches at this time.
