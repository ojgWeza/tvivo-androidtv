#!/usr/bin/env node
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import net from 'node:net';
import { fileURLToPath } from 'node:url';

const dir = path.dirname(fileURLToPath(import.meta.url));
const mediaDir = fs.mkdtempSync(path.join(os.tmpdir(), 'mock-provider-selftest-'));
const bytes = Buffer.alloc(512 * 1024);
for (let i = 0; i < bytes.length; i++) bytes[i] = i % 251;
for (const name of ['movie.mp4', 'episode-A1.mp4', 'episode-B1.mp4', 'live.ts']) fs.writeFileSync(path.join(mediaDir, name), bytes);
const portProbe = net.createServer();
await new Promise((resolve, reject) => portProbe.listen(0, '127.0.0.1', resolve).once('error', reject));
const port = portProbe.address().port;
await new Promise((resolve) => portProbe.close(resolve));
const child = spawn(process.execPath, [path.join(dir, 'server.mjs')], { env: { ...process.env, PORT: String(port), MOCK_HOST: '127.0.0.1', MEDIA_DIR: mediaDir }, stdio: 'ignore', windowsHide: true });
const base = `http://127.0.0.1:${port}`;
const api = (action = '') => `${base}/player_api.php?username=demo&password=demo${action ? `&action=${action}` : ''}`;
const ctl = async (body) => fetch(`${base}/__ctl`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) });
let firstStream;

async function waitReady() {
  for (let i = 0; i < 80; i++) {
    if (child.exitCode != null) throw new Error(`server exited early (${child.exitCode})`);
    try { const response = await fetch(api()); if (response.ok) return; } catch {}
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error('server did not start within 4 seconds');
}

try {
  await waitReady();
  const auth = await (await fetch(api())).json();
  assert.equal(auth.user_info.auth, 1);
  assert.equal(auth.user_info.username, 'demo');
  assert.equal(auth.user_info.max_connections, '1');
  const catalogChecks = [
    ['get_live_categories', 'Generated Live'], ['get_live_streams', 'Generated Live Loop'],
    ['get_vod_categories', 'Generated Movies'], ['get_vod_streams', 'Synthetic Film'],
    ['get_series_categories', 'Generated Series'], ['get_series', 'Synthetic Series'],
  ];
  for (const [action, expected] of catalogChecks) {
    const response = await fetch(api(action)); assert.equal(response.status, 200, action);
    assert.match(JSON.stringify(await response.json()), new RegExp(expected));
  }
  assert.equal((await (await fetch(`${api('get_vod_info')}&vod_id=2001`)).json()).movie_data.container_extension, 'mp4');
  assert.equal((await (await fetch(`${api('get_series_info')}&series_id=3001`)).json()).episodes['1'].length, 2);
  const ranged = await fetch(`${base}/movie/demo/demo/2001.mp4`, { headers: { range: 'bytes=10-19' } });
  assert.equal(ranged.status, 206);
  assert.equal(ranged.headers.get('content-range'), `bytes 10-19/${bytes.length}`);
  assert.deepEqual(Buffer.from(await ranged.arrayBuffer()), bytes.subarray(10, 20));
  const earlyEof = await new Promise((resolve, reject) => {
    http.get(`${base}/movie/demo/demo/2001.mp4?eofPercent=40`,
      { headers: { range: 'bytes=100000-400000' } }, (response) => {
        const chunks = [];
        response.on('data', (chunk) => chunks.push(chunk));
        response.on('error', () => {}); // The advertised length intentionally exceeds the early EOF.
        response.once('close', () => resolve({ status: response.statusCode,
          range: response.headers['content-range'], body: Buffer.concat(chunks) }));
      }).on('error', reject);
  });
  assert.equal(earlyEof.status, 206);
  assert.equal(earlyEof.range, `bytes 100000-400000/${bytes.length}`);
  const earlyEofBody = earlyEof.body;
  assert.equal(earlyEofBody.length, Math.floor(bytes.length * 0.4) - 100000);
  assert.deepEqual(earlyEofBody, bytes.subarray(100000, Math.floor(bytes.length * 0.4)));

  firstStream = await fetch(`${base}/movie/demo/demo/2001.mp4?stallAfterBytes=1&stallSeconds=10`, { headers: { range: 'bytes=0-400000' } });
  assert.equal(firstStream.status, 206);
  const capped = await fetch(`${base}/live/demo/demo/1001.ts`);
  assert.equal(capped.status, 403, 'second active stream must be rejected by default');
  await firstStream.body.cancel(); firstStream = undefined;
  let released = false;
  for (let i = 0; i < 40; i++) {
    if ((await (await fetch(`${base}/__ctl`)).json()).activeConnections === 0) { released = true; break; }
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  assert.equal(released, true, 'aborting a stalled response must release its connection');
  const reopened = await fetch(`${base}/movie/demo/demo/2001.mp4`, { headers: { range: 'bytes=100-199' } });
  assert.equal(reopened.status, 206, 'a range request may reopen after the old socket closes');
  assert.deepEqual(Buffer.from(await reopened.arrayBuffer()), bytes.subarray(100, 200));

  await ctl({ catalog429: 1 });
  assert.equal((await fetch(api('get_live_streams'))).status, 429);
  assert.equal((await fetch(api('get_live_streams'))).status, 200);
  await ctl({ killSwitch: true });
  assert.equal((await fetch(api())).status, 503);
  await ctl({ killSwitch: false });
  assert.equal((await fetch(api())).status, 200);
  console.log('PASS auth + 8 Xtream API actions; Range bytes and absolute EOF cutoff; max_connections=1 rejection and close/reopen; 429 countdown; kill-switch');
} catch (error) {
  console.error(`FAIL ${error.stack || error}`);
  process.exitCode = 1;
} finally {
  if (firstStream?.body) await firstStream.body.cancel().catch(() => {});
  child.kill();
  await new Promise((resolve) => child.once('exit', resolve));
  fs.rmSync(mediaDir, { recursive: true, force: true });
}
