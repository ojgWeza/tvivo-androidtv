## N-1 — An unhandled-exception hook that writes to `DiagnosticLog`
**Cost: small. Unblocks Q-14. Implemented 2026-09-11; 151 unit tests pass; device verification pending.**

Q-14 (the phone crash) has been open with no root cause for two days for exactly
one reason: no logcat has ever been captured from that device, and the crash
happens before anything can be attached. An `UncaughtExceptionHandler` installed
in `Application.onCreate` that appends the stack trace to the existing on-device
`DiagnosticLog` turns "reproducible but untraceable" into "open Diagnostics and
read it" — on any device, with no adb, including the physical TV later.

Must write **synchronously** on the crashing thread and survive the process
dying; a coroutine launch will not finish. Ties into T-D4 — this is the strongest
argument that Diagnostics needs to record more than failures it happens to catch.

Implemented as `TvivoApplication` + `CrashDiagnostics`: the handler records only
exception types and bounded stack frames (never messages), syncs the file before
delegating to Android's prior handler, then consumes and deletes it on next launch.

