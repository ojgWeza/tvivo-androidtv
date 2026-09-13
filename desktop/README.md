# Tvivo desktop playback POC (POC-D1)

This Windows x64 technical spike plays local synthetic media only. It does not
connect to a provider or ship LibVLC binaries.

Install a 64-bit LibVLC/VLC distribution locally, then point the run task at its
directory (the directory containing `libvlc.dll` and the `plugins` folder):

```powershell
./gradlew.bat :desktop:run -Dtvivo.libvlc.dir='C:\\path\\to\\VideoLAN\\VLC'
```

Choose a local `.mp4`, `.mkv`, or `.ts` fixture in the window. Fixtures belong
outside Git; `desktop-fixtures/` is ignored for convenience. The app will show
a diagnostic instead of crashing when LibVLC is absent, incomplete, or not
Windows x64. No native binaries may be bundled until LGPL obligations are
reviewed and recorded in `docs/decisions.md`.
