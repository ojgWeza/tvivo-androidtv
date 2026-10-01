# Tvivo — Session Entry Point

**Read in this order:**

1. `D:\HCode\DEV-BIBLE.md` — global rules for all D:\HCode projects
2. `PROJECT-BIBLE.md` — this project's rules, state, and constraints (§0-8)
3. `PRODUCT-BIBLE.md` — product spec, users, principles, commitments
4. Memory index in `C:\Users\Dell\.claude\projects\D--HCode-Tvivo\memory\MEMORY.md`
5. `TODO.md` — open work queue

All rules are binding. If a task conflicts with documented rules, surface the conflict.

See `PROJECT-BIBLE.md` §0 for detailed session protocol and §6 for Claude/Codex workflow.

## windows-spike/CODEMAP.md

`windows-spike/CODEMAP.md` is a generated index — Pages → `SqliteCatalogRepository` methods →
SQLite tables — kept current by `.githooks/pre-commit` (enabled via `git config core.hooksPath
.githooks`, already set for this clone). It is the sanctioned exception to "no hand-maintained
layout copy": it's generated and self-checking, never edited by hand.

- Regenerate: `node windows-spike/scripts/codemap.mjs` (run from `windows-spike/`)
- CI/staleness gate: `node windows-spike/scripts/codemap.mjs check`
- After editing `SqliteCatalogRepository.cs` or `Tvivo.App/**/*.cs`, re-check notes freshness:
  `node windows-spike/scripts/codemap.mjs changes` — re-read `TODO.md`/`docs/decisions.md` against
  what it lists, then `node windows-spike/scripts/codemap.mjs review <doc>` once actually re-read.
- This app has no web-controller/stored-proc layer, so `windows-spike/scripts/codemap.mjs` carries
  a local extension beyond the stock skill script: when `controllers`/`sql` are left out of
  `codemap.config.json`, each repository method stands in as its own "route" and its own "proc" —
  see the `collapsedRoutes`/`collapsedProcs` logic in that file before editing it.
- Oracle (source of meaning, not structure): `PROJECT-BIBLE.md`.
