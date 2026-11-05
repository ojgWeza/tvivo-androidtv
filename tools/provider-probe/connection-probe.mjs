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
//   4a/6. only with --allow-overlap: the SAME stream opened twice, and a VOD file read twice (play + probe/download)
//   4. only with --allow-overlap: opens stream B (different channel) from the SAME IP while A is held
//        accepted -> the panel does not count per call (looks per device/IP or unenforced)
//        refused  -> it counts per call
//   5. closes everything and polls active_cons until it returns to baseline (how long a slot stays "busy")
// Output is one JSON object. No URLs with credentials, no tokens.

import dns from 'node:dns';
import net from 'node:net';
// curl reaches the provider in ~0.2 s while Node's default dual-stack connect can time out: prefer IPv4, wait longer per address.
dns.setDefaultResultOrder('ipv4first');
try { net.setDefaultAutoSelectFamilyAttemptTimeout(2000); } catch {}

// Minimal fetch replacement on node:http/https: forces IPv4, follows redirects, no connection pooling.
// (Node's built-in fetch timed out connecting here while raw sockets and curl worked.)
import http from 'node:http';
import https from 'node:https';
function hfetch(rawUrl, init = {}, hops = 0) {
  return new Promise((resolve, reject) => {
    const u = new URL(rawUrl);
    const mod = u.protocol === 'https:' ? https : http;
    const signal = init.signal;
    const fail = e => reject(e);
    if (signal?.aborted) { const e = new Error('aborted'); e.name = 'AbortError'; return fail(e); }
    const req = mod.request(u, { method: init.method || 'GET', headers: init.headers || {}, family: 4, agent: false, timeout: init.idleMs || 90000 }, res => {
      const code = res.statusCode || 0;
      if ([301, 302, 303, 307, 308].includes(code) && res.headers.location && hops < 5) {
        res.resume();
        return hfetch(new URL(res.headers.location, u).toString(), init, hops + 1).then(resolve, reject);
      }
      const response = {
        status: code, ok: code >= 200 && code < 300,
        headers: { get: n => { const v = res.headers[String(n).toLowerCase()]; return v === undefined ? null : String(v); } },
        body: {
          getReader() {
            const it = res[Symbol.asyncIterator]();
            return { read: async () => { const r = await it.next(); return r.done ? { done: true, value: undefined } : { done: false, value: r.value }; } };
          },
          cancel: async () => { res.destroy(); }
        },
        json: async () => { const parts = []; for await (const c of res) parts.push(c); return JSON.parse(Buffer.concat(parts).toString('utf8')); }
      };
      if (signal) signal.addEventListener('abort', () => res.destroy(Object.assign(new Error('aborted'), { name: 'AbortError' })), { once: true });
      resolve(response);
    });
    req.on('timeout', () => req.destroy(Object.assign(new Error('connect/idle timeout'), { name: 'TimeoutError' })));
    req.on('error', fail);
    if (signal) signal.addEventListener('abort', () => req.destroy(Object.assign(new Error(signal.reason?.message || 'aborted'), { name: signal.reason?.name || 'AbortError' })), { once: true });
    req.end();
  });
}
const connectFailures = [];
async function hfetchRetry(url, init, label) {
  let last;
  for (let i = 0; i < 5; i++) {
    try { return await hfetch(url, init); }
    catch (e) {
      last = e;
      const code = e.code || e.name;
      if (!['ETIMEDOUT', 'ECONNRESET', 'ECONNREFUSED', 'TimeoutError', 'EAI_AGAIN'].includes(code)) throw e;
      connectFailures.push({ label: label || 'call', attempt: i + 1, code, atSec: Math.round((Date.now() - t0) / 1000) });
      await new Promise(r => setTimeout(r, 4000));
    }
  }
  throw last;
}
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
  const res = await hfetchRetry(api(), { signal: AbortSignal.timeout(60000) }, 'user_info');
  const j = await res.json();
  const u = j.user_info || {};
  return { http: res.status, status: u.status, maxConnections: Number(u.max_connections), activeCons: Number(u.active_cons) };
}

async function openHeld(url, label) {
  const controller = new AbortController();
  const started = Date.now();
  try {
    const res = await hfetch(url, { signal: controller.signal, headers: { 'User-Agent': 'tvivo-connection-probe' } });
    if (!res.ok) { controller.abort(); return { label, http: res.status, held: false }; }
    const reader = res.body.getReader();
    await Promise.race([reader.read(), sleep(8000)]);
    return { label, http: res.status, held: true, ms: Date.now() - started, close: () => controller.abort() };
  } catch (e) {
    return { label, http: 0, held: false, error: String(e.name || e), cause: String(e.cause?.code || e.cause?.message || e.message || '') };
  }
}

async function probeStatus(url, init, label) {
  try {
    const res = await hfetchRetry(url, { ...init, signal: AbortSignal.timeout(20000) }, 'probe-' + label);
    const range = res.headers.get('content-range');
    try { await res.body?.cancel(); } catch {}
    return { label, http: res.status, contentRange: range ? 'present' : 'absent', contentLength: res.headers.get('content-length') ? 'present' : 'absent' };
  } catch (e) { return { label, http: 0, error: String(e.name || e) }; }
}

// Reads a stream continuously (discarding bytes) and counts them, so we can see whether it keeps flowing.
function openCounting(url, label) {
  const controller = new AbortController();
  const st = { label, bytes: 0, ended: false, error: null, http: 0, firstByteMs: null, t0: Date.now() };
  const done = (async () => {
    try {
      const res = await hfetch(url, { signal: controller.signal, headers: { 'User-Agent': 'tvivo-connection-probe' } });
      st.http = res.status;
      if (!res.ok) { st.ended = true; return; }
      const reader = res.body.getReader();
      for (;;) { const { done: d, value } = await reader.read(); if (d) { st.ended = true; break; } if (st.firstByteMs === null) st.firstByteMs = Date.now() - st.t0; st.bytes += value.length; }
    } catch (e) { if (!controller.signal.aborted) { st.error = String(e.name || e); st.ended = true; } }
  })();
  return { st, close: () => controller.abort(), done };
}

async function keepReadingPair(urlA, urlB, name) {
  const a = openCounting(urlA, 'A');
  await sleep(4000);
  const a0 = a.st.bytes;
  await sleep(4000);
  const aRate = Math.round((a.st.bytes - a0) / 4);
  step(name + ' A alone: bytes/s over 4 s', { http: a.st.http, bytesPerSec: aRate });
  const b = openCounting(urlB, 'B');
  await sleep(3000);
  const a1 = a.st.bytes, b1 = b.st.bytes;
  await sleep(6000);
  step(name + ' A and B together over 6 s', {
    aHttp: a.st.http, bHttp: b.st.http,
    aBytesPerSec: Math.round((a.st.bytes - a1) / 6), bBytesPerSec: Math.round((b.st.bytes - b1) / 6),
    aEnded: a.st.ended, bEnded: b.st.ended, aError: a.st.error, bError: b.st.error
  });
  step(name + ' active_cons with both reading', await userInfo());
  const verdict = a.st.ended ? 'A WAS CUT when B opened (provider keeps only the newest)'
    : (a.st.bytes - a1) / 6 < aRate * 0.2 ? 'A starved when B opened' : 'A kept flowing; both streams ran together';
  step(name + ' verdict', { verdict });
  a.close(); b.close();
  await Promise.allSettled([a.done, b.done]);
}

try {
  const base0 = await userInfo();
  step('0 baseline user_info', base0);
  if (base0.activeCons > 0) step('0 warning', { note: 'active_cons already > 0 at baseline: another device/app may be streaming; results are less clean' });

  const listRes = await hfetch(api('&action=get_live_streams'), { signal: AbortSignal.timeout(30000) });
  const live = await listRes.json();
  if (!Array.isArray(live) || live.length < 2) throw new Error('need at least 2 live streams');
  const urlFor = s => `${base}/live/${encodeURIComponent(user)}/${encodeURIComponent(pass)}/${s.stream_id}.ts`;
  const A = urlFor(live[0]);
  const Bu = urlFor(live[1]);

  const a = await openHeld(A, 'A');
  step('1 open stream A (held)', { http: a.http, held: a.held, error: a.error, cause: a.cause });
  if (!a.held) throw new Error('stream A was not accepted; stopping');

  await sleep(3000);
  step('2 active_cons while A held', await userInfo());

  step('3a HEAD same stream while A held', await probeStatus(A, { method: 'HEAD' }, 'HEAD'));
  step('3b Range 0-0 same stream while A held', await probeStatus(A, { method: 'GET', headers: { Range: 'bytes=0-0' } }, 'Range0-0'));
  step('3c active_cons after probes', await userInfo());

  let b = null;
  if (allowOverlap) {
    // 4a: the SAME stream opened a second time (what a shared/tee stream or a re-open would look like).
    const a2 = await openHeld(A, 'A-again');
    step('4a open the SAME stream A a second time while A held', { http: a2.http, held: a2.held, error: a2.error });
    await sleep(2000);
    step('4a active_cons with A + A-again', await userInfo());
    if (a2.held) a2.close();
    await sleep(6000);
    step('4a active_cons after closing A-again (A still held)', await userInfo());
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

  if (allowOverlap) {
    await sleep(6000);
    await keepReadingPair(A, Bu, '7 live');
    await sleep(8000);
  }

  if (allowOverlap) {
    await sleep(8000);
    // 8: does a HEAD / Range 0-0 / second open of the SAME live stream cut the stream that is being played?
    const play = openCounting(A, 'A');
    await sleep(4000);
    let mark = play.st.bytes; await sleep(3000);
    const base = Math.round((play.st.bytes - mark) / 3);
    step('8 A playing alone: bytes/s', { http: play.st.http, bytesPerSec: base });
    const survive = async (name, action) => {
      const before = play.st.bytes;
      const result = await action();
      await sleep(4000);
      const rate = Math.round((play.st.bytes - before) / 4);
      step(name, { ...result, playingBytesPerSec: rate, playingEnded: play.st.ended,
        verdict: play.st.ended ? 'PLAYING STREAM WAS CUT' : (rate < base * 0.2 ? 'playing stream starved' : 'playing stream survived') });
    };
    await survive('8a HEAD on the playing stream', () => probeStatus(A, { method: 'HEAD' }, 'HEAD'));
    await survive('8b Range 0-0 on the playing stream', () => probeStatus(A, { method: 'GET', headers: { Range: 'bytes=0-0' } }, 'Range0-0'));
    if (!play.st.ended) {
      const again = openCounting(A, 'A-again');
      await survive('8c second full open of the SAME stream (playing one tracked)', async () => ({ http: again.st.http }));
      step('8c second open itself', { http: again.st.http, bytes: again.st.bytes, ended: again.st.ended });
      again.close(); await Promise.allSettled([again.done]);
    }
    play.close(); await Promise.allSettled([play.done]);
  }

  if (allowOverlap) {
    await sleep(8000);
    // 9: switching back and forth between two live channels (close one, open the other), 6 turns of 5 s.
    const turns = [];
    for (let i = 0; i < 6; i++) {
      const url = i % 2 === 0 ? A : Bu;
      const c = openCounting(url, i % 2 === 0 ? 'A' : 'B');
      const started = Date.now();
      await sleep(5000);
      turns.push({ turn: i + 1, channel: c.st.label, http: c.st.http, firstByteMs: c.st.firstByteMs,
        bytesPerSec: Math.round(c.st.bytes / 5), ended: c.st.ended, error: c.st.error });
      c.close(); await Promise.allSettled([c.done]);
      await sleep(i % 2 === 0 ? 1000 : 3000); // short and longer gaps, to see whether the gap matters
    }
    step('9 live switching A,B,A,B,A,B (gaps 1 s / 3 s)', { turns });
    step('9 active_cons after switching', await userInfo());
  }

  if (allowOverlap) {
    // 6: VOD file held, then a size probe / second Range read of the SAME file (playing + probing/downloading it).
    try {
      const vr = await hfetch(api('&action=get_vod_streams'), { signal: AbortSignal.timeout(60000) });
      const vod = await vr.json();
      const v = Array.isArray(vod) ? vod.find(x => x.container_extension) : null;
      if (!v) { step('6 skipped', { note: 'no VOD item found' }); }
      else {
        const vu = `${base}/movie/${encodeURIComponent(user)}/${encodeURIComponent(pass)}/${v.stream_id}.${v.container_extension}`;
        await sleep(6000);
        const v1 = await openHeld(vu, 'VOD-1');
        step('6a open VOD file (held, like playing it)', { http: v1.http, held: v1.held, error: v1.error });
        await sleep(2000);
        step('6a active_cons with VOD held', await userInfo());
        step('6b HEAD same VOD while held', await probeStatus(vu, { method: 'HEAD' }, 'HEAD'));
        step('6c Range 0-0 same VOD while held', await probeStatus(vu, { method: 'GET', headers: { Range: 'bytes=0-0' } }, 'Range0-0'));
        const v2 = await openHeld(vu, 'VOD-2');
        step('6d second full read of the SAME VOD (like play + download)', { http: v2.http, held: v2.held, error: v2.error });
        await sleep(2000);
        step('6d active_cons with VOD x2', await userInfo());
        if (v2.held) v2.close();
        if (v1.held) v1.close();
      }
    } catch (e) { step('6 error', { message: String(e.message || e) }); }
  }
} catch (e) {
  step('error', { message: String(e.message || e), cause: String(e.cause?.code || e.cause?.message || e.code || ''), hint: 'If this is a timeout on step 0, check PROVIDER_BASE (scheme, host, port) and try opening it in a browser.' });
}
out.connectFailures = connectFailures;
const json = JSON.stringify(out, null, 2);
console.log(json);
// Also save the result so it can be read later: --out <file>, default tools/provider-probe/results/probe-<time>.json
try {
  const fs = await import('node:fs');
  const path = await import('node:path');
  const url = await import('node:url');
  const oi = process.argv.indexOf('--out');
  const file = oi > 0 ? process.argv[oi + 1]
    : path.join(path.dirname(url.fileURLToPath(import.meta.url)), 'results', 'probe-' + out.startedAt.replace(/[:.]/g, '-') + '.json');
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, json);
  console.error('Saved result to ' + file);
} catch (e) { console.error('Could not save result file: ' + (e.message || e)); }
process.exit(0);
