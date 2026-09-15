## UI work

| id | Change | Files | Note |
|---|---|---|---|
| **D-1** | Login: centred 820 px column, server full-width, username+password paired, one action row. Everything focusable above the IME ceiling. | `auth/LoginScreen.kt` | Supersedes the partial Q-11 fix **Built.** Content is ~290 dp, top-anchored, no scroll and no `imePadding()` — above the ceiling by layout |
| **D-2** | Login error copy to a hard **one line** | `ui/common/ErrorCopy.kt`, `LoginScreen.kt` | Two lines push buttons into the IME **Built.** `ErrorCopy.forLogin` + `ErrorCopyTest` holds the 48-char budget |
| **D-3** | Rail 360 dp → **280 dp**; labels wrap to 2 lines, never ellipsised; tooltip only past 2 lines | `ui/browse/CategoryRail.kt` | Truncation recreates Q-12 **Built.** 280 dp, `title` role, 2 lines, conditional tooltip on overflow |
| **D-4** | **Type scale as roles** (`display`/`headline`/`title`/`body`/`label`/`caption`), 12 dp floor. Retires every hand-picked `sp`, including the 10 dp quality badge | new `ui/theme/Type.kt` + ~6 UI files | Wide blast radius **Built.** `ui/theme/Type.kt`; all 40 hand-picked `sp` call sites now name a role |
| **D-5** | Map `Palette` onto **Material colour roles** so contrast variants resolve | `ui/theme/Palette.kt`, theme setup | Wide blast radius **Built.** `ui/theme/Theme.kt`; `TvivoTheme` installed in `MainActivity` |
| **D-6** | **Card titles overlaid** on the poster over a bottom scrim, 2 lines then ellipsise. Live TV keeps titles below (card too short) | `ui/browse/ContentGrid.kt` | **Closes Q-8.** Already in `ui-scope.md`, never implemented **Built. Closes Q-8.** Poster titles overlaid on a gradient scrim; live keeps titles below |
| **D-7** | **Icon row on Home**: Refresh / Account / Exit as 88 dp pills, label revealed on focus | `ui/home/HomeScreen.kt`, new `ui/common/IconPill.kt` | **Built.** `ui/common/IconPill.kt`; Exit is last in the row *and* behind a confirm |
| **D-8** | Browse header uses the **same** icon pill component | `ui/browse/BrowseScreen.kt` | Refresh is a bare text link today **Built.** Same `IconPill`; label says `Refresh this category` to name the scope |
| **D-9** | **Home tiles get photographs** (520×300, `docs/design/img/`) under a scrim | `ui/home/HomeScreen.kt`, `res/drawable*` | Screen for brand marks |
| **D-10** | **Splash screen** — mark, wordmark, real progress bar | `res/`, `MainActivity.kt` |  |
| **D-11** | **App mark + 320×180 TV banner** from `docs/design/img/*.svg` | `res/drawable/`, manifest `android:banner` |  |
| **D-12** | `Sign out` below a divider, consequence in the hint, confirm dialog with **default focus on the safe option** | `ui/settings/SettingsScreen.kt` | **Built.** Divider, consequence in the hint, `ui/common/ConfirmDialog.kt` with safe-option default focus |

