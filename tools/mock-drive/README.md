# Mock Google Drive

Zero-dependency Node mock for the Drive sign-in and link-library contract. It stores state in memory and uses synthetic tokens, accounts, and media bytes.

## Run

```sh
node tools/mock-drive/server.mjs
```

The server listens on `0.0.0.0:8090` by default. Set `PORT`, `MOCK_HOST`, or `MOCK_DRIVE_TOKEN_TTL_MS` to override the listen port, bind host, or access-token lifetime. Run the self-contained HTTP contract tests with `node --test tools/mock-drive/test.mjs`; tests bind an ephemeral loopback port and close the server during teardown.

## Endpoints

- `POST /device/code` creates a device code. Optional form field `account` selects `account-a` or `account-b`.
- `POST /token` handles device-code grant polling, refresh-token rotation, and `grant_type=revoke` with `token`.
- `GET /drive/v3/about` returns account `permissionId`, display name, and email.
- `GET /drive/v3/files` supports `q` clauses for `name`, `mimeType`, `'<id>' in parents`, and `trashed=false`; `pageSize`, numeric `pageToken`, and `fields` are supported.
- `POST /drive/v3/files` creates metadata-only files or accepts `multipart/related` metadata and media parts. `GET /drive/v3/files/{id}` reads metadata; `?alt=media` reads bytes; `PATCH` updates metadata or media and bumps version and modified time.
- `GET /seed` returns movies, series, and music `tvivo.link-library` examples. Their URLs point to `GET /media/sample-audio.mp3`, `/media/sample-video.mp4`, and `/media/sample-video.ts`, generated in code.
- `POST /__ctl/approve` with `{ "device_code": "…" }` approves a device flow; include `denied:true` to reject it.
- `POST /__ctl/slow-down` and `/__ctl/expire-device` set device-flow polling outcomes for the given code.
- `POST /__ctl/expire-access` immediately expires issued access tokens.
- `GET /__ctl` shows active faults and file count. `POST /__ctl/reset` resets all state.

## Fault controls

Post JSON to `/__ctl/{name}` (or send `{ "action": "name" }` to `/__ctl`):

| Control | Effect |
| --- | --- |
| `force401` `{ "count": 1 }` | Return 401 for the next Drive request(s). |
| `403-quota` / `403-other` | Return 403 with `storageQuotaExceeded` / `insufficientFilePermissions`. |
| `429` `{ "count": 1 }` | Return 429 with `rateLimitExceeded`. |
| `delay` `{ "ms": 250 }` | Delay Drive responses. |
| `offline` `{ "enabled": true }` / `drop` | Drop Drive connections until cleared. |
| `create-after-timeout` `{ "count": 1 }` | Store create, then drop its response to model an ambiguous timeout. |
| `duplicate-folder` `{ "count": 1 }` | Create two same-name roots on the next `Tvivo Library` create. |
| `corrupt-index` `{ "count": 1 }` | Return invalid bytes for the next JSON file media read. |
| `clear` | Clear configured faults. |
| `reset` | Clear all files, tokens, device codes, and faults. |

The mock intentionally exposes an unauthenticated control interface for local QA. Do not expose it to an untrusted network.
