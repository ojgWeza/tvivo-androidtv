#!/usr/bin/env node
// codemap — a generated, checkable address directory into a codebase, plus
// hash-tracked review state for the prose docs that explain it.
//
// Two halves, deliberately kept apart:
//
//   1. THE INDEX (CODEMAP.md). Regenerated from source: route -> frontend caller
//      -> backend method -> stored procs -> tables, plus drift detectors. It
//      records addresses only and never asserts a rule. It carries no timestamp
//      or commit hash, so identical source gives a byte-identical file and
//      `check` PROVES it current instead of asking anyone to trust it.
//
//   2. THE NOTES (prose docs you already have — a bible, a README per module).
//      These hold meaning, which no generator can extract. They cannot be
//      regenerated, so instead each doc declares the source globs it describes,
//      `review` stamps their content hashes, and `changes` lists every doc whose
//      sources moved since it was last reviewed. It tells you WHICH doc to
//      re-read; it never claims the doc is wrong.
//
// Usage (from anywhere inside the repo):
//   node scripts/codemap.mjs [generate]        write CODEMAP.md
//   node scripts/codemap.mjs check [--strict]  exit 1 if CODEMAP.md is stale
//                                              (--strict: also if any note is)
//   node scripts/codemap.mjs changes [--strict] [--quiet]
//                                              list notes whose sources changed
//   node scripts/codemap.mjs review [doc ...]  mark notes reviewed (all if none)
//   node scripts/codemap.mjs init              write a codemap.config.json skeleton
// Options: --root DIR · --config FILE · --out FILE (generate/check only)
// `--check` is accepted as an alias for `check`.
//
// No dependencies beyond Node 18+. Reads files only — no network, no database.

import { readFileSync, writeFileSync, readdirSync, statSync, existsSync, mkdirSync } from 'node:fs';
import { join, relative, sep, dirname, resolve, isAbsolute } from 'node:path';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';

/* ------------------------------------------------------------------- args */
const argv = process.argv.slice(2);
const opt = (name) => {
  const i = argv.indexOf(name);
  return i >= 0 ? argv[i + 1] : undefined;
};
const flag = (name) => argv.includes(name);
const VALUED = new Set(['--root', '--config', '--out']);
const positional = argv.filter((a, i) => !a.startsWith('--') && !VALUED.has(argv[i - 1]));
let command = positional[0] || 'generate';
if (flag('--check')) command = 'check';
const cmdArgs = positional.slice(1);

// Root = --root, else the nearest directory upward holding codemap.config.json
// (so a project nested inside a larger git repo maps itself, not its parent),
// else the current directory. With an explicit --config, the current directory.
const findConfigDir = () => {
  for (let d = process.cwd(); ; d = dirname(d)) {
    if (existsSync(join(d, 'codemap.config.json'))) return d;
    if (dirname(d) === d) return null;
  }
};
const ROOT = resolve(opt('--root') || (opt('--config') ? process.cwd() : findConfigDir() || process.cwd()));
const rel = (p) => relative(ROOT, p).split(sep).join('/');
const abs = (p) => (isAbsolute(p) ? p : join(ROOT, p));
const read = (p) => readFileSync(p, 'utf8');
const normEol = (s) => s.replace(/\r\n/g, '\n');

/* ----------------------------------------------------------------- config */
const CONFIG_PATH = abs(opt('--config') || 'codemap.config.json');

const SKELETON = {
  $comment:
    'codemap config. Every pattern is a JavaScript regex string; capture group 1 is the name. ' +
    'Globs support **, *, ? and {a,b}. Delete any layer your stack does not have.',
  output: 'CODEMAP.md',
  oracle: 'README.md',
  eol: 'auto',
  exclude: [],
  controllers: {
    files: ['Api/Controllers/**/*.cs'],
    routeLabel: 'api/…',
    backendCall: ['BL\\(\\)\\s*\\.\\s*(\\w+)\\s*\\('],
  },
  backend: { files: ['Lib/**/*.cs'], procRef: ['\\b(usp_\\w+)'], inlineSql: true },
  sql: { files: ['**/*.sql'] },
  frontend: {
    files: ['App/src/**/*.{ts,tsx}'],
    routeRef: ['api/\\w+/(\\w+)'],
    ignore: 'mock',
    stripPrefix: 'App/src/',
  },
  ownTables: '^App_',
  notes: { 'README.md': ['Api/**', 'Lib/**'] },
  notesState: '.codemap/reviewed.json',
};

if (command === 'init') {
  if (existsSync(CONFIG_PATH)) {
    console.error(`${rel(CONFIG_PATH)} already exists — not overwriting.`);
    process.exit(1);
  }
  writeFileSync(CONFIG_PATH, JSON.stringify(SKELETON, null, 2) + '\n');
  console.log(`Wrote ${rel(CONFIG_PATH)} — edit its globs and patterns to this repo's conventions.`);
  process.exit(0);
}

if (!existsSync(CONFIG_PATH)) {
  console.error(`No config at ${CONFIG_PATH}. Run: node scripts/codemap.mjs init`);
  process.exit(2);
}
const cfg = JSON.parse(read(CONFIG_PATH));
const arr = (v) => (v == null ? [] : Array.isArray(v) ? v : [v]);
const regexes = (v) => arr(v).map((s) => new RegExp(s, 'g'));

/* ------------------------------------------------------------ file listing */
function globToRe(g) {
  let re = '';
  for (let i = 0; i < g.length; i++) {
    const c = g[i];
    if (c === '*') {
      if (g[i + 1] === '*') {
        i++;
        if (g[i + 1] === '/') {
          i++;
          re += '(?:.*/)?';
        } else re += '.*';
      } else re += '[^/]*';
    } else if (c === '?') re += '[^/]';
    else if (c === '{') {
      const end = g.indexOf('}', i);
      re += '(?:' + g.slice(i + 1, end).split(',').map((s) => s.replace(/[.+^$()|[\]\\]/g, '\\$&')).join('|') + ')';
      i = end;
    } else re += c.replace(/[.+^$()|[\]\\]/g, '\\$&');
  }
  return new RegExp('^' + re + '$', 'i');
}
const matcher = (globs) => {
  const res = arr(globs).map(globToRe);
  return (p) => res.some((r) => r.test(p));
};

// Enumerate through git, so gitignored machine-local scratch (QA dumps, build
// output) never reaches the index. A hardcoded skip list made `check`
// non-deterministic in the project this was extracted from: the same commit
// gave a different CODEMAP.md on a box that had run QA than on a clean clone.
function listFiles() {
  let files;
  try {
    files = execFileSync('git', ['ls-files', '-z', '--cached', '--others', '--exclude-standard'], {
      cwd: ROOT,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
      maxBuffer: 256 * 1024 * 1024,
    })
      .split('\0')
      .filter(Boolean)
      .filter((p) => existsSync(join(ROOT, p)));
  } catch {
    // Not a git checkout: fall back to a walk with the usual suspects skipped.
    const SKIP = new Set(['node_modules', '.git', 'obj', 'bin', 'packages', 'dist']);
    files = [];
    const walk = (d) => {
      for (const e of readdirSync(d)) {
        if (SKIP.has(e)) continue;
        const p = join(d, e);
        if (statSync(p).isDirectory()) walk(p);
        else files.push(rel(p));
      }
    };
    walk(ROOT);
  }
  const excluded = matcher(cfg.exclude);
  const out = cfg.output || 'CODEMAP.md';
  return files.filter((p) => !excluded(p) && p !== out).sort();
}
const ALL = listFiles();
const select = (globs) => ALL.filter(matcher(globs)).map((p) => join(ROOT, p));
// A configured layer that matches nothing is a broken config, never an empty
// codebase: refuse, or `check` would happily certify a map of nothing.
const selectLayer = (layer, globs) => {
  const files = select(globs);
  if (arr(globs).length && !files.length) {
    console.error(`codemap: "${layer}" globs ${JSON.stringify(arr(globs))} match no files under ${ROOT}.`);
    console.error('codemap: fix the config (paths are relative to the folder holding codemap.config.json).');
    process.exit(2);
  }
  return files;
};

/* ------------------------------------------------------------------ notes */
const STATE_PATH = abs(cfg.notesState || '.codemap/reviewed.json');
const hashOf = (p) => createHash('sha256').update(normEol(read(p))).digest('hex').slice(0, 16);

function noteStatus() {
  let state = {};
  try {
    state = JSON.parse(read(STATE_PATH));
  } catch {}
  const report = [];
  for (const [doc, globs] of Object.entries(cfg.notes || {})) {
    const now = Object.fromEntries(select(globs).map((p) => [rel(p), hashOf(p)]));
    const was = state[doc]?.files;
    if (!was) {
      report.push({ doc, never: true, added: Object.keys(now), removed: [], modified: [], now });
      continue;
    }
    const added = Object.keys(now).filter((f) => !(f in was));
    const removed = Object.keys(was).filter((f) => !(f in now));
    const modified = Object.keys(now).filter((f) => f in was && was[f] !== now[f]);
    report.push({ doc, never: false, added, removed, modified, now });
  }
  return { state, report };
}
const isStale = (r) =>
  r.never || !Object.keys(r.now).length || r.added.length || r.removed.length || r.modified.length;

function printNotes(report, quiet) {
  const stale = report.filter(isStale);
  if (!stale.length) {
    if (!quiet) console.log(report.length ? 'All notes reviewed against current source.' : 'No notes configured.');
    return 0;
  }
  console.log(`${stale.length} note(s) describe source that changed since their last review:`);
  for (const r of stale) {
    if (!Object.keys(r.now).length) {
      console.log(`  ${r.doc} — its globs match NO files; fix "notes" in the config`);
      continue;
    }
    if (r.never) {
      console.log(`  ${r.doc} — never reviewed (${Object.keys(r.now).length} source files)`);
      continue;
    }
    console.log(`  ${r.doc}`);
    const show = (label, list) => {
      for (const f of list.slice(0, 15)) console.log(`      ${label} ${f}`);
      if (list.length > 15) console.log(`      … and ${list.length - 15} more`);
    };
    show('M', r.modified);
    show('A', r.added);
    show('D', r.removed);
  }
  console.log('Re-read each doc against those files, fix what is wrong, then: node scripts/codemap.mjs review <doc>');
  return stale.length;
}

// Writes in the file's existing line-ending style. The original always wrote LF
// into a repo that stores CRLF, so every hook run left a zero-content diff.
function writePreservingEol(path, text) {
  let crlf = cfg.eol === 'crlf';
  if (cfg.eol !== 'lf' && cfg.eol !== 'crlf') {
    try {
      crlf = read(path).includes('\r\n');
    } catch {
      crlf = false;
    }
  }
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, crlf ? text.replace(/\n/g, '\r\n') : text);
}

if (command === 'changes') {
  const n = printNotes(noteStatus().report, flag('--quiet'));
  process.exit(n && flag('--strict') ? 1 : 0);
}

if (command === 'review') {
  const { state, report } = noteStatus();
  const known = new Set(report.map((r) => r.doc));
  for (const d of cmdArgs)
    if (!known.has(d)) {
      console.error(`"${d}" is not a key under "notes" in the config.`);
      process.exit(2);
    }
  const targets = cmdArgs.length ? cmdArgs : [...known];
  for (const r of report) if (targets.includes(r.doc)) state[r.doc] = { files: r.now };
  const sorted = Object.fromEntries(Object.keys(state).sort().map((k) => [k, state[k]]));
  writePreservingEol(STATE_PATH, JSON.stringify(sorted, null, 2) + '\n');
  console.log(`Marked reviewed: ${targets.join(', ')}`);
  process.exit(0);
}

if (command !== 'generate' && command !== 'check') {
  console.error(`Unknown command "${command}". Use generate | check | changes | review | init.`);
  process.exit(2);
}

/* ============================================================== THE INDEX */
const stripLineComments = (s) =>
  s.split(/\r?\n/).map((l) => (/^\s*\/\//.test(l) ? '' : l.replace(/\/\/.*$/, '')));
// Keeps the line count intact so reported line numbers stay true to the file.
const blankBlockComments = (s) => s.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
// Comments are dropped before matching: names mentioned in prose would
// otherwise produce phantom call edges.
const codeLines = (s) => stripLineComments(blankBlockComments(s));
const depthDelta = (L) => (L.match(/\{/g) || []).length - (L.match(/\}/g) || []).length;
const allMatches = (res, L) => res.flatMap((re) => [...L.matchAll(re)].map((m) => m[1]));

/* ---------------------------------------------------------------- 1. routes */
// Convention-based routing: the action name IS the route segment.
const C = cfg.controllers || {};
const controllerFiles = selectLayer("controllers", C.files);
const backendCallRe = regexes(C.backendCall);
const routes = new Map(); // name -> { verbs:Set, blCalls:Set, file, line, overloads }
const verbOf = (r) => [...r.verbs].sort().join("/") + (r.overloads > 1 ? ` ×${r.overloads}` : "");
// `declRegex` lets a non-C#-shaped stack override the decl pattern (Kotlin has no
// `public` keyword and no `[HttpVerb]` attribute); `requireVerb: false` treats every
// matching declaration as a route on its own, verb always `CALL`.
const controllerDeclRe = C.declRegex
  ? new RegExp(arr(C.declRegex)[0])
  : /^\s*public\s+(?:async\s+)?[\w<>,\[\]\s.?]+?\s+(\w+)\s*\(/;
const requireVerb = C.requireVerb !== false;

for (const f of controllerFiles) {
  const lines = codeLines(read(f));
  let pendingVerb = requireVerb ? null : 'CALL';
  let cur = null;
  let depth = 0;
  let enteredBody = false;
  for (let i = 0; i < lines.length; i++) {
    const L = lines[i];
    if (requireVerb) {
      const verb = L.match(/\[\s*Http(Get|Post|Put|Delete|Patch)\s*\]/);
      if (verb) {
        pendingVerb = verb[1].toUpperCase();
        continue;
      }
    }
    const decl = L.match(controllerDeclRe);
    if (decl && pendingVerb) {
      // Overloads share one route entry: the first declaration is the address,
      // and every overload's verbs and backend calls are merged into it.
      cur = routes.get(decl[1]);
      if (!cur) {
        cur = { name: decl[1], verbs: new Set(), blCalls: new Set(), file: rel(f), line: i + 1, overloads: 0 };
        routes.set(cur.name, cur);
      }
      cur.verbs.add(pendingVerb);
      cur.overloads++;
      pendingVerb = requireVerb ? null : 'CALL';
      depth = 0;
      enteredBody = false;
    }
    if (cur) {
      depth += depthDelta(L);
      if (depth > 0) enteredBody = true;
      for (const n of allMatches(backendCallRe, L)) cur.blCalls.add(n);
      // Close out once the body's own braces return to baseline — not just on
      // depth<0 — so a method the decl regex fails to recognize (an unusual
      // return type, say) can never have its lines misattributed to whatever
      // method happened to match last.
      if (depth < 0 || (enteredBody && depth === 0)) cur = null;
    }
  }
}

/* ------------------------------------------------------- 2. backend methods */
const B = cfg.backend || {};
const backendFiles = selectLayer("backend", B.files);
const procRefRe = regexes(B.procRef);
// `inlineSql`: true = the default detector below, false = off, or a regex string
// for stacks whose inline SQL also writes (insert/update) or uses other quoting.
const inlineSqlRe =
  B.inlineSql === false ? null : typeof B.inlineSql === 'string' ? new RegExp(B.inlineSql, 'i') : /"[^"]*\bSELECT\b/i;
const blMethods = new Map(); // name -> { file, line, procs:Set, directSql:number }

// Stacks with no stored-proc/route layer at all (e.g. a desktop app's page ->
// repository-method -> inline-SQL-table shape) have nothing for `controllers`
// or `sql` to point at. When both are left out of the config, each backend
// method stands in as its own "route" and its own "proc": the method name is
// the address frontend calls, and the tables its own body touches are its
// table list directly, with no proc hop in between.
const collapsedRoutes = !C.files || arr(C.files).length === 0;
const collapsedProcs = !cfg.sql || arr(cfg.sql.files).length === 0;

// name -> { defs:[{file,line}], reads:Set, writes:Set, ctes:Set, aliases:Map }
// Declared here (rather than down in section 3) so the collapsed-procs mode
// below can populate it directly from backend-method bodies.
const procs = new Map();
const SQL_NOISE = new Set([
  'SELECT', 'VALUES', 'INSERTED', 'DELETED', 'OPENJSON', 'STRING_SPLIT',
  'SET', 'WITH', 'AS', 'ON', 'WHERE', 'AND', 'OR', 'NOT', 'EXISTS',
]);
// Words that can follow `FROM x` / `JOIN x` and are therefore not an alias.
const ALIAS_STOP = new Set([
  'WHERE', 'ON', 'JOIN', 'INNER', 'LEFT', 'RIGHT', 'FULL', 'CROSS', 'OUTER', 'WITH', 'GROUP', 'ORDER',
  'HAVING', 'UNION', 'SET', 'AND', 'OR', 'SELECT', 'AS', 'PIVOT', 'UNPIVOT', 'APPLY', 'EXCEPT',
  'INTERSECT', 'OPTION', 'INTO', 'VALUES', 'WHEN', 'THEN', 'ELSE', 'END', 'FOR', 'OUTPUT', 'BEGIN',
  'IF', 'RETURN', 'DECLARE', 'INSERT', 'UPDATE', 'DELETE', 'EXEC', 'EXECUTE', 'GO', 'NOT', 'IS', 'TABLESAMPLE',
]);
const cleanIdent = (t) => t.replace(/[\[\]]/g, '').replace(/^dbo\./i, '').replace(/[;,()]/g, '').trim();
// Shared by the SQL-proc-file loop (section 3) and the collapsed-procs mode
// here: CTE names, then aliases, then the actual reads/writes on one line.
function scanSqlLine(L, cur) {
  for (const m of L.matchAll(/(?:\bWITH\s+|,\s*|^\s*)\[?(\w+)\]?\s+AS\s*(?:\(|$)/gi)) cur.ctes.add(m[1].toUpperCase());
  for (const m of L.matchAll(/\b(?:FROM|JOIN)\s+([\[\]\w.@#]+)\s+(?:AS\s+)?\[?(\w+)\]?/gi)) {
    if (!ALIAS_STOP.has(m[2].toUpperCase())) cur.aliases.set(m[2].toUpperCase(), cleanIdent(m[1]));
  }
  for (const m of L.matchAll(/(?<!\bFETCH\s+(?:(?:NEXT|PRIOR|FIRST|LAST)\s+)?)\b(FROM|JOIN|INSERT\s+INTO|UPDATE|DELETE\s+FROM|DELETE|MERGE(?:\s+INTO)?)\s+([\[\]\w.@#]+)(\s*\()?/gi)) {
    const kw = m[1].toUpperCase().replace(/\s+/g, ' ');
    const t = cleanIdent(m[2]);
    if (!t || t.startsWith('@') || t.startsWith('#') || SQL_NOISE.has(t.toUpperCase())) continue;
    if (m[3] && !/^(INSERT|MERGE)/.test(kw)) {
      if (kw === 'FROM' || kw === 'JOIN') cur.reads.add(`${t}()`);
      continue;
    }
    (kw === 'FROM' || kw === 'JOIN' ? cur.reads : cur.writes).add(t);
  }
}
function dealiasProc(p) {
  for (const s of [p.reads, p.writes])
    for (const t of [...s]) {
      const key = t.toUpperCase();
      if (p.ctes.has(key)) s.delete(t);
      else if (p.aliases.has(key) && p.aliases.get(key).toUpperCase() !== key) {
        s.delete(t);
        const real = p.aliases.get(key);
        if (!p.ctes.has(real.toUpperCase()) && !/^[@#]/.test(real)) s.add(real);
      }
    }
}

// Room DAOs (Kotlin): methods are abstract — the SQL lives in a `@Query(...)`
// annotation *above* the declaration, not in a body below it, so the C#-shaped
// brace-depth loop below cannot see it at all. Multiple `interface FooDao { }`
// blocks can share one file (this project's `SupportDaos.kt` does), and
// method names collide freely across DAOs (`observe`, `get`, `upsert`, …), so
// each method is keyed by its accessor shape (`fooDao().method`) — exactly how
// the repository layer calls it (`db.fooDao().method(...)`) — never by the
// bare name, or two different DAOs' same-named methods would merge into one
// address and silently mix their tables together.
if (B.kotlinRoomDao) {
  const lowerFirst = (s) => s.charAt(0).toLowerCase() + s.slice(1);
  const extractQueryText = (buf) => {
    const triple = buf.match(/"""([\s\S]*?)"""/);
    if (triple) return triple[1];
    const single = buf.match(/"((?:[^"\\]|\\.)*)"/);
    return single ? single[1] : '';
  };
  for (const f of backendFiles) {
    const lines = codeLines(read(f));
    let iface = null;
    let collecting = false;
    let buffer = '';
    let parenDepth = 0;
    let pendingSql = null;
    for (let i = 0; i < lines.length; i++) {
      const L = lines[i];
      const ifaceDecl = L.match(/^\s*interface\s+(\w+)/);
      if (ifaceDecl) {
        iface = ifaceDecl[1];
        pendingSql = null;
        collecting = false;
        continue;
      }
      if (collecting) {
        buffer += '\n' + L;
        parenDepth += (L.match(/\(/g) || []).length - (L.match(/\)/g) || []).length;
        if (parenDepth <= 0) {
          collecting = false;
          pendingSql = extractQueryText(buffer);
        }
        continue;
      }
      if (/@Query\s*\(/.test(L)) {
        buffer = L.slice(L.indexOf('@Query'));
        parenDepth = (buffer.match(/\(/g) || []).length - (buffer.match(/\)/g) || []).length;
        if (parenDepth <= 0) pendingSql = extractQueryText(buffer);
        else collecting = true;
        continue;
      }
      const decl = L.match(/^\s*(?:suspend\s+)?fun\s+(\w+)\s*\(/);
      if (decl && iface) {
        const name = `${lowerFirst(iface)}().${decl[1]}`;
        if (!blMethods.has(name))
          blMethods.set(name, {
            name, file: rel(f), line: i + 1, procs: new Set(), directSql: 0,
            reads: new Set(), writes: new Set(), ctes: new Set(), aliases: new Map(),
          });
        const m = blMethods.get(name);
        if (pendingSql) {
          for (const ql of pendingSql.split(/\r?\n/)) scanSqlLine(ql, m);
          m.directSql++;
        }
      }
      pendingSql = null;
    }
  }
} else {
  for (const f of backendFiles) {
    const lines = codeLines(read(f));
    let cur = null;
    let depth = 0;
    let enteredBody = false;
    for (let i = 0; i < lines.length; i++) {
      const L = lines[i];
      const decl = L.match(
        /^\s*public\s+(?:static\s+|async\s+|virtual\s+|override\s+)*[\w<>,\[\]\s.?]+?\s+(\w+)\s*\(/
      );
      if (decl && !/^\s*public\s+(?:static\s+|partial\s+)*(class|enum|interface|struct|record)\b/.test(L)) {
        const name = decl[1];
        // Overloads collapse onto one entry; the address is the same either way.
        if (!blMethods.has(name))
          blMethods.set(name, {
            name, file: rel(f), line: i + 1, procs: new Set(), directSql: 0,
            reads: new Set(), writes: new Set(), ctes: new Set(), aliases: new Map(),
          });
        cur = blMethods.get(name);
        depth = 0;
        enteredBody = false;
      }
      if (cur) {
        depth += depthDelta(L);
        if (depth > 0) enteredBody = true;
        for (const n of allMatches(procRefRe, L)) cur.procs.add(n);
        // A SELECT inside a string literal that is not an EXEC = direct SQL.
        if (inlineSqlRe && inlineSqlRe.test(L) && !/\bEXEC(?:UTE)?\s+\w/i.test(L))
          cur.directSql++;
        if (collapsedProcs) scanSqlLine(L, cur);
        // Close out once the body's own braces return to baseline — not just on
        // depth<0 — so a method the decl regex fails to recognize (an unusual
        // return type, say) can never have its lines misattributed to whatever
        // method happened to match last.
        if (depth < 0 || (enteredBody && depth === 0)) cur = null;
      }
    }
  }
}

if (collapsedProcs)
  for (const m of blMethods.values())
    if (m.reads.size || m.writes.size) {
      dealiasProc(m);
      procs.set(m.name, { defs: [{ file: m.file, line: m.line }], reads: m.reads, writes: m.writes, ctes: m.ctes, aliases: m.aliases });
      m.procs.add(m.name);
    }

if (collapsedRoutes)
  for (const m of blMethods.values())
    routes.set(m.name, { name: m.name, verbs: new Set(['CALL']), blCalls: new Set([m.name]), file: m.file, line: m.line, overloads: 1 });

/* ------------------------------------------------- 3. procs -> tables (SQL) */
const sqlFiles = selectLayer("sql", (cfg.sql || {}).files);

for (const f of sqlFiles) {
  // Block comments are blanked first: header prose like "CREATE PROCEDURE body
  // for it appears in…" otherwise registers a proc literally named `body`.
  const lines = blankBlockComments(read(f)).split(/\r?\n/);
  let cur = null;
  for (let i = 0; i < lines.length; i++) {
    const L = lines[i].replace(/--.*$/, '');
    const create = L.match(/^\s*(?:CREATE|ALTER)\s+(?:OR\s+ALTER\s+)?PROC(?:EDURE)?\s+(?:\[?dbo\]?\.)?\[?(\w+)\]?/i);
    if (create) {
      const name = create[1];
      if (!procs.has(name)) procs.set(name, { defs: [], reads: new Set(), writes: new Set(), ctes: new Set(), aliases: new Map() });
      cur = procs.get(name);
      cur.defs.push({ file: rel(f), line: i + 1 });
      continue;
    }
    if (/^\s*GO\s*$/i.test(L)) {
      cur = null;
      continue;
    }
    if (!cur) continue;
    // `FETCH NEXT FROM cursor` reads a cursor, not a table; CTE/alias names
    // drop out of the reads/writes sets in the dealiasing pass below.
    scanSqlLine(L, cur);
  }
}

for (const p of procs.values()) dealiasProc(p);

/* ------------------------------------------------------- 4. frontend -> API */
const F = cfg.frontend || {};
const feFiles = selectLayer("frontend", F.files);
const routeRefRe = regexes(F.routeRef);
const callers = new Map(); // routeName -> Set(relative file)
for (const f of feFiles) {
  for (const n of allMatches(routeRefRe, read(f))) {
    if (!callers.has(n)) callers.set(n, new Set());
    callers.get(n).add(rel(f));
  }
}
const ignoreRe = F.ignore ? new RegExp(F.ignore, 'i') : null;
const isIgnored = (p) => !!ignoreRe && ignoreRe.test(p);
const shortFe = (p) => (F.stripPrefix && p.startsWith(F.stripPrefix) ? p.slice(F.stripPrefix.length) : p);

/* ----------------------------------------------------------- 5. join + emit */
const procsCalledFromCs = new Set();
for (const m of blMethods.values()) for (const p of m.procs) procsCalledFromCs.add(p);

const tablesOf = (procNames) => {
  const r = new Set(), w = new Set();
  for (const pn of procNames) {
    const p = procs.get(pn);
    if (!p) continue;
    for (const t of p.reads) r.add(t);
    for (const t of p.writes) w.add(t);
  }
  return { reads: r, writes: w };
};

const out = [];
const fmt = (set) => {
  const a = [...set].sort();
  return a.length ? a.map((x) => `\`${x}\``).join('<br>') : '—';
};
const oracle = cfg.oracle ? `\`${cfg.oracle}\`` : 'the project docs';

out.push('# CODEMAP — generated address directory');
out.push('');
out.push('> **GENERATED FILE — do not edit by hand.** Regenerate with `node scripts/codemap.mjs`;');
out.push('> `node scripts/codemap.mjs check` exits non-zero if it is stale.');
out.push('>');
out.push('> This is an **index**, not a source of truth. It records *where things live and what');
out.push('> calls what* — never what any of it means. For meaning, the oracle is');
out.push(`> ${oracle}.`);
out.push('>');
out.push('> Carries no timestamp or commit hash by design: identical source ⇒ identical file, so an');
out.push('> empty `git diff` after a run is a real staleness check.');
out.push('');
out.push('**Known limits of the extraction** — it is regex over source, not a compiler:');
out.push('');
out.push('- Controller → backend is followed **one hop only**. A backend method that reaches the DB');
out.push('  through another backend method shows no procs of its own.');
out.push('- Proc → proc calls are not followed; §8 reflects table references inside each proc body.');
out.push('- Dynamic SQL, and any proc or table named only in a variable, are invisible.');
out.push('- §6 lists *where* a proc is defined more than once, not whether the bodies differ.');
out.push('');
out.push('## 1. Stack traces — frontend → route → backend → proc → tables');
out.push('');
out.push(`| Route (\`${C.routeLabel || '…'}\`) | Verb | Called from | Backend method | Procs | Writes | Reads |`);
out.push('|---|---|---|---|---|---|---|');

for (const name of [...routes.keys()].sort()) {
  const r = routes.get(name);
  const cs = [...(callers.get(name) || [])].filter((p) => !isIgnored(p)).sort();
  const allProcs = new Set();
  const blCells = [];
  let anyDirect = false;
  for (const bn of [...r.blCalls].sort()) {
    const m = blMethods.get(bn);
    if (m) {
      blCells.push(`[\`${bn}\`](${m.file}#L${m.line})`);
      for (const p of m.procs) allProcs.add(p);
      if (m.directSql > 0) anyDirect = true;
    } else blCells.push(`\`${bn}\` ⚠️`);
  }
  const { reads, writes } = tablesOf(allProcs);
  // Never a bare "—" for a method that reaches the DB by inline SQL: an empty
  // cell would read as "touches no tables", the exact silent failure this map
  // exists to prevent.
  const cell = (set) => (set.size ? fmt(set) : anyDirect ? '_direct SQL, see §7_' : '—');
  out.push(
    `| [\`${name}\`](${r.file}#L${r.line}) | ${verbOf(r)} | ` +
      `${cs.length ? cs.map((p) => `\`${shortFe(p)}\``).join('<br>') : '— ⚠️'} | ` +
      `${blCells.join('<br>') || '—'} | ${cell(allProcs)} | ${cell(writes)} | ${cell(reads)} |`
  );
}

/* --------- drift detectors: sections 2-4 should normally read "None" -------- */
// A repo with no `sql` layer (inline SQL only, or procs kept elsewhere) gets
// the proc sections marked not-applicable rather than every proc listed as
// "defined nowhere".
const HAS_SQL = arr((cfg.sql || {}).files).length > 0;
const NO_SQL = '_Not applicable — no `sql` layer configured._';
const section = (title, note, rows, needsSql = false) => {
  out.push('', `## ${title}`, '', note, '');
  if (needsSql && !HAS_SQL) out.push(NO_SQL);
  else if (!rows.length) out.push('_None._');
  else rows.forEach((r) => out.push(`- ${r}`));
};

section(
  '2. Frontend calls a route the controllers do not define',
  'Broken at runtime, or a route renamed on one side only.',
  [...callers.keys()]
    .filter((r) => !routes.has(r))
    .sort()
    .map((r) => `\`${r}\` — called from ${[...callers.get(r)].sort().map((p) => `\`${shortFe(p)}\``).join(', ')}`)
);

section(
  '3. Route defined but never called from the frontend',
  'Dead endpoint, or called by a host page / another consumer outside this repo.',
  [...routes.keys()]
    .filter((r) => !(callers.get(r) && [...callers.get(r)].some((p) => !isIgnored(p))))
    .sort()
    .map((r) => `\`${r}\` (${verbOf(routes.get(r))})`)
);

section(
  '4. Proc referenced from the backend but defined in no `.sql` in this repo',
  'Fails at runtime unless the proc exists only on the server (or is owned by another module).',
  [...procsCalledFromCs]
    .filter((p) => !procs.has(p))
    .sort()
    .map(
      (p) =>
        `\`${p}\` — referenced by ${[...blMethods.values()]
          .filter((m) => m.procs.has(p))
          .map((m) => `\`${m.name}\``)
          .sort()
          .join(', ')}`
    ),
  true
);

section(
  '5. Proc defined but never referenced from the backend',
  'Unused, called by another proc, or called by a consumer outside this repo.',
  [...procs.keys()]
    .filter((p) => !procsCalledFromCs.has(p))
    .sort()
    .map((p) => `\`${p}\` — ${procs.get(p).defs.map((d) => `${d.file}:${d.line}`).join(', ')}`),
  true
);

section(
  '6. Proc defined in more than one `.sql` file',
  'Expected when incremental and consolidated deploy scripts sit side by side; listed so a ' +
    'divergent body is visible. Whichever definition is applied last wins.',
  [...procs.keys()]
    .filter((p) => new Set(procs.get(p).defs.map((d) => d.file)).size > 1)
    .sort()
    .map((p) => `\`${p}\` — ${procs.get(p).defs.map((d) => `${d.file}:${d.line}`).join(' · ')}`),
  true
);

section(
  '7. Backend methods issuing SQL directly instead of via a proc',
  collapsedProcs
    ? 'No separate proc/`.sql` layer in this repo — each method\'s own tables are already in sections 1 and 8.'
    : 'These bypass every proc-level guard, so sections 1 and 8 cannot see the tables they touch.',
  [...blMethods.values()]
    .filter((m) => m.directSql > 0)
    .sort((a, b) => b.directSql - a.directSql || a.name.localeCompare(b.name))
    .map((m) => `[\`${m.name}\`](${m.file}#L${m.line}) — ${m.directSql} inline statement(s)`)
);

/* ------------------------------------------- 8. reverse index: table -> procs */
out.push('', '## 8. Table → which procs touch it', '');
out.push(
  collapsedProcs
    ? 'Reverse index over each backend method\'s own inline SQL (there is no separate proc layer here).'
    : 'Reverse index over proc bodies. Direct SQL from section 7 is **not** represented here.'
);
out.push('');
if (!HAS_SQL && !collapsedProcs) out.push(NO_SQL);
else {
  out.push('| Table | Written by | Read by |');
  out.push('|---|---|---|');
}
const tables = new Map();
const bucket = (t) => {
  if (!tables.has(t)) tables.set(t, { w: new Set(), r: new Set() });
  return tables.get(t);
};
for (const [pn, p] of procs) {
  for (const t of p.writes) bucket(t).w.add(pn);
  for (const t of p.reads) bucket(t).r.add(pn);
}
const ownRe = cfg.ownTables ? new RegExp(cfg.ownTables, 'i') : null;
for (const t of [...tables.keys()].sort()) {
  const e = tables.get(t);
  const own = !ownRe || ownRe.test(t) ? '' : ' _(external)_';
  out.push(`| \`${t}\`${own} | ${fmt(e.w)} | ${fmt(e.r)} |`);
}

out.push('', '---', '');
out.push(
  `Indexed ${routes.size} routes · ${blMethods.size} backend methods · ${procs.size} procs · ` +
    `${tables.size} tables · ${feFiles.length} frontend files · ${sqlFiles.length} SQL files.`
);
out.push('');

const text = out.join('\n');
const target = abs(opt('--out') || cfg.output || 'CODEMAP.md');

if (command === 'check') {
  let existing = '';
  try {
    existing = read(target);
  } catch {}
  const indexStale = normEol(existing) !== normEol(text);
  console.log(indexStale ? `${rel(target)} is STALE — run: node scripts/codemap.mjs` : `${rel(target)} is current.`);
  const staleNotes = printNotes(noteStatus().report, true);
  process.exit(indexStale || (staleNotes && flag('--strict')) ? 1 : 0);
}

writePreservingEol(target, text);
console.log(`Wrote ${rel(target)} — ${routes.size} routes, ${procs.size} procs, ${tables.size} tables.`);
