# Tvivo desktop

This Windows x64 desktop foundation validates a user-entered Xtream account,
stores accepted credentials with Windows DPAPI, and hosts the local-media
LibVLC player. It does not ship LibVLC binaries or provider-specific data.

The proposed player viewport, controls, input, and heavyweight-surface rules
are recorded in [`docs/design/desktop-player.md`](../docs/design/desktop-player.md).
They are intentionally scoped to POC-D1; catalog persistence and browsing are
the next desktop slice.

Install a 64-bit LibVLC/VLC distribution locally, then point the run task at its
directory (the directory containing `libvlc.dll` and the `plugins` folder):

```powershell
./gradlew.bat :desktop:run -Dtvivo.libvlc.dir='C:\\path\\to\\VideoLAN\\VLC'
```

Enter your own provider address and account details at runtime. Accepted
credentials are encrypted for the current Windows user with DPAPI; no plaintext
configuration is written. Choose a local `.mp4`, `.mkv`, or `.ts` fixture in
the player. Fixtures belong outside Git; `desktop-fixtures/` is ignored for
convenience. The app will show a diagnostic instead of crashing when LibVLC is
absent, incomplete, or not Windows x64. No native binaries may be bundled until
LGPL obligations are reviewed and recorded in `docs/decisions.md`.
