# WinUI design rules

- Orange (`#D97757`) is the app-wide accent. Keep `SystemAccentColor` and its light/dark variants, accent brushes, and control-state brushes in `src/Tvivo.App/App.xaml`; new controls inherit the same hover, pressed, checked, selected, and focus color rule. Do not set a Windows blue/gray accent on an individual control.
- Keep player selection visible as an orange row background. Do not replace it with a “now playing” status label.
