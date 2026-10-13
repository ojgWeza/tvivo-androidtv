# Hover summaries and light theme — implementation plan

Scope: WinUI 3 unpackaged app under windows-spike. Planning only. All paths below are relative to windows-spike. Do not install, deploy, open a provider stream, or use ExtendsContentIntoTitleBar. Each implementation slice ends with a diff review and a report of tests and pending visual checks. The owner/Haiku performs the manual checks; the current planning pass performs none.

## Feature A — hover summary

### Findings

- CatalogLandingPage.xaml:39-42 attaches a plain string ToolTipService.ToolTip binding to CatalogCard.HoverPreviewText. CatalogLandingPage.xaml.cs:1253-1276 creates cards from Channel; :3036-3117 builds HoverPreviewText from the card's current Channel.Metadata. When plot is absent, this produces the title and perhaps details, with no network lookup. This explains the report as a code-path hypothesis, not a runtime observation.
- MainWindow.xaml.cs:2457-2499,2502-2519 reads SqliteCatalogRepository.GetMetadata, fetches missing **movie** metadata with IMovieInfoProvider.GetMovieInfoAsync, calls SaveMetadata, mutates the Channel.Metadata dictionary, and calls CatalogLandingPage.NotifyMetadataChanged. The fetch starts on player entry (:1576), alongside StartPlaybackAsync (:1577); clicking the (i) button opens already loaded text rather than initiating the fetch (MainWindow.xaml:428-453). The latter notification only walks snapshot-cache shelf cards (:1314-1319); currently visible open-grid cards may be different objects (:2265-2275). Opening a series calls ISeriesCatalogProvider.GetSeriesInfoAsync and saves its metadata in MainWindow.xaml.cs:1609-1661. Player About displays Plot and Cast in :2522-2545.
- SqliteCatalogRepository.cs:139-166 stores metadata per account, type, and item ID and marks even an empty result metadata_fetched=1. Its catalog reads include plot (:373-382,:433-474,:847-853), so a rebuilt card can use cached details. Core Contracts.cs:91-92,163-169 exposes CatalogMetadata and separate movie/series info interfaces. XtreamInfrastructure.cs:158-224 maps plot/description from get_vod_info and get_series_info, both through SendApiAsync to player_api.php; XtreamInfrastructure.cs:113-151 spaces API requests by at least 300 ms and retries 429. These are metadata HTTP requests, not StreamSource playback URL opens; a provider may still rate-limit all requests for the same account. Series info also downloads episode lists (:170-215), so it costs more than movie info.
- Live has no equivalent info-fetch interface in Contracts.cs:158-169. CatalogCard already has EPG Now/Next fields in CatalogLandingPage.xaml.cs:3073-3100. Do not invent a plot for Live or fetch a stream to obtain one.

### A1. Cached, bounded hover lookup

**Goal:** One reusable browse-only metadata lookup, with no repeat request for cached or already-fetched empty details.

**Files to touch:** src/Tvivo.App/Pages/HoverMetadataLookup.cs (new internal class), tests/Tvivo.App.Tests/HoverMetadataLookupTests.cs (new).

**Do not touch:** MainWindow.xaml(.cs), playback engines, StreamSource/URL builders, SqliteCatalogRepository schema or migrations, artwork loading.

**Steps:** Implement internal HoverMetadataLookup(ICatalogProvider provider, SqliteCatalogRepository repository, optional clock) with GetAsync(ProviderAccount account, CatalogItemType type, string itemId, CancellationToken) returning CatalogMetadata?; construct one instance in CatalogLandingPage in A2. Key work by (account.AccountId, type, itemId), never title. First query repository.GetMetadata; treat a non-null metadata object, including empty Plot, as a cache hit. For Movie call IMovieInfoProvider.GetMovieInfoAsync; for Series call ISeriesCatalogProvider.GetSeriesInfoAsync and use Metadata only. Live returns null without network. Save a successful non-null result with SaveMetadata; do not persist an empty placeholder for HTTP failure. Use one SemaphoreSlim for hover requests across all cards, recheck the DB and failure cooldown after acquiring it, and keep a short in-memory failure cooldown (30 s per key) so repeatedly hovering a failed title does not retry immediately. Pass CancellationToken into provider calls; do not log URL, account, or raw exception. Keep the instance page scoped and cancel it when the page unloads or account changes. The provider's own 300 ms pacing is a second guard, not the only guard.

**Verification:** Unit tests with fake providers/repository seam: cached metadata makes zero provider calls; two near-simultaneous requests for one key make one call; requests for different keys never overlap; cancellation before the semaphore prevents a call; empty successful metadata is cached; failure is retryable after cooldown; Live makes zero calls. No test invokes playback.

### A2. Hover lifetime and visible-card update

**Goal:** Fetch only after deliberate hover and show the result on the hovered card without entering the player.

**Files to touch:** src/Tvivo.App/Pages/CatalogLandingPage.xaml, src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs, src/Tvivo.App/Pages/HoverRequestScheduler.cs (new internal delay/cancel helper), src/Tvivo.App/MainWindow.xaml.cs (only the NotifyMetadataChanged call signature at :2519); tests/Tvivo.App.Tests/HoverMetadataLookupTests.cs.

**Do not touch:** MainWindow.xaml or other player logic, player surfaces, repository migrations, EPG refresh, card focus/scale template in App.xaml.

**Steps:** Add PointerEntered/PointerExited/Unloaded handlers to the card-template Grid; resolve its DataContext as CatalogCard and use its account/type/ID, not a stale page mode. Put the 800 ms delay, generation/key check, and CancellationTokenSource replacement in HoverRequestScheduler with an injectable delay for tests; the page owns one scheduler. Pointer exit, unloading/recycling, mode/account change, and page navigation cancel pending delay and provider request; use CatalogLandingPage.SetActive(false) (:173-178), which MainWindow.ShowPage already calls (:1064), as the navigation cancellation point. A completion must verify the same card object/key is still hovered before changing UI. Reuse cached Plot immediately with no delay. On success, merge non-empty metadata fields into every rendered matching Channel (shelf, open grid, spotlight, and snapshot cache), then call NotifyMetadataChanged on every matching CatalogCard; expand the current :1314-1319 helper to accept (account ID, type, item ID) and include visible open-grid cards. Preserve existing focus/hover transforms, selection, scroll restoration, and artwork pipeline. If the built-in ToolTip fails to refresh while open, explicitly replace its Content on the UI thread for that hovered card; test this visually before adding a custom popup.

**Verification:** Unit test the delay/cancel/key-generation state machine with an injectable delay: pointer exit or recycle before 700 ms makes zero calls; a stale completion cannot update a different card; cache hit updates immediately. Haiku: on a movie with a known provider summary, hover from a fresh catalog without opening player, wait ~1 s, capture tooltip with plot; leave before delay on a different title and verify no late tooltip update; repeat hover and confirm no second metadata request via a redacted request-count log or fake provider. Do not open a stream.

### A3. Readable tooltip content

**Goal:** A bounded, readable summary on movies and series, useful Live text.

**Files to touch:** src/Tvivo.App/Pages/CatalogLandingPage.xaml, src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs; tests/Tvivo.App.Tests/CatalogHoverTextTests.cs (new, only for text shaping).

**Do not touch:** MainWindow.xaml(.cs), player About flyout, card geometry/focus styles in App.xaml, provider APIs.

**Steps:** Replace the string tooltip with a ToolTip containing a StackPanel/TextBlocks bound to title, DetailsLine, Plot, and optional Cast/EPG properties. Explicitly set the tooltip DataContext to its owning CatalogCard when attached/realized; do not assume a popup inherits DataContext or RequestedTheme from the card. Give it 320-420 px maximum width, 12-16 px padding, TextWrapping=WrapWholeWords, and a bounded height (~280 px) with internal ScrollViewer for longer plots; do not silently truncate the summary to one line. Limit Cast to a short separate line (e.g. 160 characters with ellipsis) while leaving full Plot scrollable. For an unknown Movie/Series plot, show “Loading summary…” from the start of hover while the network request waits for debounce; if the API returns no plot show “No summary available”, never a title-only tooltip. For Live, show channel name plus existing Now/Next when available, otherwise “No programme summary available”; never call a Live info endpoint. Set the popup's RequestedTheme to the active catalog theme, use themed tooltip surface/text resources, and give it an accessible name that includes the summary. Extract a pure formatter only if text normalization/truncation needs tests; otherwise manual checks suffice.

**Verification:** Unit-test null/blank plot, whitespace, long cast, and Live fallback if a formatter is extracted. Haiku screenshot checks: first-hover movie, long plot with scroll, series with cached plot, Live with and without EPG, card near right/bottom window edge; tooltip must stay on screen, wrap, remain legible in both themes, and not cover a focused card permanently. No playback.

## Feature B — cream / teal / burnt-orange light theme

### Findings and decision

- App.xaml:6 forces Dark; CatalogLandingPage.xaml:7 independently forces Dark. App.xaml:20-102 contains semantic Color/SolidColorBrush keys and control accent overrides; :103-374 defines custom styles and a card focus ring, mostly using StaticResource. MainWindow.xaml:7, MainWindow.xaml.cs:175-240 constructs the window and sets DWM dark mode; App.xaml.cs:70-86 creates/activates it. Header refresh/settings placement is MainWindow.xaml:93-181. MyTvivoLayoutStore.cs:9-51 demonstrates safe JSON under TvivoDataPaths.For with a temporary file and atomic replace; use a separate theme JSON file, not this layout file.
- Recommend **Light/Dark/HighContrast ThemeDictionaries plus ThemeResource references**, rather than recoloring shared brush objects. ThemeResource re-evaluates on runtime theme changes; StaticResource is loaded once. Root FrameworkElement.RequestedTheme can switch at runtime, while Application.RequestedTheme is a startup default. This follows [Microsoft theme-resource guidance](https://learn.microsoft.com/en-us/windows/apps/develop/platform/xaml/xaml-theme-resources) and [FrameworkElement.RequestedTheme](https://learn.microsoft.com/en-us/windows/windows-app-sdk/api/winrt/microsoft.ui.xaml.frameworkelement.requestedtheme?view=windows-app-sdk-1.8). Retain App.xaml's Dark startup default; remove the CatalogLandingPage.xaml local Dark override and set WindowRoot.RequestedTheme from persisted choice after InitializeComponent but **before Activate**. Also set DWM caption mode before activation. This should avoid a light-content flash, but must be verified by a three-launch recording. Do not set Application.RequestedTheme after launch.
- Convert all uses of color/brush keys in XAML, including custom style setters and visual states, to ThemeResource. StaticResource for non-color styles/templates/fonts may stay. Code-behind brush assignments in MainWindow.xaml.cs, CatalogLandingPage.xaml.cs, AccountPage.xaml.cs and ShellNavigationButton.xaml.cs must use a theme-aware resolver for the active window/page, then reapply explicit assignments on theme switch; Application.Current.Resources indexed lookups can otherwise resolve the app's Dark default. Theme dictionaries need HighContrast entries using system text/background/accent colors rather than the brand palette. Do not disable high-contrast adjustment.

### Proposed light roles and contrast

WCAG ratios below use opaque sRGB relative luminance. Normal text target is 4.5:1 and large text/UI boundary target is 3:1 per [WCAG 2.2 contrast guidance](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html). Actual alpha overlays and artwork need screenshot checks.

| Role | Dark existing | Light proposed | Relevant contrast |
|---|---|---|---|
| canvas / cream | #0A1619 | #FFF5E5 | burnt text 8.47:1 |
| deep/header | #071114 | #F4E5CC | burnt text 7.37:1 on #F4E5CC |
| panel surface | #0F2126 | #FFF9EF | burnt text 8.73:1 |
| raised surface | #163036 | #EBD9BA | burnt text 6.61:1 |
| accent / teal | #D97757 | #006B68 | teal on cream 5.89:1; cream on teal 5.89:1 |
| accent soft | #E7A084 | #005653 | soft teal on cream 7.92:1 |
| primary foreground / deep burnt orange | #E8F1F2 | #7A3216 | 8.47:1 on canvas |
| muted foreground | #93ACB1 | #6A4834 | 7.51:1 on canvas |
| success / favorite / error | #8BC8A0 / #F2C94C / #FFFF8A80 | #14664B / #9D5E00 / #8B280F | 6.41 / 4.82 / 8.07:1 on canvas |
| line / focus tint | #1FE8F1F2 / #33E7A084 | #4D7A3216 / #33006B68 | decorative alpha; solid teal focus ring 5.89:1 on cream |

**Known bad combinations:** burnt #7A3216 on teal #006B68 is only **1.44:1**; use cream #FFF5E5 on teal for filled buttons, badges, selected playlist rows, and selected mode buttons. Burnt on a dark video strip (~#333333 under 80% black over white video) is **1.38:1**; keep fixed dark media scrims with light #FFF5E5 text (**11.70:1** on that worst-case strip), including icons/secondary text. Teal on the raised cream #EBD9BA is 4.59:1, just above normal-text threshold; avoid lower-opacity teal text there. A light translucent surface over arbitrary video has no guaranteed contrast: keep cinema/mini controls on a dark scrim. Cream logo glyph on teal is 5.89:1.

### Complete hard-coded color/brush audit (source at plan time)

Repeated literals are grouped, but every literal occurrence found by the XAML/C# color scan is represented. “Transparent” is a layout/hit-test value and remains transparent. App palette keys become the light roles above; fixed media colors deliberately remain dark.

| Source lines / element or key | Current hard code | Light mapping / action |
|---|---|---|
| App.xaml:20-39, AppCanvas/Deep/Surface/SurfaceRaised/Accent/AccentSoft/Text/Muted/Good/Favorite/FocusTint/Line Color; SystemAccentColor and Light1-3/Dark1-3 (:26-32) | dark teal, orange, pale text values listed there | Put each key in Dark/Light/HighContrast dictionaries. Light base roles above; SystemAccentColor=#006B68, lighter teal steps for hover surfaces, darker teal steps for pressed strokes. Ensure filled accent text uses AppOnAccentBrush=#FFF5E5. |
| App.xaml:56-102, SystemControlHighlightListAccentLow/Medium; AccentFillColorSecondary/Tertiary/Disabled; AccentTextFillColorDisabled; Button, ToggleButton, ComboBoxItem, ListViewItem, GridViewItem, CheckBox, RadioButton, NavigationViewItem, AppBarButton, SplitButton, RepeaterButton hover/pressed/selected brushes | #33/#4D/#66/#80/#B3D97757 variants | Keep each existing alpha, replace orange RGB with teal #006B68 in Light dictionary; inspect contrast of selected text and use opaque teal for text/strokes. |
| App.xaml:207,213,258,264 custom caption/toggle visual states | #33/#4D/#66D97757 | ThemeResource hover/pressed brushes from the dictionaries; do not leave orange literal. |
| App.xaml:112,123,131,139-155,162,193,220,314 and MainWindow.xaml:29,121,141,275,400-421,569,603,625; CatalogLandingPage.xaml:31,188; code-behind Transparent at MainWindow.xaml.cs:1476,4240, CatalogLandingPage.xaml.cs:1734,2902, AccountPage.xaml.cs:180 | Transparent / Colors.Transparent | Keep Transparent; these are intentionally clear hit targets/backgrounds, not visible palette colors. |
| CatalogLandingPage.xaml:20-22 placeholder gradient | #12383F, #1B5557, #A66B43 | Light cream/teal placeholder: #EBD9BA → #B8D9D1 → #006B68. Put stops behind theme resources. |
| CatalogLandingPage.xaml:28-29,31,36 placeholder line art and wordmark | #DDF4EDE5, #EAF4EDE5, #F0BE86, #CFE8E2D8 | On light placeholder use opaque #006B68 for frame, #7A3216 for waveform/wordmark. Verify over all three gradient stops; use a small cream backing for wordmark if any section fails contrast. |
| CatalogLandingPage.xaml:58-59 card artwork footer gradient | #00071114 → #F0071114 | Keep dark media gradient in both modes; set card overlay title/details/EPG to dedicated AppOnMediaText/Muted brushes (#FFF5E5 / #D9E9E6). Burnt text here is unreadable. |
| CatalogLandingPage.xaml:451-452 scroll-arrow pill | #CC0B1620, #33FFFFFF | On cream page use #EBD9BA opaque with #4D7A3216 border and burnt icon; on artwork overlap, use dark media pill + cream icon. Verify its actual background at scroll positions. |
| MainWindow.xaml:25 title-bar brand glyph | #FFFFFFFF | AppOnAccentBrush=#FFF5E5 on teal in Light; preserve white on orange in Dark. |
| MainWindow.xaml:265-266 cinema title gradient | #CC000000 → #00000000 | Keep fixed dark scrim; use AppOnMediaTextBrush for title. |
| MainWindow.xaml:290,316 mini top/bottom strips | #CC000000 | Keep fixed dark scrims; compact controls must use on-media icon/text brushes or a Dark theme scope. |
| MainWindow.xaml:299,330,339 mini title/times | White | AppOnMediaTextBrush=#FFF5E5 in Light; white in Dark. |
| MainWindow.xaml:632 player related progress track | #33FFFFFF | On normal light side panel use #4D7A3216; if selected teal row, use a cream-tinted track. |
| MainWindow.xaml.cs:4097 VlcTransportBar cinema derived brush | alpha E0 of AppPanelSurfaceBrush | In cinema use a dark #E0071114 media panel independent of page theme; reapply when entering/exiting cinema and when toggling theme. |
| AccountPage.xaml:127 and ProviderSetupPage.xaml:34 ErrorText | #FFFF8A80 | AppDangerTextBrush: #8B280F on light cream, current salmon on dark. |
| AccountPage.xaml.cs:212 expired date | Colors.OrangeRed | Use AppDangerTextBrush; never inherit accent teal for an error. |

### B1. Persistent preference

**Goal:** A safe, unpackaged-app JSON preference with Dark as the missing/corrupt fallback.

**Files to touch:** src/Tvivo.Infrastructure/ThemePreferenceStore.cs (new), tests/Tvivo.Infrastructure.Tests/ThemePreferenceStoreTests.cs (new).

**Do not touch:** MyTvivoLayoutStore.cs, credentials/DataStore, App.xaml, MainWindow.xaml(.cs), installer.

**Steps:** Use TvivoDataPaths.For("theme-preference.json"), JSON { "theme": "Dark"|"Light" }, temp-file plus File.Move overwrite as in MyTvivoLayoutStore.cs:13-51. Read only the two exact values; missing/malformed/unknown returns Dark. Keep this preference global to the local app, not per provider. Write tests with a temp path for round trip, corrupt JSON, unknown value, and failed/partial temp write preserving last good file. Never use ApplicationData.Current.LocalSettings (unpackaged regression recorded in TODO.md:260-264).

**Verification:** Run only ThemePreferenceStoreTests in Tvivo.Infrastructure.Tests; inspect temporary test files, not real user data.

### B2. Theme resource foundation

**Goal:** Define all semantic roles and make custom XAML styles switchable.

**Files to touch:** src/Tvivo.App/App.xaml.

**Do not touch:** MainWindow.xaml(.cs), CatalogLandingPage.xaml(.cs), AccountPage.xaml(.cs), playback engines, window title-bar mechanism.

**Steps:** Move App color and brush roles (:20-102) into Light/Dark/HighContrast ThemeDictionaries, add AppOnAccentBrush, AppOnMediaTextBrush, AppOnMediaMutedBrush, AppDangerTextBrush, AppMediaScrimBrush, and AppScrollPillBrush. Keep existing dark colors visually identical. Use system colors for HighContrast. Replace StaticResource with ThemeResource for color/brush references in App.xaml custom styles and visual-state setters (:103-374), including card FocusRing; keep non-color style/font references StaticResource. Replace literal orange state setters (:207,213,258,264). Ensure style keys remain available in all themes.

**Verification:** Review the XAML keys in Dark/Light/HighContrast and search for orange literals outside the Dark palette. Haiku later checks focus ring and caption/toggle states; no runtime claim from this slice alone.

### B3. Toggle and startup application

**Goal:** One-click sun/moon switch, persistence, and correct first frame.

**Files to touch:** src/Tvivo.App/MainWindow.xaml, src/Tvivo.App/MainWindow.xaml.cs, src/Tvivo.App/App.xaml.cs, src/Tvivo.App/AppThemeBrushResolver.cs (new small helper).

**Do not touch:** ExtendsContentIntoTitleBar or WindowChrome layout logic beyond adding the header button, MyTvivoLayoutStore.cs, playback engines, installer.

**Steps:** Add a small AppIconButtonStyle button in MainWindow.xaml header stack immediately beside TopRefreshButton and AccountNavigation (:164-181). Dark shows Segoe Fluent E706 sun and automation/tooltip “Switch to light theme”; Light shows E708 crescent moon and “Switch to dark theme”. Read ThemePreferenceStore before constructing/activating MainWindow in App.OnLaunched or at the start of MainWindow construction; assign WindowRoot.RequestedTheme and DWM dark-caption value (1 Dark, 0 Light) before first Activate. On click, apply root theme, update glyph/label, then persist; on write failure show a non-blocking error while keeping the selected in-memory theme for this run. Add AppThemeBrushResolver.For(FrameworkElement, key) for code-behind: choose the Light/Dark theme dictionary from element.ActualTheme, or HighContrast when Windows high contrast is active; fail visibly for a missing key instead of silently returning a dark brush. Subsequent page/player slices use this helper in their ActualThemeChanged refreshes. Keep App.xaml startup Dark and never set Application.RequestedTheme at runtime. Preserve system High Contrast when active. Do not change the window title-bar extension.

**Verification:** B1 unit tests cover persisted choice and fallback. Haiku: click Dark→Light→Dark and inspect root ActualTheme, glyph/accessible label, and saved JSON; check keyboard Tab/Enter/Space and no button collision at minimum 776 px catalog width. The full light screenshots and three-launch no-flash check are final-gate checks after B4-B7; incomplete surfaces at this slice are expected.

### B4. Catalog surfaces

**Goal:** Cream catalog, readable artwork cards, spotlight, tooltip, and scroll controls.

**Files to touch:** src/Tvivo.App/Pages/CatalogLandingPage.xaml, src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs.

**Do not touch:** repository/provider code, HomePage/ProviderSetupPage/AccountPage, card sizing, focus/scale animations in App.xaml, artwork cache, playback.

**Steps:** Remove CatalogLandingPage.xaml:7 RequestedTheme="Dark". Change color/brush usages from StaticResource to ThemeResource in this page. Map every CatalogLandingPage literal in the audit table, including the placeholder and arrow pill. Make artwork footer a Dark requested-theme scope (or dedicated on-media brushes) so card title, quality badge, EPG, progress labels stay readable over photos in both modes; do not apply burnt text over the dark footer. Spotlight is a theme-colored panel, so burnt text on its light cream gradient is appropriate. Theme the A3 tooltip. Add a page-level ActualThemeChanged handler to reapply code-behind brush assignments, including indicator dots and selected buttons at :1734-1737,:2632-2640,:2897-2902; use AppThemeBrushResolver with this page as the element.

**Verification:** Haiku screenshots in both themes: For You, Movies, Series, Live, open shelf, empty/loading/error, one artwork card and one placeholder, spotlight, hover/focus card pop, scroll-arrow pill. Specifically compare selected text and rings; text must not disappear on artwork.

### B5. Setup, Home, and Settings

**Goal:** Theme the remaining non-player pages and the expired/error states.

**Files to touch:** src/Tvivo.App/Pages/HomePage.xaml, src/Tvivo.App/Pages/ProviderSetupPage.xaml, src/Tvivo.App/Pages/AccountPage.xaml, src/Tvivo.App/Pages/AccountPage.xaml.cs, src/Tvivo.App/Controls/ShellNavigationButton.xaml.cs.

**Do not touch:** CatalogLandingPage.xaml(.cs), MainWindow.xaml(.cs), repository/provider code, playback, MyTvivoLayoutStore.

**Steps:** Change color/brush usages from StaticResource to ThemeResource in these three pages. Map the two ErrorText literals to AppDangerTextBrush. Replace AccountPage.xaml.cs:212 OrangeRed with AppDangerTextBrush. On ActualThemeChanged, reapply the AccountPage tab selection brushes (:175-180) and ShellNavigationButton foreground/background (:31-34) through AppThemeBrushResolver using each control's root element; keep existing selections. Confirm Settings ComboBox/ScrollViewer default Light styling does not retain a dark custom brush.

**Verification:** Haiku screenshots in Light and Dark: Setup with error, Home shelves, every Settings tab including expired state, open ComboBox, scrollbars, and focus. Verify legibility of the error copy and the selected tab.

### B6. Shell and normal player

**Goal:** Theme the header, normal player, About, playlist, and default flyouts.

**Files to touch:** src/Tvivo.App/MainWindow.xaml, src/Tvivo.App/MainWindow.xaml.cs.

**Do not touch:** CompactChrome/CinemaTitleStrip and cinema-specific VlcTransportBar styling (B7), playback engines/services, stream URLs, seek/stall behavior, App.xaml palette definitions, title-bar extension.

**Steps:** Convert normal-surface color/brush usages in MainWindow.xaml to ThemeResource. Change the brand glyph at :25 to AppOnAccentBrush; map player related progress track at :632 with selected-row-aware colors. Make the normal player About flyout, volume/track flyouts, next-episode prompt, playlist selected row, dialogs, and scrollbars use active theme resources. Replace MainWindow.xaml.cs Application.Current.Resources brush lookups with AppThemeBrushResolver.For(WindowRoot, key); reapply explicit foreground/background/selected brushes on ActualThemeChanged, especially :1465-1476,:1934-1935,:4230-4275. Keep the existing header layout and player behavior.

**Verification:** Haiku screenshots in Light and Dark: header at normal/minimum width, normal player using a local fixture only, About, audio/subtitle menu, volume flyout, playlist selected row, next-episode prompt if locally inducible, exit dialog, scrollbar thumb and focus ring. No provider stream.

### B7. Cinema and mini-player overlays

**Goal:** Keep video overlays legible in both page themes.

**Files to touch:** src/Tvivo.App/MainWindow.xaml, src/Tvivo.App/MainWindow.xaml.cs.

**Do not touch:** normal-player layout, playback engines/services, stream URLs, seek/stall logic, window-mini geometry, App.xaml palette definitions.

**Steps:** Map the MainWindow cinema/mini literals in the audit table (:265-266,:290,:299,:316,:330,:339). Keep CinemaTitleStrip and CompactChrome on fixed dark scrims with AppOnMediaText/Muted brushes; set their local RequestedTheme=Dark if needed for built-in slider/button/flyout controls, then explicitly set on-media icons/text. At MainWindow.xaml.cs:4095-4098, use dark translucent AppMediaScrimBrush in cinema and the theme panel surface in normal player; reapply when presentation or theme changes. Check title, time labels, transport icons, prompts, compact volume and seek controls against both bright and dark video frames.

**Verification:** Haiku screenshots at Light and Dark with a local bright/dark fixture: cinema title/transport visible and faded, compact and window-mini player, prompt and media flyouts. Check readability and window-mini controls; do not open a provider stream.

## Gate, order, risks, owner decisions

**Implementation order:** A1 → A2 → A3 → B1 → B2 → B3 → B4 → B5 → B6 → B7. B4 consumes the A3 tooltip. After each slice, inspect targeted diff and run its listed unit tests when possible. Final compile gate, with the app closed, is **dotnet build src/Tvivo.App/Tvivo.App.csproj -c Debug -p:Platform=x64** from windows-spike. **dotnet build Tvivo.sln does not build Tvivo.App.** Run the App/Infrastructure unit-test projects for changed logic and the full existing test suite at the final gate; implementation owner performs these later. Capture three startup launches with Light stored and check the first frame for a dark flash, then repeat with Dark stored. This planning pass must not build, test, compile, or launch.

**Risks:** A series hover transfers the full episode-list response; the debounce, one-request gate, DB cache, and failure cooldown are mandatory. Some providers may count API requests against account limits even though the endpoint is not a stream. Tooltip live-update behavior is a WinUI runtime uncertainty requiring Haiku's hover screenshot. Theme dictionaries plus existing StaticResource and code-behind brush assignment can leave stale colors; treat a visible stale surface as a failed slice. Bright artwork/video invalidates contrast assumptions for translucent scrims; capture both bright and dark frames. High Contrast needs its own acceptance pass. Existing working tree has many unrelated changes; restrict each slice diff to its listed files.

**Open questions for owner:** (1) Is network fetching series summaries on hover acceptable despite get_series_info returning the full episode list? This plan says yes with the strict gate; a no changes Series to cached-only. (2) Should a successful but empty provider summary show the explicit “No summary available” fallback, as proposed? (3) Which local fixture and screenshot sizes should Haiku use for the final player/mini/cinema acceptance? None of these questions blocks writing the plan.
