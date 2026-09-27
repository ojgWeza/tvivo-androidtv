# WinUI design rules

- Orange (`#D97757`) is the app-wide accent. Keep `SystemAccentColor` and its light/dark variants, accent brushes, and control-state brushes in `src/Tvivo.App/App.xaml`; new controls inherit the same hover, pressed, checked, selected, and focus color rule. Do not set a Windows blue/gray accent on an individual control.
- Keep player selection visible as an orange row background. Do not replace it with a “now playing” status label.
- Transitions never rebuild visible content in place. Keep the current catalog source attached while the next category snapshot loads, fade the current content out before applying the new snapshot, then fade it back in. Preserve the page instance and each category's scroll/interaction state.
- Artwork flows from `Channel.LogoUri` through `CatalogCard.ArtworkUrl` to `Image.Source` as a decoded `BitmapImage`; the image uses code-behind loading, not a XAML `Source` binding. Keep the placeholder visible until `ImageOpened`, and log the item-scoped sanitized URL, HTTP status, decode result, and image failure without credentials or query values.
- Keep plot and cast text out of fixed player columns. Show an info button beside the title and display the description in an overlay flyout so the episode or related-item list does not reflow.
- App buttons use the shared 12 px corner radius from catalog cards/tiles and comfortable shared padding. Orange primary buttons must retain that shape and sizing through the global app style.
