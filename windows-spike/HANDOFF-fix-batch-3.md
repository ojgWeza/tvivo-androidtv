# Fix batch 3 handoff

Code and tests were edited only. No build, test, app launch, install, process control, or emulator run was performed.

## Root causes and changes

1. **Right pane flicker** — `MainWindow.SetPlayerList` replaced the `ListView.ItemsSource` with a new array after selection. It now keeps an `ObservableCollection<PlayerListEntry>` and updates each row's current state when the IDs are unchanged. A real season change replaces the rows because the data set changes.
2. **Selected item appearance** — the pane only rendered a text status for the current item. The list row now uses the shared orange accent as its backdrop and dark text; the status label is hidden.
3. **Stale player content after Back** — navigation stopped both playback engines but retained the current channel, title, series context, season selector, and related rows. Leaving the player now clears those fields and the surface text while stopping playback. Both playback engines already clear their media source/view during stop.
4. **Artwork** — the SQLite catalog contains artwork URLs: Live 5,638/6,382; Movie 48,277/48,887; Series 13,136/14,156. Xtream mapping stores `stream_icon` for Live/Movie and `cover` for Series, and the card receives `LogoUri` as `ArtworkUrl`. The image request already accepts HTTP and HTTPS, sends `User-Agent`/`Accept`, and follows `HttpClient` redirects. The exact code defect was `ExpireArtworkAsync` calling `StopArtwork` after ten seconds; `StopArtwork` clears `Image.Source`, so a successfully decoded image was removed. The timeout now only releases its request slot and keeps the bitmap displayed. The first image failure now writes its type, HTTP status/HRESULT or `ImageFailed` detail to the local launch log, with URLs redacted. There is no explicit image cache in this WinUI spike.
5. **One-season selector** — the ComboBox was made visible for every series. It now appears only for multiple seasons; a single season is shown as a plain label.
6. **Orange accent consistency** — custom app styles covered only selected navigation/buttons, leaving built-in control states on WinUI defaults. `App.xaml` now overrides the system accent variants and accent/state brushes for buttons, toggles, combo/list/grid items, check/radio, navigation, app bar, split, and repeat buttons. The global rule is in `DESIGN-RULES.md`.
7. **My Tvivo tie ordering** — the previous `LeadingPunctuation` key removed punctuation, turning names such as `! Symbol` into letter/digit keys and allowing numeric titles to sort ahead. Schema 13 stores a Unicode-letter, digit, or symbol bucket prefix without stripping title characters. Most-visited count and last-tuned time remain ahead of that fallback key. The regular browse ordering remains based on the unmodified title.
8. **Full row listing** — shelf previews were capped at 12 and the count on the row header was a non-interactive `TextBlock`. The count is now a button opening the same full listing as the title. The listing uses the existing paged `GridView` query (100 rows per page); My Tvivo shelves retain their item type when opened.
9. **Filter leaves stale non-matches** — `GetChannelsGroupedByCategory` used a raw SQL string containing a two-character SQLite `ESCAPE` value. A non-empty search caused the grouped shelf query to fail; the UI catch retained the previous unfiltered shelf surface. The escape literal is now one character. A grouped-shelf filter regression test asserts that only matching rows remain.
10. **Refresh error and Retry** — refresh and page-load catches hid all exception details, so the UI could only say “Couldn't refresh.” The Retry button was already wired to `LoadAsync(..., forceRefresh: true)` for a connected account, and a completed failed refresh task is removed so a later call starts a new attempt. Refresh failures now identify catalog type and stage (categories, items, or save), and the UI surfaces the sanitized exception message. A test verifies stage reporting and a second refresh attempt. The local DB is schema 12 with populated categories/items in all three types, so the suspected schema-v12 migration is not preventing current catalog reads. The saved app log contains no refresh error, so it does not establish which provider request or database operation caused the user's particular refresh failure; the new message will expose that on the next approved run.

## Files by defect

- 1–3, 5: `src/Tvivo.App/MainWindow.xaml(.cs)`
- 4, 8–10: `src/Tvivo.App/Pages/CatalogLandingPage.xaml(.cs)`
- 6: `src/Tvivo.App/App.xaml`, `DESIGN-RULES.md`
- 7 and 9: `src/Tvivo.Infrastructure/SqliteCatalogRepository.cs`
- 10: `src/Tvivo.Infrastructure/CatalogRefreshService.cs`
- Regression coverage: `tests/Tvivo.Infrastructure.Tests/SqliteCatalogRepositoryTests.cs`

## Live verification list

- Confirm the right-pane row collection stays steady while switching episodes/items and that only the selected row has the orange backdrop.
- Leave playback with Back, open a different title, and confirm no previous frame/title/side list appears; check both Native and LibVLC engines.
- Confirm artwork remains visible past ten seconds for HTTP and HTTPS URLs. If any card still fails, inspect the first artwork failure in `%LOCALAPPDATA%\Tvivo\tvivo-launch.log`.
- Open a single-season series and a multi-season series; verify label versus interactive selector.
- Check hover, pressed, keyboard focus, checked, and selected states for title bar buttons and buttons, toggles, ComboBox, ListView/GridView, checkbox/radio, navigation, app bar, split, and repeat controls.
- Check My Tvivo ties with Latin, Arabic, digit-leading, and symbol-leading titles, including ties at the same visit count and last-tuned time.
- Click a shelf count in Movies, Series, Live TV, and My Tvivo; page through the full listing and verify counts and item types.
- Type a filter with matches and one with no matches; verify every non-match disappears and clearing restores the full shelf surface.
- Trigger All Categories refresh and Retry; capture the new stage-specific error if the provider still rejects a request.

No build or test result is claimed. The owner must run the gated build/tests and live verification.
