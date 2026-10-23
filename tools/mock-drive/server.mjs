#!/usr/bin/env node
import http from 'node:http';
import { randomUUID, createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const FOLDER = 'application/vnd.google-apps.folder';
const INDEX = 'application/json';
const ACCOUNT_IDS = ['account-a', 'account-b'];
const media = {
  'sample-audio.mp3': Buffer.from('ID3\u0004\u0000\u0000\u0000\u0000\u0000\u0000Tvivo mock audio', 'binary'),
  'sample-video.mp4': Buffer.from([0, 0, 0, 24, 102, 116, 121, 112, 105, 115, 111, 109, 84, 118, 105, 118, 111, 77, 111, 99, 107]),
  'sample-video.ts': Buffer.from(Array.from({ length: 188 }, (_, i) => i === 0 ? 0x47 : (i * 13) & 255)),
};
const now = () => new Date().toISOString();
const md5 = (bytes) => createHash('md5').update(bytes).digest('hex');
const newState = () => ({ files: new Map(), tokens: new Map(), refresh: new Map(), codes: new Map(), faults: { force401: 0, forbidden: null, rateLimit: 0, delayMs: 0, offline: false, createAfterTimeout: 0, duplicateFolder: 0, corruptIndexBytes: 0 }, counters: { file: 0, code: 0 } });
function json(res, status, value, headers = {}) { res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store', ...headers }); res.end(JSON.stringify(value)); }
function driveError(res, code, reason, message = reason) { json(res, code, { error: { code, message, errors: [{ domain: 'global', reason, message }] } }); }
function readBody(req) { return new Promise((resolve, reject) => { const chunks = []; req.on('data', c => chunks.push(c)); req.on('end', () => resolve(Buffer.concat(chunks))); req.on('error', reject); }); }
function parseMultipart(body, contentType) {
  const boundary = /boundary=(?:"([^"]+)"|([^;]+))/.exec(contentType || '');
  if (!boundary) return { metadata: {}, bytes: body };
  const boundaryText = boundary[1] || boundary[2]; const chunks = body.toString('latin1').split(`--${boundaryText}`); let metadata = {}; let bytes = Buffer.alloc(0);
  for (const chunk of chunks) {
    const split = chunk.indexOf('\r\n\r\n'); if (split < 0) continue;
    const head = chunk.slice(0, split); let payload = chunk.slice(split + 4); if (payload.endsWith('\r\n')) payload = payload.slice(0, -2);
    if (/name="metadata"/.test(head)) { try { metadata = JSON.parse(Buffer.from(payload, 'latin1').toString('utf8')); } catch { return { metadata: {}, bytes: Buffer.alloc(0) }; } }
    else if (/Content-Disposition:/i.test(head)) bytes = Buffer.from(payload, 'latin1');
  }
  return { metadata, bytes };
}
function fieldsProjection(obj, fields) {
  if (!fields) return obj;
  const nested = /(?:^|,)files\(([^()]*)\)/.exec(fields);
  const selected = new Set((nested?.[1] || fields).split(',').map(s => s.trim().split('(')[0]).filter(Boolean));
  if (selected.has('*')) return obj;
  return Object.fromEntries(Object.entries(obj).filter(([key]) => selected.has(key)));
}
function fileMeta(f) { return { id: f.id, name: f.name, mimeType: f.mimeType, size: String(f.bytes.length), modifiedTime: f.modifiedTime, md5Checksum: md5(f.bytes), version: String(f.version), ...(f.parents?.length ? { parents: f.parents } : {}) }; }
function queryMatches(f, q) {
  if (!q) return true;
  const clauses = q.split(/\s+and\s+/i).map(s => s.trim());
  return clauses.every(c => {
    let m = /^name\s*=\s*'((?:\\.|[^'])*)'$/.exec(c); if (m) return f.name === m[1].replace(/\\'/g, "'");
    m = /^mimeType\s*=\s*'([^']*)'$/.exec(c); if (m) return f.mimeType === m[1];
    m = /^'([^']+)'\s+in\s+parents$/.exec(c); if (m) return m[1] === 'root' ? f.parents.length === 0 : f.parents.includes(m[1]);
    if (/^trashed\s*=\s*false$/.test(c)) return !f.trashed;
    return true;
  });
}
export function createMockDriveServer(options = {}) {
  const state = newState(); const ttlMs = Number(options.tokenTtlMs ?? process.env.MOCK_DRIVE_TOKEN_TTL_MS ?? 30000); const pageSizeDefault = Number(options.pageSize ?? 2);
  function issue(account, refreshToken = null) { const token = `mock-access-${randomUUID()}`; state.tokens.set(token, { account, expires: Date.now() + ttlMs }); if (!refreshToken) { refreshToken = `mock-refresh-${randomUUID()}`; state.refresh.set(refreshToken, account); } return { access_token: token, expires_in: Math.max(1, Math.floor(ttlMs / 1000)), token_type: 'Bearer', refresh_token: refreshToken }; }
  async function handler(req, res) {
    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    if (url.pathname === '/__ctl' || url.pathname.startsWith('/__ctl/')) {
      if (req.method === 'GET') return json(res, 200, { ...state.faults, files: state.files.size, accounts: ACCOUNT_IDS });
      if (req.method !== 'POST') return json(res, 405, { error: 'Use GET or POST' });
      const action = url.pathname.slice('/__ctl'.length).replace(/^\//, '');
      if (action === 'reset') { const fresh = newState(); Object.assign(state, fresh); return json(res, 200, { reset: true }); }
      let data = {}; try { data = JSON.parse((await readBody(req)).toString() || '{}'); } catch { return json(res, 400, { error: 'Expected JSON object' }); }
      const ctl = action || data.action;
      if (ctl === 'approve') { const code = String(data.device_code || data.user_code || data.code || ''); const flow = state.codes.get(code) || [...state.codes.values()].find(candidate => candidate.userCode === code); if (!flow) return json(res, 404, { error: 'Unknown device or user code' }); flow.status = data.denied ? 'access_denied' : 'approved'; return json(res, 200, { status: flow.status }); }
      if (ctl === 'slow-down') { const flow = state.codes.get(String(data.device_code || '')); if (!flow) return json(res, 404, { error: 'Unknown device code' }); flow.status = 'slow_down'; flow.interval += 5; return json(res, 200, { status: flow.status, interval: flow.interval }); }
      if (ctl === 'expire-device') { const flow = state.codes.get(String(data.device_code || '')); if (!flow) return json(res, 404, { error: 'Unknown device code' }); flow.expires = 0; return json(res, 200, { expired: true }); }
      if (ctl === 'expire-access') { for (const value of state.tokens.values()) value.expires = 0; return json(res, 200, { expired: true }); }
      if (ctl === 'force401') state.faults.force401 = Number(data.count ?? 1);
      else if (ctl === '403-quota' || ctl === 'quota') state.faults.forbidden = 'storageQuotaExceeded';
      else if (ctl === '403-other' || ctl === 'forbidden') state.faults.forbidden = 'insufficientFilePermissions';
      else if (ctl === '429' || ctl === 'rate-limit') state.faults.rateLimit = Number(data.count ?? 1);
      else if (ctl === 'delay') state.faults.delayMs = Math.max(0, Number(data.ms ?? data.delayMs ?? 0));
      else if (ctl === 'offline' || ctl === 'drop') state.faults.offline = data.enabled !== false;
      else if (ctl === 'create-after-timeout') state.faults.createAfterTimeout = Number(data.count ?? 1);
      else if (ctl === 'duplicate-folder') state.faults.duplicateFolder = Number(data.count ?? 1);
      else if (ctl === 'corrupt-index') state.faults.corruptIndexBytes = Number(data.count ?? 1);
      else if (ctl === 'clear') state.faults = newState().faults;
      else return json(res, 400, { error: 'Unknown control action' });
      return json(res, 200, { ...state.faults });
    }
    if (url.pathname.startsWith('/media/')) { const name = url.pathname.slice(7); const bytes = media[name]; if (!bytes) return json(res, 404, { error: 'Not found' }); res.writeHead(200, { 'content-type': name.endsWith('.mp3') ? 'audio/mpeg' : name.endsWith('.ts') ? 'video/mp2t' : 'video/mp4', 'content-length': bytes.length, 'cache-control': 'no-store' }); return res.end(bytes); }
    if (url.pathname === '/seed') { const base = `http://${req.headers.host || 'localhost'}`; return json(res, 200, { movies: { format: 'tvivo.link-library', version: 1, kind: 'movies', items: [{ id: '0cc0e2e0-4e10-4f11-86e0-000000000001', title: 'Mock Movie', group: 'Sample Movies', streamUrl: `${base}/media/sample-video.mp4` }] }, series: { format: 'tvivo.link-library', version: 1, kind: 'series', items: [{ id: '0cc0e2e0-4e10-4f11-86e0-000000000002', title: 'Mock Series', group: 'Sample Series', seasons: [{ number: 1, title: 'Season 1', episodes: [{ id: '0cc0e2e0-4e10-4f11-86e0-000000000003', number: 1, title: 'Episode 1', streamUrl: `${base}/media/sample-video.ts` }] }] }] }, music: { format: 'tvivo.link-library', version: 1, kind: 'music', items: [{ id: '0cc0e2e0-4e10-4f11-86e0-000000000004', title: 'Mock Song', group: 'Sample Music', artist: 'Tvivo Mock', streamUrl: `${base}/media/sample-audio.mp3` }] } }); }
    if (req.method === 'POST' && url.pathname === '/device/code') { const account = String(new URLSearchParams((await readBody(req)).toString()).get('account') || 'account-a'); if (!ACCOUNT_IDS.includes(account)) return json(res, 400, { error: 'invalid_client' }); const code = `device-${++state.counters.code}`; const userCode = `TV-${String(state.counters.code).padStart(4, '0')}`; state.codes.set(code, { account, userCode, status: 'authorization_pending', expires: Date.now() + 60000, interval: 1 }); return json(res, 200, { device_code: code, user_code: userCode, verification_url: `http://${req.headers.host}/__ctl/approve`, expires_in: 60, interval: 1 }); }
    if (req.method === 'POST' && (url.pathname === '/token' || url.pathname === '/revoke')) { const form = new URLSearchParams((await readBody(req)).toString()); const grant = form.get('grant_type') || ''; if (grant === 'refresh_token') { const old = form.get('refresh_token'); const account = state.refresh.get(old); if (!account) return json(res, 400, { error: 'invalid_grant' }); state.refresh.delete(old); return json(res, 200, issue(account)); } if (grant.includes('device_code')) { const code = state.codes.get(form.get('device_code')); if (!code || Date.now() >= code.expires) return json(res, 400, { error: 'expired_token' }); if (code.status === 'authorization_pending') return json(res, 400, { error: 'authorization_pending' }); if (code.status === 'slow_down') return json(res, 400, { error: 'slow_down' }); if (code.status === 'access_denied') return json(res, 400, { error: 'access_denied' }); return json(res, 200, issue(code.account)); } if (grant === 'revoke' || url.pathname === '/revoke') { const token = form.get('token'); state.refresh.delete(token); for (const [key, value] of state.tokens) if (value.account && key === token) state.tokens.delete(key); return res.writeHead(200).end(); } return json(res, 400, { error: 'unsupported_grant_type' }); }
    if (!url.pathname.startsWith('/drive/v3/') && !url.pathname.startsWith('/upload/drive/v3/')) return json(res, 404, { error: 'Not found' });
    if (state.faults.offline) return req.socket.destroy();
    if (state.faults.delayMs > 0) await new Promise(resolve => setTimeout(resolve, state.faults.delayMs));
    if (state.faults.force401 > 0) { state.faults.force401--; return driveError(res, 401, 'authError'); }
    const auth = /^Bearer\s+(.+)$/i.exec(req.headers.authorization || ''); const token = auth && state.tokens.get(auth[1]); if (!token || Date.now() >= token.expires) return driveError(res, 401, 'authError');
    if (state.faults.forbidden) return driveError(res, 403, state.faults.forbidden);
    if (state.faults.rateLimit > 0) { state.faults.rateLimit--; return json(res, 429, { error: { code: 429, message: 'Mock rate limit', errors: [{ domain: 'global', reason: 'rateLimitExceeded', message: 'Mock rate limit' }] } }, { 'retry-after': '2' }); }
    const account = token.account;
    if (url.pathname === '/drive/v3/about' && req.method === 'GET') return json(res, 200, { user: { permissionId: account, displayName: account === 'account-a' ? 'Mock Account A' : 'Mock Account B', emailAddress: `${account}@example.test` } });
    const filesPath = '/drive/v3/files';
    if (url.pathname === filesPath && req.method === 'GET') {
      const visible = [...state.files.values()].filter(f => f.account === account && queryMatches(f, url.searchParams.get('q')));
      const pageSize = Math.max(1, Math.min(1000, Number(url.searchParams.get('pageSize') || pageSizeDefault))); const offset = Math.max(0, Number(url.searchParams.get('pageToken') || 0)); const chosen = visible.slice(offset, offset + pageSize);
      const response = { files: chosen.map(f => fieldsProjection(fileMeta(f), url.searchParams.get('fields'))), ...(offset + pageSize < visible.length ? { nextPageToken: String(offset + pageSize) } : {}) };
      const mask = url.searchParams.get('fields');
      if (mask) { const top = new Set(mask.replace(/files\([^)]*\)/g, 'files').split(',').map(s => s.trim())); return json(res, 200, Object.fromEntries(Object.entries(response).filter(([key]) => top.has(key)))); }
      return json(res, 200, response);
    }
    if ((url.pathname === filesPath || url.pathname === '/upload/drive/v3/files') && req.method === 'POST') {
      const raw = await readBody(req); let metadata; let bytes = Buffer.alloc(0);
      if (/multipart\/related/i.test(req.headers['content-type'] || '')) ({ metadata, bytes } = parseMultipart(raw, req.headers['content-type'])); else { try { metadata = JSON.parse(raw.toString()); } catch { return driveError(res, 400, 'invalidArgument'); } }
      const id = `file-${++state.counters.file}`; const f = { id, name: String(metadata.name || ''), mimeType: metadata.mimeType || 'application/octet-stream', parents: metadata.parents || [], account, bytes, version: 1, modifiedTime: now(), trashed: false };
      if (f.name === 'Tvivo Library' && state.faults.duplicateFolder > 0) { state.faults.duplicateFolder--; const duplicate = { ...f, id: `file-${++state.counters.file}`, name: f.name }; state.files.set(duplicate.id, duplicate); }
      state.files.set(id, f); const response = fieldsProjection(fileMeta(f), url.searchParams.get('fields'));
      if (state.faults.createAfterTimeout > 0) { state.faults.createAfterTimeout--; return req.socket.destroy(); }
      return json(res, 200, response);
    }
    const match = /^\/drive\/v3\/files\/([^/]+)$/.exec(url.pathname);
    if (match) { const f = state.files.get(decodeURIComponent(match[1])); if (!f || f.account !== account) return driveError(res, 404, 'notFound');
      if (url.searchParams.get('alt') === 'media' && req.method === 'GET') { let bytes = f.bytes; if (state.faults.corruptIndexBytes > 0 && f.mimeType === INDEX) { state.faults.corruptIndexBytes--; bytes = Buffer.from('{corrupt mock index'); } res.writeHead(200, { 'content-type': f.mimeType, 'content-length': bytes.length, 'cache-control': 'no-store' }); return res.end(bytes); }
      if (req.method === 'GET') return json(res, 200, fieldsProjection(fileMeta(f), url.searchParams.get('fields')));
      if (req.method === 'PATCH') { const raw = await readBody(req); let metadata = {}; if (/multipart\/related/i.test(req.headers['content-type'] || '')) ({ metadata, bytes: f.bytes } = parseMultipart(raw, req.headers['content-type'])); else { try { metadata = JSON.parse(raw.toString()); } catch { return driveError(res, 400, 'invalidArgument'); } } Object.assign(f, { ...(metadata.name ? { name: metadata.name } : {}), ...(metadata.mimeType ? { mimeType: metadata.mimeType } : {}), version: f.version + 1, modifiedTime: now() }); return json(res, 200, fieldsProjection(fileMeta(f), url.searchParams.get('fields'))); }
    }
    return driveError(res, 404, 'notFound');
  }
  const server = http.createServer((req, res) => handler(req, res).catch(error => { if (!res.destroyed && !res.headersSent) json(res, 500, { error: { code: 500, errors: [{ reason: 'internalError' }], message: error.message } }); }));
  return server;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const server = createMockDriveServer(); const host = process.env.MOCK_HOST || '0.0.0.0'; const port = Number(process.env.PORT || 8090);
  server.listen(port, host, () => console.log(`Mock Drive server listening at http://${host}:${server.address().port}`));
  server.on('error', error => { console.error(error.message); process.exitCode = 1; });
}
