# Open setup code format, version 1

Anyone may generate this format offline. It contains a source label and login credentials. The `sourcecode` scheme below is a **placeholder**; the product name is undecided. Implementations should keep the scheme in one constant (`SetupCodeFormat.SCHEME` in the Kotlin codec) so it can be changed consistently.

## Fields

| Field | Required | Meaning |
| --- | --- | --- |
| `v` | Yes in JSON; optional in a versioned URI | Format version, `1` or `1.minor`. The current encoder writes `1.0`. |
| `name` | Yes | Plain text source label. No markup or image. |
| `server` | Yes | Host with an optional port and `http://` or `https://` scheme. A missing scheme means HTTP. |
| `user` | Yes | Username, kept exactly as entered. |
| `pass` | Yes | Password, kept exactly as entered. |
| `ent` | No | Opaque future signed entitlement. Version 1 stores it as untrusted data and grants nothing from it. |

The URI path `/v1` identifies major version 1. If a URI also has `v`, its major version must match the path. Readers reject other major versions and ignore unknown fields on a recognized major version, including later minor versions. Writers should emit `v=1.0` and only fields they understand.

## Encodings

**URI:** `<scheme>://setup/v1?v=1.0&name=...&server=...&user=...&pass=...` with an optional `&ent=...`. Percent-encode the UTF-8 bytes of every value. Spaces may be represented by `%20` or `+`. Percent decoding happens exactly once; `%252F` becomes the literal text `%2F`. Field names are case-sensitive. Duplicate fields are invalid.

Placeholder example:

```text
sourcecode://setup/v1?v=1.0&name=Home&server=https%3A%2F%2Fexample.invalid%3A8443&user=sample&pass=placeholder
```

**Base64url JSON:** UTF-8 JSON object encoded with RFC 4648 URL-safe base64, without padding, then prefixed with `<scheme>-setup:`. The decoder also accepts the bare base64url token for interoperability. All fields except `v` are JSON strings; `v` may be a string or the number `1`. Unknown JSON values may be objects or arrays. Example JSON before encoding:

```json
{"v":"1.0","name":"Home","server":"http://127.0.0.1","user":"sample","pass":"placeholder"}
```

Either encoding can be pasted with surrounding whitespace or inside a message. Readers search left to right for the first **valid** code. Keep the complete encoded payload at or below 8,192 characters and any pasted message at or below 65,536 characters. Smaller payloads make more reliable QR codes.

## Validation

- `name`: nonempty after trimming; at most 40 Unicode code points after removing control characters and bidirectional formatting controls (including overrides and isolates). It remains plain text.
- `server`: nonempty host, at most 253 ASCII characters; DNS labels are at most 63 characters and contain letters, digits, or interior hyphens. IPv4 literals and bracketed IPv6 literals are accepted. Ports are absent, empty (treated as absent), or decimal 1–65535. Only HTTP and HTTPS are allowed. Userinfo, paths, queries, fragments, whitespace, and percent escapes inside the server value are rejected.
- `user` and `pass`: nonblank, at most 128 Unicode code points each. They are not trimmed or normalized.
- `ent`: at most 2,048 characters. It is untrusted until a separate signed-entitlement verifier is built.

The codec returns a fixed validation reason and never includes the password in an error or in `SetupCode.toString()`.

## Security and user flow

**The QR and either text encoding are secrets: they include the password.** Do not log, upload, put in diagnostics, or show them after import. Warn before displaying or sharing a QR. After parsing, show an editable confirmation screen with the label and host. Do not save credentials, contact the server, or play anything until the person confirms. Store accepted credentials directly in the encrypted credential store. An unsigned code never supplies a logo, image, provider identity, or trusted Plus access.
