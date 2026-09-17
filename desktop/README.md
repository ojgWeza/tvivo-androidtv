# Tvivo desktop

This Windows x64 desktop app validates a user-entered Xtream account, stores
accepted credentials with Windows DPAPI, and opens a library Home screen after
sign-in. It caches account-scoped categories and catalogs in SQLite, including
favourites and resume state, then plays selected provider content through
bundled libmpv runtime. It does not ship provider-specific data.

The proposed player viewport, controls, input, and heavyweight-surface rules
are recorded in [`docs/design/desktop-player.md`](../docs/design/desktop-player.md).
They define the native-player surface and control boundary.

The Windows x64 libmpv runtime is bundled with the desktop distribution. On first
playback it is extracted into Tvivo's local application runtime automatically;
users do not need a separate mpv installation. Playback is loaded through the JNA
binding in `MpvPlayer.kt`.

For development only, a local runtime may be overridden with a directory that
contains `libmpv-2.dll`:

```powershell
./gradlew.bat :desktop:run -Dtvivo.libmpv.dir='D:\\path\\to\\libmpv'
```

Enter your own provider address and account details at runtime. Accepted
credentials are encrypted for the current Windows user with DPAPI; no plaintext
configuration is written. Refresh a library from Home, Movies, Series, or Live
TV, then select content to open playback. The app shows a diagnostic instead of
crashing when libmpv is absent, incomplete, or not Windows x64. Native runtime
licensing and redistribution details must be reviewed and recorded in
`docs/decisions.md` before release packaging changes.
