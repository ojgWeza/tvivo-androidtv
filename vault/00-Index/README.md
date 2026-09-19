# Tvivo Project Vault — Index

Durable knowledge base for the Tvivo IPTV project (Android TV + Windows desktop). This vault does
**not** replace any source file — every page here either links to an original file in place, or
(for a small curated set) holds a dated snapshot explicitly marked as a copy, with the original
noted as the source of truth.

Vault path: `D:\HCode\Tvivo\vault\` (tracked in the same git repository as the project).
Created: 2026-09-19.

## Sections

- [01-Overview](../01-Overview/README.md) — what Tvivo is, who it's for, platform/positioning
- [02-Architecture](../02-Architecture/README.md) — current system architecture, API reference, UI scope
- [03-Status](../03-Status/README.md) — open work, desktop plans, TODO
- [04-Decisions](../04-Decisions/README.md) — decision log and ADR-style records
- [05-Investigations](../05-Investigations/README.md) — analyses, reviews, QA reports, research digests
- [06-Findings](../06-Findings/README.md) — confirmed learnings and durable conclusions
- [07-Experiments-and-Gates](../07-Experiments-and-Gates/README.md) — Windows rewrite spike phases and gates
- [08-Working-Protocol](../08-Working-Protocol/README.md) — Claude/Codex working rules, session entry points
- [09-Build-and-Run](../09-Build-and-Run/README.md) — build/run instructions
- [90-Archive](../90-Archive/README.md) — superseded/historical material

## How this vault was built

Created by Claude Code with a Codex-authored design (read-only analysis). See
[07-Experiments-and-Gates](../07-Experiments-and-Gates/README.md) for the vault build itself as a
recorded action. Every linked file below is unmodified; only `vault/` and a narrow `.gitignore`
addition were created.

## Conventions

- **Link pages** point at the original file with a relative path — nothing was moved.
- **Snapshot pages** (in `04-Decisions/` and `02-Architecture/` only, listed below) are explicit,
  dated copies of specific files, each carrying a header: `Copied from <path> on <date> — treat
  the original as source of truth.` They exist so a reviewer can read core decisions without
  leaving Obsidian; they are not maintained as a sync target.
- Files containing provider-identifying data (QA reports with real panel hostnames/screenshots),
  large binaries, and build artifacts are intentionally **not** included, copied, or embedded.
