## Feature work

| id | Change | Files | Note |
|---|---|---|---|
| **D-13** | **Category filter** — persistent bar pinned above the rail, filters category names live | `CategoryRail.kt`, `BrowseViewModel.kt` | |
| **D-14** | **Item filter** — grid-header search icon expanding in place into a pill with a clear button; filters the current category while typing, debounced 300 ms on `Dispatchers.IO`; header reports `N of M` | `ContentGrid.kt`, `BrowseViewModel.kt`, DAO query per content type |  |
| **D-15** | **Subscription** as its own read-only screen behind `Show subscription`; Account keeps only account actions | new `ui/settings/SubscriptionScreen.kt`, `SettingsScreen.kt`, `MainActivity.kt` | new `ui/settings/SubscriptionScreen.kt`, `SettingsScreen.kt`, `MainActivity.kt` — **Built** |
| **D-16** | Subscription copy **reports** `max_connections`, never asserts a limit | `SubscriptionScreen.kt` | **Built.** Reports the number and stops |
| **D-17** | Move Refresh and Exit **off** Account (they become D-7) | `SettingsScreen.kt` | **Built.** Both now live in Home's icon row |

