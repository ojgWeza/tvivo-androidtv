# Tvivo desktop

This Windows x64 desktop app validates a user-entered Xtream account, stores
accepted credentials with Windows DPAPI, and opens a library Home screen after
sign-in. It caches account-scoped categories and catalogs in SQLite, including
favourites and resume state, then plays selected provider content through
LibVLC. It does not ship LibVLC binaries or provider-specific data.

The proposed player viewport, controls, input, and heavyweight-surface rules
are recorded in [`docs/design/desktop-player.md`](../docs/design/desktop-player.md).
They define the native-player surface and control boundary.

The Windows x64 LibVLC runtime is bundled with the desktop distribution. On first
playback it is extracted into Tvivo's local application runtime automatically;
users do not need a separate VLC installation. The checked-in archive is
VideoLAN VLC 3.0.23, accompanied by its published SHA-256 file and the upstream
license/source material in the archive.

For development only, a local runtime may be overridden with a directory that
contains `libvlc.dll` and the `plugins` folder:

```powershell
./gradlew.bat :desktop:run -Dtvivo.libvlc.dir='D:\\path\\to\\libvlc'
```

Enter your own provider address and account details at runtime. Accepted
credentials are encrypted for the current Windows user with DPAPI; no plaintext
configuration is written. Refresh a library from Home, Movies, Series, or Live
TV, then select content to open playback. The app shows a diagnostic instead of
crashing when LibVLC is absent, incomplete, or not Windows x64. No native
binaries may be bundled until LGPL obligations are reviewed and recorded in
`docs/decisions.md`.
