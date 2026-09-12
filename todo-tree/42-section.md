## N-2 — Define what the user sees when max_connections is exhausted — **IMPLEMENTED, physical-TV verification pending**
**Cost: small. Gates the physical-TV session.**

`max_connections` is `1` on this account, so a second concurrent stream — another
device in the house, or the app's own previous player not yet torn down — is not
an edge case, it is the **first playback failure a real user will hit**. Nothing
decides what appears today; the panel's refusal surfaces as whatever opaque
Media3 error the `.ts`/`.mkv` open produces.

Needs: the error copy (per the `ErrorCopy` pattern, one line), and a decision on
whether the app *reads* `active_connections` from `server_info` before opening a
stream to pre-empt it. **The copy must report, never assert a limit** — the
constraint in `CLAUDE.md` applies here more than anywhere, since this is the one
screen tempted to say "you can only watch one thing at a time".

Best specified now and verified in the physical-TV session, where it can actually
be provoked.

