# Design artifacts

This directory is the portable visual handoff for future sessions. Open the HTML
files directly in a browser. They are reference artifacts, not production UI or
an invitation to bypass the product scope and focus contracts in `../ui-scope.md`.

## Android TV reference

[`android-tv-reference.html`](android-tv-reference.html) is the approved 1920 x 1080
reference. It includes the local artwork and vector assets it needs in `img/`; asset
provenance remains in [`img/CREDITS.md`](img/CREDITS.md).

| Frame | Screen / state | What it settles |
|---|---|---|
| A | Home, Account focused | Top-level navigation and focus-revealed actions |
| A2 | Home, Refresh focused and active | Non-blocking refresh feedback |
| B | Login, entry | Centred TV form and IME-safe placement |
| B2 | Login, unreachable-server error | One-line, field-local error treatment |
| C | Browse, default | Rail, grid header, collapsed item filter |
| C2 | Browse, category filter active | Live category narrowing |
| C3 | Browse, item filter active | In-place item-search field and clear action |
| D | Account | Account-only actions |
| D2 | Account, sign-out confirmation | Safe option holds initial focus |
| E | Subscription | Read-only provider data |
| F | Type scale | The six approved type roles |
| H | Splash | Startup state with real progress |
| I | App mark | Pulse-in-screen identity mark |

The whole-card invariant applies to every grid state: show only whole focusable
cards, reduce columns under constrained width, and preserve symmetric grid-edge
padding. Never retain a left gutter by clipping the rightmost card.

## Desktop proposals

These are proposed POC-D1 desktop directions. They must not be represented as
approved feature parity or used to add provider, catalog, account, or real-stream
behavior before the POC gate is satisfied.

| Artifact | States / screens |
|---|---|
| [`desktop-player-states.html`](desktop-player-states.html) | Windowed ready, windowed playing, full screen with controls, full screen picture only |
| [`desktop-journey-states.html`](desktop-journey-states.html) | Sign in, library home, movies browse |
| [`../desktop-player.md`](../desktop-player.md) | Canonical prose contract for viewport, resize, input, accessibility, and native-surface boundaries |

The desktop HTML artifacts are interactive fragments: use their state controls to
inspect the intended transitions. They deliberately use synthetic labels and no
provider or account data.

Both desktop artifacts render the exact pulse-in-screen mark used by Android from
`img/icon.svg`. The Android source vectors are also preserved in
[`android-res/`](android-res/) for implementation work:

- `ic_mark.xml` is the in-app mark without a tile background.
- `ic_launcher_mark.xml` is the launcher-mark variant with its background.
- `ic_banner.xml` is the Android TV launcher banner.

Use `ic_mark.xml` for a desktop in-app mark once the Compose Desktop image-resource
strategy is implemented. Do not copy Material glyphs as project-owned assets: their
desktop equivalent must retain the same label, action, focus treatment, and keyboard
path, while using the platform's accessible icon rendering.

## Source and update rules

- `android-tv-reference.html` is a portable copy of `../comps.html`. When the
  approved Android reference changes, update both in the same change.
- The desktop files are portable copies of the visual prototypes created for the
  desktop design pass. Update the relevant prototype and this copy together.
- Do not replace the visual artifacts with emulator screenshots. Screenshots are
  evidence for a specific build; these files are the reusable design reference.
- Keep technical content in English and never add provider details, credentials,
  or unlicensed artwork.
