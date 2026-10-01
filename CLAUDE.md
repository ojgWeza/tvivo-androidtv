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

## app/CODEMAP.md (Android TV)

Same skill, applied to the Android app — ViewModel → Repository method → Room DAO method
(`@Query`-annotated, abstract) → SQLite table. Its own vendored copy lives at
`app/scripts/codemap.mjs`; config at `app/codemap.config.json`. Covered by the same
`.githooks/pre-commit` (loops over both `windows-spike` and `app`).

- Regenerate: `node app/scripts/codemap.mjs` (run from `app/`)
- CI/staleness gate: `node app/scripts/codemap.mjs check`
- Notes freshness: `node app/scripts/codemap.mjs changes` / `review <doc>`, same workflow as above.
- Needed two more local script extensions beyond what `windows-spike` already added — both live in
  the shared `codemap.mjs` body (kept in sync by copying, since each project vendors its own copy):
  - `controllers.declRegex` + `controllers.requireVerb: false` — Kotlin has no `public` keyword and
    no `[HttpVerb]` attribute, so the Repository layer needed its own declaration pattern and no
    verb-gating (every matching `fun` becomes a route, verb always `CALL`).
  - `backend.kotlinRoomDao: true` — Room DAO methods are abstract; their SQL lives in a `@Query(...)`
    annotation *above* the declaration, not in a body below it, so the existing brace-depth body
    scanner cannot see it at all. This mode also tracks `interface FooDao { }` boundaries (one file,
    `SupportDaos.kt`, holds several) and keys each method by its accessor shape (`fooDao().method`),
    never by the bare method name — DAO method names collide freely across DAOs (`observe`, `get`,
    `upsert`, …), and collapsing them would silently merge different tables' reads/writes.
  - Repository method names *are* allowed to collide across `LiveRepository`/`VodRepository`/
    `SeriesRepository` on purpose (by contrast with DAO methods above): those three are deliberately
    the same shape for three content types, so e.g. route `pagingInCategory` merges all three real
    backend calls under one address — read as "this operation, across content types, touches these
    tables," not as one specific class's method.
  - Known coverage gaps, not correctness bugs: `CachedFetch` calls its DAO field directly
    (`syncMetaDao.get(...)`, no `db.` prefix) so those calls aren't linked; the UI layer's generic
    `CatalogSource` wrapper (`catalog.pagingFiltered(...)`) is a second indirection the frontend
    regex can't see through, so it shows as a route "not defined" even though the real call
    (`repo.countInCategoryFiltered(...)`) is captured correctly one line away.
- Oracle (source of meaning, not structure): `PROJECT-BIBLE.md`.
