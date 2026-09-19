# 08 · Working Protocol

How Claude and Codex are expected to collaborate in this repository, including session entry points and handoff format.

- [AGENTS.md](../../AGENTS.md) — agent rules and repository boundaries
- [CLAUDE.md](../../CLAUDE.md) — session entry point
- [handoff.md](../../handoff.md) — general handoff record
- [.codex-handoff-desktop-batch.md](../../.codex-handoff-desktop-batch.md) — desktop batch handoff

The project bible also records the session protocol and Claude/Codex division of labor in [§0 Session Protocol](../../PROJECT-BIBLE.md#0-session-protocol) and [§6 Claude/Codex Division of Labor](../../PROJECT-BIBLE.md#6-claudecodex-division-of-labor).

## Vault access (MCP)

A filesystem MCP server named `tvivo-vault` is configured machine-locally (in
`C:\Users\Dell\.claude.json`, not in this repo) using the official
`@modelcontextprotocol/server-filesystem` package, scoped exclusively to
`D:\HCode\Tvivo\vault`. It has full read/write/delete access **inside the vault only** — it
cannot reach the rest of the repo, `.git`, source code, build/cache folders, or any path outside
`vault\`. Verified 2026-09-19 by direct stdio JSON-RPC calls: reads/writes inside the vault
succeeded, reads/writes one level up (repo root) were denied with
`Access denied - path outside allowed directories`. Requires a Claude Code restart to pick up
config changes to `mcpServers`.