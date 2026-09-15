## Q-20 — The splash mark shows its launcher background as a box — **FIXED, verified**
**Severity: Low. Found 2026-09-08 on-emulator.** `ui/common/SplashScreen.kt`

`ic_launcher_mark` carries a gradient background rect, because a launcher icon has to
supply its own surface. Drawn on the splash, which already has one, that rect reads as a
lighter square floating behind the mark.

**Fix:** `ic_mark.xml`, same geometry without the background, for in-app use. The launcher
icon keeps its tile. **Verified on-emulator 2026-09-08.**

---

