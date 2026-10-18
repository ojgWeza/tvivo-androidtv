#!/usr/bin/env node
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const host = process.env.MOCK_HOST || '0.0.0.0';
const port = Number(process.env.PORT || 8089);
const mediaDir = path.resolve(process.env.MEDIA_DIR || path.join(path.dirname(fileURLToPath(import.meta.url)), 'media'));
const username = process.env.DEMO_USERNAME || 'demo';
const password = process.env.DEMO_PASSWORD || 'demo';
const state = {
  maxConnections: int(process.env.MAX_CONNECTIONS, 1),
  rejectSecondStatus: int(process.env.REJECT_SECOND_STATUS, 403),
  stallAfterBytes: int(process.env.STALL_AFTER_BYTES, -1),
  stallSeconds: Number(process.env.STALL_SECONDS || 0),
  eofPercent: Number(process.env.EOF_PERCENT || 100),
  catalog429: int(process.env.CATALOG_429, 0),
  killSwitch: envBool(process.env.KILL_SWITCH),
};
let catalog429Remaining = state.catalog429;
const streams = new Set();

function int(value, fallback) { const n = Number(value); return Number.isFinite(n) ? Math.trunc(n) : fallback; }
function envBool(value) { return /^(1|true|yes|on)$/i.test(value || ''); }
function json(res, status, value) {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
  res.end(JSON.stringify(value));
}
function authorized(url) { return url.searchParams.get('username') === username && url.searchParams.get('password') === password; }
function withQuery(url, key, fallback) { const value = url.searchParams.get(key); return value == null ? fallback : Number(value); }
function snapshot() { return { ...state, catalog429Remaining, activeConnections: streams.size }; }

const liveCategories = [{ category_id: '101', category_name: 'Generated Live', parent_id: 0 }];
const vodCategories = [{ category_id: '201', category_name: 'Generated Movies', parent_id: 0 }];
const seriesCategories = [{ category_id: 301, category_name: 'Generated Series', parent_id: 0 }];
const vodStreams = Array.from({ length: 24 }, (_, index) => ({
  num: index + 1,
  name: `Synthetic Film ${String(index + 1).padStart(2, '0')}`,
  stream_id: 2001 + index,
  category_id: '201',
  stream_icon: '',
  container_extension: 'mp4',
  added: String(1700000001 + index),
}));
const series = Array.from({ length: 16 }, (_, index) => ({
  num: index + 1,
  name: `Synthetic Series ${String(index + 1).padStart(2, '0')}`,
  series_id: 3001 + index,
  category_id: 301,
  cover: '',
  last_modified: String(1700000002 + index),
}));
const episode = (id, n, title) => ({ id, episode_num: String(n), title, container_extension: 'mp4', duration: '00:02:00', duration_secs: 120 });
const seriesInfo = { seasons: [{ season_number: 1, name: 'Season 1' }], episodes: { '1': [episode('episode-A1', 1, 'Episode 1'), episode('episode-B1', 2, 'Episode 2')] } };
const liveStreams = Array.from({ length: 16 }, (_, index) => ({
  num: index + 1,
  name: index === 0 ? 'Generated Live Loop' : `Synthetic Live Channel ${String(index + 1).padStart(2, '0')}`,
  stream_id: 1001 + index,
  category_id: '101',
  stream_icon: '',
  ext: 'ts',
  added: String(1700000000 + index),
}));

function apiResponse(url) {
  const action = url.searchParams.get('action');
  switch (action) {
    case null: return { user_info: { username, auth: 1, status: 'Active', max_connections: String(state.maxConnections), active_cons: String(streams.size), exp_date: '2000000000' }, server_info: { url: '127.0.0.1', port: String(port), https_port: String(port), server_protocol: 'http' } };
    case 'get_live_categories': return liveCategories;
    case 'get_live_streams': return liveStreams;
    case 'get_vod_categories': return vodCategories;
    case 'get_vod_streams': return vodStreams;
    case 'get_vod_info': {
      const streamId = Number(url.searchParams.get('vod_id'));
      return vodStreams.some((item) => item.stream_id === streamId)
        ? { info: { plot: 'Generated test video; no third-party content.' }, movie_data: { stream_id: streamId, container_extension: 'mp4' } }
        : {};
    }
    case 'get_series_categories': return seriesCategories;
    case 'get_series': return series;
    case 'get_series_info': return series.some((item) => item.series_id === Number(url.searchParams.get('series_id'))) ? seriesInfo : {};
    default: return [];
  }
}

async function streamFile(req, res, url, kind, id, ext) {
  const file = kind === 'live' ? 'live.ts' : kind === 'movie' ? 'movie.mp4' : `${id}.mp4`;
  const fullPath = path.join(mediaDir, file);
  let stat;
  try { stat = await fs.promises.stat(fullPath); } catch { return json(res, 404, { error: `Missing generated media: ${file}` }); }
  if (streams.size >= state.maxConnections) return json(res, state.rejectSecondStatus, { user_info: { auth: 0 }, error: 'Maximum connections reached' });

  const idempotentRelease = () => { streams.delete(res); };
  streams.add(res);
  res.once('close', idempotentRelease);
  res.once('finish', idempotentRelease);

  const range = req.headers.range;
  let start = 0; let end = stat.size - 1; let status = 200;
  if (range) {
    const match = /^bytes=(\d*)-(\d*)$/.exec(range);
    if (!match || (!match[1] && !match[2])) { res.writeHead(416, { 'content-range': `bytes */${stat.size}` }).end(); return; }
    if (!match[1]) { const suffix = Number(match[2]); start = Math.max(0, stat.size - suffix); }
    else { start = Number(match[1]); if (match[2]) end = Math.min(end, Number(match[2])); }
    if (start >= stat.size || end < start) { res.writeHead(416, { 'content-range': `bytes */${stat.size}` }).end(); return; }
    status = 206;
  }
  const eofPercent = Math.max(0, Math.min(100, withQuery(url, 'eofPercent', state.eofPercent)));
  const selectedLength = end - start + 1;
  // EOF is a point in the whole file. Range retries must not move the cutoff forward.
  const eofByte = Math.floor(stat.size * eofPercent / 100);
  const byteLimit = Math.max(0, Math.min(selectedLength, eofByte - start));
  const loopLive = kind === 'live' && !range && eofPercent === 100;
  const stallAt = withQuery(url, 'stallAfterBytes', state.stallAfterBytes);
  const stallMs = Math.max(0, withQuery(url, 'stallSeconds', state.stallSeconds) * 1000);
  const mime = ext === 'ts' ? 'video/mp2t' : 'video/mp4';
  res.writeHead(status, {
    'content-type': mime,
    'accept-ranges': 'bytes',
    ...(loopLive ? {} : { 'content-length': selectedLength }),
    ...(status === 206 ? { 'content-range': `bytes ${start}-${end}/${stat.size}` } : {}),
    'cache-control': 'no-store',
  });
  if (req.method === 'HEAD' || byteLimit === 0) { res.end(); return; }
  let sent = 0;
  let stalled = false;
  let source;
  const stop = () => { source?.destroy(); idempotentRelease(); };
  req.once('aborted', stop);
  res.once('close', stop);
  res.on('drain', () => source?.resume());
  const pump = () => {
    if (res.destroyed) return;
    source = fs.createReadStream(fullPath, { start, end: start + byteLimit - 1, highWaterMark: 64 * 1024 });
    source.on('data', (chunk) => {
      if (!stalled && stallAt >= 0 && sent <= stallAt && sent + chunk.length >= stallAt && stallMs > 0) {
        source.pause();
        const prefix = chunk.subarray(0, Math.max(0, stallAt - sent));
        if (prefix.length) { sent += prefix.length; res.write(prefix); }
        stalled = true;
        const currentSource = source;
        setTimeout(() => { if (!res.destroyed) { const rest = chunk.subarray(prefix.length); if (rest.length) { sent += rest.length; res.write(rest); } currentSource.resume(); } }, stallMs).unref();
        return;
      }
      sent += chunk.length;
      if (!res.write(chunk)) source.pause();
    });
    source.on('end', () => { if (loopLive && !res.destroyed) pump(); else res.end(); });
    source.on('error', () => { if (!res.destroyed) res.destroy(); });
  };
  pump();
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  if (url.pathname === '/__ctl') {
    if (req.method === 'GET') return json(res, 200, snapshot());
    if (req.method !== 'POST') return json(res, 405, { error: 'Use GET or POST' });
    let raw = '';
    for await (const chunk of req) raw += chunk;
    let updates;
    try { updates = JSON.parse(raw || '{}'); } catch { return json(res, 400, { error: 'Expected JSON object' }); }
    const allowed = ['maxConnections', 'rejectSecondStatus', 'stallAfterBytes', 'stallSeconds', 'eofPercent', 'catalog429', 'killSwitch'];
    for (const key of allowed) if (key in updates) state[key] = key === 'killSwitch' ? Boolean(updates[key]) : Number(updates[key]);
    if ('catalog429' in updates) catalog429Remaining = Math.max(0, state.catalog429);
    return json(res, 200, snapshot());
  }
  if (state.killSwitch) return json(res, 503, { error: 'Mock provider kill switch is enabled' });
  if (url.pathname === '/player_api.php') {
    if (!authorized(url)) return json(res, 401, { user_info: { auth: 0, status: 'Disabled' } });
    if (url.searchParams.has('action') && catalog429Remaining > 0) {
      catalog429Remaining -= 1;
      return json(res, 429, { error: 'Configured catalog throttling' });
    }
    return json(res, 200, apiResponse(url));
  }
  const match = /^\/(live|movie|series)\/([^/]+)\/([^/]+)\/([^/]+)\.([a-zA-Z0-9]+)$/.exec(decodeURIComponent(url.pathname));
  if (match) {
    const [, kind, user, pass, id, ext] = match;
    if (user !== username || pass !== password) return json(res, 401, { error: 'Invalid credentials' });
    if (req.method !== 'GET' && req.method !== 'HEAD') return json(res, 405, { error: 'GET or HEAD required' });
    if (kind === 'live' && (!liveStreams.some((item) => String(item.stream_id) === id) || ext !== 'ts')) return json(res, 404, { error: 'Unknown live stream' });
    if (kind === 'movie' && (!vodStreams.some((item) => String(item.stream_id) === id) || ext !== 'mp4')) return json(res, 404, { error: 'Unknown movie' });
    if (kind === 'series' && (!['episode-A1', 'episode-B1'].includes(id) || ext !== 'mp4')) return json(res, 404, { error: 'Unknown episode' });
    return streamFile(req, res, url, kind, id, ext);
  }
  json(res, 404, { error: 'Not found' });
});

server.listen(port, host, () => console.log(`Mock Xtream provider listening at http://${host}:${server.address().port} (user=${username})`));
server.on('error', (error) => { console.error(error.message); process.exitCode = 1; });
