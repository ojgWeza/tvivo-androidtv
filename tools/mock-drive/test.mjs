import test from 'node:test';
import assert from 'node:assert/strict';
import { createMockDriveServer } from './server.mjs';

async function withServer(fn) {
  const server = createMockDriveServer({ tokenTtlMs: 30000, pageSize: 2 });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try { await fn(base); } finally { await new Promise(resolve => server.close(resolve)); }
}
async function token(base, account = 'account-a') {
  const code = await fetch(`${base}/device/code`, { method: 'POST', body: new URLSearchParams({ account }) }).then(r => r.json());
  await fetch(`${base}/__ctl/approve`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ device_code: code.device_code }) });
  return fetch(`${base}/token`, { method: 'POST', body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:device_code', device_code: code.device_code }) }).then(r => r.json());
}
const ctl = (base, action, data = {}) => fetch(`${base}/__ctl/${action}`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(data) });
const auth = access => ({ authorization: `Bearer ${access}`, 'content-type': 'application/json' });
async function create(base, access, name, mimeType, parents = [], bytes = '') {
  const metadata = JSON.stringify({ name, mimeType, parents });
  if (bytes) {
    const boundary = 'test-boundary'; const body = `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\nContent-Disposition: form-data; name="metadata"\r\n\r\n${metadata}\r\n--${boundary}\r\nContent-Type: ${mimeType}\r\nContent-Disposition: form-data; name="file"; filename="${name}"\r\n\r\n${bytes}\r\n--${boundary}--\r\n`;
    return fetch(`${base}/drive/v3/files?uploadType=multipart&fields=id,name,mimeType,size,modifiedTime,md5Checksum,version`, { method: 'POST', headers: { authorization: `Bearer ${access}`, 'content-type': `multipart/related; boundary=${boundary}` }, body }).then(r => r.json());
  }
  return fetch(`${base}/drive/v3/files?fields=id,name,mimeType,size,modifiedTime,md5Checksum,version`, { method: 'POST', headers: auth(access), body: metadata }).then(r => r.json());
}
async function list(base, access, q, extra = '') {
  return fetch(`${base}/drive/v3/files?q=${encodeURIComponent(q)}&fields=nextPageToken,files(id,name,mimeType,size,modifiedTime,md5Checksum,version)${extra}`, { headers: auth(access) }).then(r => r.json());
}

test('folder/index bootstrap and existing folder read', async () => withServer(async base => {
  const { access_token: access } = await token(base);
  assert.deepEqual((await list(base, access, "name='Tvivo Library' and mimeType='application/vnd.google-apps.folder' and trashed=false")).files, []);
  const root = await create(base, access, 'Tvivo Library', 'application/vnd.google-apps.folder');
  for (const name of ['Movies', 'Series', 'Music']) await create(base, access, name, 'application/vnd.google-apps.folder', [root.id]);
  const movies = await list(base, access, `'${root.id}' in parents and name='Movies' and mimeType='application/vnd.google-apps.folder' and trashed=false`);
  assert.equal(movies.files.length, 1);
  const folder = movies.files[0];
  const index = await create(base, access, 'index.json', 'application/json', [folder.id], '{"format":"tvivo.link-library","version":1,"kind":"movies","items":[]}');
  assert.equal(index.version, '1'); assert.ok(index.md5Checksum);
  assert.equal((await list(base, access, `'${folder.id}' in parents and name='index.json' and trashed=false`)).files[0].id, index.id);
  const bytes = await fetch(`${base}/drive/v3/files/${index.id}?alt=media`, { headers: auth(access) }).then(r => r.text());
  assert.match(bytes, /"kind":"movies"/);
}));

test('pending bootstrap can resume by relisting without overwriting existing indexes', async () => withServer(async base => {
  const { access_token: access } = await token(base); const root = await create(base, access, 'Tvivo Library', 'application/vnd.google-apps.folder');
  const child = await create(base, access, 'Movies', 'application/vnd.google-apps.folder', [root.id]);
  const original = await create(base, access, 'index.json', 'application/json', [child.id], 'pending bytes');
  const found = await list(base, access, `'${child.id}' in parents and name='index.json' and mimeType='application/json' and trashed=false`);
  assert.equal(found.files[0].id, original.id);
  assert.equal(await fetch(`${base}/drive/v3/files/${original.id}?alt=media`, { headers: auth(access) }).then(r => r.text()), 'pending bytes');
}));

test('two account visibility is scoped to files created by that account token', async () => withServer(async base => {
  const a = await token(base, 'account-a'); const b = await token(base, 'account-b');
  const file = await create(base, a.access_token, 'private.json', 'application/json', [], '{}');
  assert.equal((await list(base, b.access_token, "name='private.json'")).files.length, 0);
  assert.equal((await fetch(`${base}/drive/v3/files/${file.id}`, { headers: auth(b.access_token) })).status, 404);
  assert.equal((await fetch(`${base}/drive/v3/about`, { headers: auth(b.access_token) }).then(r => r.json())).user.permissionId, 'account-b');
}));

test('pagination and requested fields', async () => withServer(async base => {
  const { access_token: access } = await token(base);
  for (let i = 0; i < 5; i++) await create(base, access, `f${i}`, 'text/plain');
  const first = await list(base, access, "trashed=false", '&pageSize=2'); assert.equal(first.files.length, 2); assert.equal(first.files[0].id, 'file-1'); assert.ok(first.nextPageToken);
  const second = await list(base, access, "trashed=false", `&pageSize=2&pageToken=${first.nextPageToken}`); assert.equal(second.files.length, 2);
  const partial = await fetch(`${base}/drive/v3/files?fields=files(id,name)&q=${encodeURIComponent("name='f0'")}`, { headers: auth(access) }).then(r => r.json());
  assert.deepEqual(Object.keys(partial.files[0]).sort(), ['id', 'name']);
}));

test('401, 403 quota and permission, and 429 deterministic faults', async () => withServer(async base => {
  const { access_token: access } = await token(base); const headers = auth(access);
  await ctl(base, 'force401'); assert.equal((await fetch(`${base}/drive/v3/about`, { headers })).status, 401);
  await ctl(base, '403-quota'); const quota = await fetch(`${base}/drive/v3/about`, { headers }).then(r => r.json()); assert.equal(quota.error.errors[0].reason, 'storageQuotaExceeded');
  await ctl(base, 'clear'); await ctl(base, '403-other'); const denied = await fetch(`${base}/drive/v3/about`, { headers }).then(r => r.json()); assert.equal(denied.error.errors[0].reason, 'insufficientFilePermissions');
  await ctl(base, 'clear'); await ctl(base, '429'); const limited = await fetch(`${base}/drive/v3/about`, { headers }); assert.equal(limited.status, 429); assert.equal(limited.headers.get('retry-after'), '2'); assert.equal((await limited.json()).error.errors[0].reason, 'rateLimitExceeded');
}));

test('delay, create-after-timeout accepted create, duplicate roots, and corrupt index bytes', async () => withServer(async base => {
  const { access_token: access } = await token(base); const headers = auth(access);
  await ctl(base, 'delay', { ms: 20 }); const before = Date.now(); await fetch(`${base}/drive/v3/about`, { headers }); assert.ok(Date.now() - before >= 15);
  await ctl(base, 'clear'); await ctl(base, 'create-after-timeout');
  await assert.rejects(fetch(`${base}/drive/v3/files`, { method: 'POST', headers, body: JSON.stringify({ name: 'accepted', mimeType: 'text/plain' }) }));
  assert.equal((await list(base, access, "name='accepted'")).files.length, 1);
  await ctl(base, 'clear'); await ctl(base, 'duplicate-folder'); await create(base, access, 'Tvivo Library', 'application/vnd.google-apps.folder');
  assert.equal((await list(base, access, "name='Tvivo Library' and mimeType='application/vnd.google-apps.folder'")).files.length, 2);
  await ctl(base, 'clear'); const index = await create(base, access, 'index.json', 'application/json', [], '{}'); await ctl(base, 'corrupt-index');
  assert.match(await fetch(`${base}/drive/v3/files/${index.id}?alt=media`, { headers }).then(r => r.text()), /corrupt mock index/);
}));

test('device flow pending, approval, denial, expiry, slow_down and refresh rotation', async () => withServer(async base => {
  const post = (path, body) => fetch(`${base}${path}`, { method: 'POST', body: new URLSearchParams(body) });
  let response = await post('/device/code', { account: 'account-a' }); let pending = await response.json();
  assert.equal((await post('/token', { grant_type: 'urn:ietf:params:oauth:grant-type:device_code', device_code: pending.device_code })).status, 400);
  assert.equal((await fetch(`${base}/__ctl/approve`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ device_code: pending.device_code, denied: true }) })).status, 200);
  assert.equal((await post('/token', { grant_type: 'urn:ietf:params:oauth:grant-type:device_code', device_code: pending.device_code }).then(r => r.json())).error, 'access_denied');
  response = await post('/device/code', { account: 'account-a' }); pending = await response.json();
  await ctl(base, 'approve', { device_code: pending.device_code });
  const first = await post('/token', { grant_type: 'urn:ietf:params:oauth:grant-type:device_code', device_code: pending.device_code }).then(r => r.json());
  const rotated = await post('/token', { grant_type: 'refresh_token', refresh_token: first.refresh_token }).then(r => r.json());
  assert.notEqual(rotated.refresh_token, first.refresh_token); assert.equal((await post('/token', { grant_type: 'refresh_token', refresh_token: first.refresh_token }).then(r => r.json())).error, 'invalid_grant');
  assert.equal((await post('/token', { grant_type: 'revoke', token: rotated.refresh_token })).status, 200);
  assert.equal((await post('/token', { grant_type: 'refresh_token', refresh_token: rotated.refresh_token }).then(r => r.json())).error, 'invalid_grant');
  const fresh = await fetch(`${base}/device/code`, { method: 'POST', body: new URLSearchParams({ account: 'account-b' }) }).then(r => r.json());
  await ctl(base, 'slow-down', { device_code: fresh.device_code });
  assert.equal((await post('/token', { grant_type: 'urn:ietf:params:oauth:grant-type:device_code', device_code: fresh.device_code }).then(r => r.json())).error, 'slow_down');
  await ctl(base, 'expire-device', { device_code: fresh.device_code });
  assert.equal((await post('/token', { grant_type: 'urn:ietf:params:oauth:grant-type:device_code', device_code: fresh.device_code }).then(r => r.json())).error, 'expired_token');
  await ctl(base, 'expire-access'); assert.equal((await fetch(`${base}/drive/v3/about`, { headers: auth(rotated.access_token) })).status, 401);
}));

test('metadata and multipart media updates bump version and modifiedTime', async () => withServer(async base => {
  const { access_token: access } = await token(base); const file = await create(base, access, 'replace.json', 'application/json', [], 'old');
  const boundary = 'update-boundary'; const metadata = JSON.stringify({ name: 'replace.json', mimeType: 'application/json' });
  const body = `--${boundary}\r\nContent-Type: application/json\r\nContent-Disposition: form-data; name="metadata"\r\n\r\n${metadata}\r\n--${boundary}\r\nContent-Type: application/json\r\nContent-Disposition: form-data; name="file"; filename="replace.json"\r\n\r\nnew bytes\r\n--${boundary}--\r\n`;
  const changed = await fetch(`${base}/drive/v3/files/${file.id}?uploadType=multipart`, { method: 'PATCH', headers: { authorization: `Bearer ${access}`, 'content-type': `multipart/related; boundary=${boundary}` }, body }).then(r => r.json());
  assert.equal(changed.version, '2'); assert.equal(await fetch(`${base}/drive/v3/files/${file.id}?alt=media`, { headers: auth(access) }).then(r => r.text()), 'new bytes');
}));

test('seed helper links to deterministic local media', async () => withServer(async base => {
  const seed = await fetch(`${base}/seed`).then(r => r.json());
  assert.equal(seed.movies.format, 'tvivo.link-library'); assert.match(seed.music.items[0].streamUrl, /\/media\/sample-audio.mp3$/);
  assert.equal((await fetch(seed.movies.items[0].streamUrl)).status, 200);
}));
