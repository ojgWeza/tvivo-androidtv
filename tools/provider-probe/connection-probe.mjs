#!/usr/bin/env node
// Safe, short probe for HOW a provider counts "connections" (per call? per IP/device?).
// Dependency-free. Credentials come ONLY from env vars and are never printed.
//
//   PROVIDER_BASE=http://host:port PROVIDER_USER=u PROVIDER_PASS=p node connection-probe.mjs [--allow-overlap] [--watch-seconds 90]
//
// What it does (about 2 minutes, one held stream, tiny reads):
//   0. reads user_info (max_connections, active_cons) - no stream opened
//   1. opens stream A and holds it (reads one small chunk, then stops reading)
//   2. while A is held: re-reads active_cons  -> does ONE held call count as 1 connection?
//   3. while A is held: HEAD, then GET "Range: bytes=0-0" on the SAME stream -> does a header-only probe count / get refused?
//   4. only with --allow-overlap: opens stream B (different channel) from the SAME IP while A is held
//        accepted -> the panel does not count per call (looks per device/IP or unenforced)
//        refused  -> it counts per call
//   5. closes everything and polls active_cons until it returns to baseline (how long a slot stays "busy")
// Output is one JSON object. No URLs with credentials, no tokens.

const base = (process.env.PROVIDER_BASE || '').replace(/\/+$/, '');
const user = process.env.PROVIDER_USER || '';
const pass = process.env.PROVIDER_PASS || '';
const allowOverlap = process.argv.includes('--allow-overlap');
const wi = process.argv.indexOf('--watch-seconds');
const watchSeconds = wi > 0 ? Number(process.argv[wi + 1]) : 90;
if (!base || !user || !pass) {
  console.error('Set PROVIDER_BASE, PROVIDER_USER, PROVIDER_PASS (env only).');
  process.exit(2);
}

const sleep = ms => new Promise(r => setTimeout(r, ms));
const api = (extra = '') => `${base}/player_api.php?username=${encodeURIComponent(user)}&password=${encodeURIComponent(pass)}${extra}`;
const out = { schema: 'connection-probe/1', startedAt: new Date().toISOString(), allowOverlap, steps: [] };
const step = (name, data) => { out.steps.push({ name, t: Math.round((Date.now() - t0) / 1000), ...data }); };
const t0 = Date.now();

async function userInfo() {
  const res = await fetch(api(), { signal: AbortSignal.timeout(30000) });
  const j = await res.json();
  const u = j.user_info || {};
  return { http: res.status, status: u.status, maxConnections: Number(u.max_connections), activeCons: Number(u.active_cons) };
}

async function openHeld(url, label) {
  const controller = new AbortController();
  const started = Date.now();
  try {
    const res = await fetch(url, { signal: controller.signal, headers: { 'User-Agent': 'tvivo-connection-probe' } });
    if (!res.ok) { controller.abort(); return { label, http: res.status, held: false }; }
    const reader = res.body.getReader();
    await Promise.race([reader.read(), sleep(8000)]);
    return { label, http: res.status, held: true, ms: Date.now() - started, close: () => controller.abort() };
  } catch (e) {
    return { label, http: 0, held: false, error: String(e.name || e) };
  }
}

async function probeStatus(url, init, label) {
  try {
    const res = await fetch(url, { ...init, signal: AbortSignal.timeout(6000) });
    const range = res.headers.get('content-range');
    try { await res.body?.cancel(); } catch {}
    return { label, http: res.status, contentRange: range ? 'present' : 'absent', contentLength: res.headers.get('content-length') ? 'present' : 'absent' };
  } catch (e) { return { label, http: 0, error: String(e.name || e) }; }
}

try {
  const base0 = await userInfo();
  step('0 baseline user_info', base0);
  if (base0.activeCons > 0) step('0 warning', { note: 'active_cons already > 0 at baseline: another device/app may be streaming; results are less clean' });

  const listRes = await fetch(api('&action=get_live_streams'), { signal: AbortSignal.timeout(30000) });
  const live = await listRes.json();
  if (!Array.isArray(live) || live.length < 2) throw new Error('need at least 2 live streams');
  const urlFor = s => `${base}/live/${encodeURIComponent(user)}/${encodeURIComponent(pass)}/${s.stream_id}.ts`;
  const A = urlFor(live[0]);
  const Bu = urlFor(live[1]);

  const a = await openHeld(A, 'A');
  step('1 open stream A (held)', { http: a.http, held: a.held, error: a.error });
  if (!a.held) throw new Error('stream A was not accepted; stopping');

  await sleep(3000);
  step('2 active_cons while A held', await userInfo());

  step('3a HEAD same stream while A held', await probeStatus(A, { method: 'HEAD' }, 'HEAD'));
  step('3b Range 0-0 same stream while A held', await probeStatus(A, { method: 'GET', headers: { Range: 'bytes=0-0' } }, 'Range0-0'));
  step('3c active_cons after probes', await userInfo());

  let b = null;
  if (allowOverlap) {
    b = await openHeld(Bu, 'B');
    step('4 open DIFFERENT stream B from same IP while A held', { http: b.http, held: b.held, error: b.error });
    await sleep(2000);
    step('4b active_cons with A+B', await userInfo());
    if (b.held) b.close();
  } else {
    step('4 skipped', { note: 'run with --allow-overlap to test a second simultaneous stream from this IP' });
  }

  a.close();
  const closedAt = Date.now();
  step('5 closed everything', {});
  let back = null;
  for (let s = 0; s <= watchSeconds; s += 5) {
    const u = await userInfo();
    if (u.activeCons <= base0.activeCons) { back = Math.round((Date.now() - closedAt) / 1000); step('5 active_cons back to baseline', { afterSeconds: back, ...u }); break; }
    await sleep(5000);
  }
  if (back === null) step('5 active_cons did NOT return to baseline within window', { watchSeconds });
} catch (e) {
  step('error', { message: String(e.message || e), hint: 'If this is a timeout on step 0, check PROVIDER_BASE (scheme, host, port) and try opening it in a browser.' });
}
console.log(JSON.stringify(out, null, 2));
process.exit(0);
