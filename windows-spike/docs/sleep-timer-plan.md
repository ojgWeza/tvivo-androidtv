# Sleep timer plan for Tvivo on Windows

**Owner summary**

A viewer can choose when playback stops from a clearly named **Sleep timer** control in the player.  
The timer works for movies, episodes, and Live channels, including when the player is small or the window is minimised.  
A quiet warning gives the viewer one large **Add 15 minutes** button.  
When time runs out, Tvivo saves the viewing position, stops playback and its network connection, and shows a calm **Sleep timer ended** screen.  
It never starts another episode or movie after the timer ends.  
The timer is for the current app session; closing and reopening Tvivo does not restart it.  
The first release should use a reliable stop. A short volume fade is a separately verified enhancement.

## Basis for the design

[YouTube’s sleep timer](https://support.google.com/youtube/answer/15397997?hl=en) lives in the player, lets viewers adjust it later, pauses playback, and gives a silent notice. [VLC for Android](https://docs.videolan.me/vlc-user/android/3.X/en/video/video_player.html) places its timer among player options and closes video playback when it expires. Tvivo should **stop both playback engines**, since stopping also releases the stream connection; a paused engine may retain it.

[Netflix’s “Are you still watching?” prompt](https://help.netflix.com/en/node/114059) is an inactivity check after continued viewing, not a viewer-set sleep timer. [mpv documents script timers](https://github.com/mpv-player/mpv/blob/master/DOCS/man/lua.rst), but that does not establish a standard sleep-timer interface. Device power timers also serve a different purpose. Tvivo should stop its own playback, never promise to turn off Windows or the display.

The current player has one app-owned transport for both engines, moves it over the video in cinema mode, and has separate mini-player controls ([MainWindow.xaml](D:/HCode/Tvivo/windows-spike/src/Tvivo.App/MainWindow.xaml:495), [MainWindow.xaml.cs](D:/HCode/Tvivo/windows-spike/src/Tvivo.App/MainWindow.xaml.cs:4959)). That supports one timer state shared by every presentation.

## 1. Controls and wording

| Place | Proposed control |
|---|---|
| Normal player | A labelled **Sleep timer** button beside the other transport options. Its flyout contains **Off**, **15, 30, 45, 60, 90 minutes**, **At the end of this movie/episode**, and **Choose minutes…**. |
| Cinema and fullscreen | The same button in the floating transport. Pointer movement or a key press reveals it under the existing cinema rules. The warning and ended screen stay visible even when the transport fades away. |
| In-window and window mini-player | A **Sleep timer** button in the mini controls, with the same choices. Keep a short, readable status such as **Timer: 24 min** in the mini title strip when armed. Do not squeeze the full warning into a tiny card; offer its large actions in a compact panel or expand to the normal player if the available size cannot hold them. |
| Keyboard | **Ctrl+Shift+T** opens the timer choices while a player is active. Do not intercept it while a text field is being edited. Escape closes the flyout; Escape or the **Back** button on the ended screen follows normal Back navigation. |

**Choose minutes…** uses a visibly labelled number field, 5–240 minutes in five-minute steps, with **Start timer** and **Cancel**. Default the field to 30 minutes. The end-of-item choice is absent for Live, whose end is not predictable. Use “this movie” or “this episode,” not a generic “item.”

After selection, show a persistent status line such as **Sleep timer · 29 min left**; at one minute, show seconds. Opening the button shows **Add 15 minutes**, **Change timer**, and **Turn off timer**. At two minutes remaining, show one quiet, nonmodal warning: **Playback will stop in 2 minutes**, with large **Add 15 minutes** and **Turn off timer** buttons. No sound, flashing, shimmer, pulsing, or repeated pop-ups. Extending or cancelling dismisses it. An end-of-item timer shows **Stops when this episode ends** rather than an invented countdown.

Use plain text alongside the icon, at least 18–20 px text in the full player, generous 44×44 px or larger targets, wrapping labels, visible keyboard focus, and theme brushes that remain legible in Dark and Windows Contrast themes. The mini-player may use shorter visible text, but must keep full screen-reader names. Examples: **“Sleep timer, 24 minutes left”**, **“Add 15 minutes to sleep timer”**, and **“Turn off sleep timer.”** Announce arming, the two-minute warning, and completion politely; do not announce every countdown update. WinUI’s `AutomationProperties.Name` and `LiveSetting` support these names and announcements ([Microsoft documentation](https://learn.microsoft.com/en-us/windows/windows-app-sdk/api/winrt/microsoft.ui.xaml.automation.automationproperties)).

## 2. Behaviour contract

| Event | Result |
|---|---|
| Time reaches zero | Set stop intent first, suppress next-item actions, save the current movie or episode position, stop and release both engines, then show a static dark **Sleep timer ended** screen. Offer **Watch again / Watch this channel** and **Back to catalog**. Neither action runs automatically. |
| Two minutes remain | Show the quiet warning once. **Add 15 minutes** adds 15 minutes to the existing deadline; it does not restart the countdown from 15 minutes. |
| User pauses | The duration timer continues to count real elapsed time. If it expires while paused, stop without a fade. Seeking does not alter the deadline. |
| User changes movie, episode, or Live channel | A duration timer continues across the viewing session. **At the end of this movie/episode** applies to the item named when it was set; a manual switch cancels it and briefly says **Sleep timer turned off after changing video**. |
| Natural end | With an end-of-item timer, stop before the existing next-episode/movie prompt or autoplay can run. Only a *genuine* end qualifies; an early or failed end follows recovery. With a duration timer, normal autoplay may continue until the deadline. |
| Shuffle and autoplay-next | Keep the saved playlist preference, but the timer’s ending takes priority for this session. Hide a competing next-item prompt while the sleep warning or ended screen is shown. |
| Live | Duration choices work. “End of this item” is unavailable. A Live interruption still follows normal recovery while time remains. |
| Buffering or reconnecting | The deadline continues. Expiry cancels the open/recovery attempt and wins over its later callback. |
| Final playback failure | Turn the timer off and show the existing Retry state; Retry does not silently rearm it. |
| Back, Home, closing the mini-player, or closing Tvivo | Cancel the timer as part of normal player teardown. Merely switching between full, cinema, compact, and window-mini views preserves it. |
| Windows minimises Tvivo | Keep counting without restoring the window or taking keyboard focus. If time expires, stop playback; show the ended screen when the viewer returns. |
| System sleep and resume | Use a monotonic elapsed-time source. On resume, check the deadline before recovery or autoplay; if it passed, stop immediately without playing a catch-up fade. Changing the wall clock or time zone must have no effect. |
| App restart | Do not restore an active timer or an expired screen. Normal resume-position storage still works. |

For Windows, `Stopwatch.GetTimestamp()` is suitable for the deadline: Microsoft documents its underlying performance counter as including standby and hibernation time ([high-resolution timestamp guidance](https://learn.microsoft.com/en-us/windows/win32/sysinfo/acquiring-high-resolution-time-stamps)). Do not use `DateTimeOffset.UtcNow` to decide expiry. The existing 500 ms player UI tick may refresh the display, but timer correctness must also be checked on activation/resume and immediately before starting another item.

**Fade:** Recommend a five-second *audio-only* fade during the final five seconds, followed by the actual stop at zero. No video brightness animation. This is a Tvivo-specific, **experimental** addition: VLC and YouTube document stopping/pausing, not a fade. Ship it only after both engines, mute, manual volume changes, and interruption races pass. The reliable first slice can stop directly at zero. A manual volume change or **Add 15 minutes** during a fade cancels the fade and restores the viewer’s chosen volume; a new play must never inherit faded volume.

## 3. Player integration and regression guards

Implement a dedicated `EndPlaybackForSleepTimerAsync` path in [MainWindow.xaml.cs](D:/HCode/Tvivo/windows-spike/src/Tvivo.App/MainWindow.xaml.cs:2939), sharing the existing session cancellation, generation increment, resume save, and two-engine stop mechanisms. `StopPlaybackForNavigation` currently clears the source, title, controls, and page state, so calling it unchanged would erase the proposed ended screen.

Set `PlaybackUserIntent.StoppedByUser` **before** stopping either engine. [PlaybackInterruptionPolicy.Classify](D:/HCode/Tvivo/windows-spike/src/Tvivo.Core/PlaybackInterruptionPolicy.cs:51) already rejects recovery unless intent is `Playing`; its callback paths and `RecoverPlaybackAsync` must also observe the generation change. Add an explicit sleep-ended guard at the native failure callback, VLC state callback, UI tick, autoplay branch, retry command, and play/pause shortcut. Suppress `_nextEpisodeAutoPlaySuppressed` and `_nextEpisodeAdvanceStarted` before stopping. Clear the prepared next item and hide `NextEpisodePrompt`. A timer expiry must never become a stall, unexpected-end recovery, or final-failure Retry screen.

Save progress before clearing the source. A timed stop partway through a movie or episode must remain **in progress**, not become watched merely because the engine later reports `Ended`. At a genuine natural end, keep the normal completion result. Log `event=sleep-timer.armed`, `.extended`, `.cancelled`, and `.ended` with mode, minutes, and a non-sensitive reason only—no titles, URLs, account details, or stream IDs.

Keep an unfaded volume baseline separate from the engine’s temporary fade level and from the existing mute state. Restore it on extension, cancellation, stop completion, engine fallback, and every new playback start. Update both volume sliders without firing their normal value-change path. This matters because the current handlers write slider changes directly to `_engine.Volume` ([MainWindow.xaml.cs](D:/HCode/Tvivo/windows-spike/src/Tvivo.App/MainWindow.xaml.cs:4087)).

Protect [player-invariants.md](D:/HCode/Tvivo/windows-spike/docs/player-invariants.md): a new playback still clears the old picture immediately, each host remains visible under its curtain before opening, and failure never revives an old frame. The ended screen should be its own static layer over an opaque black curtain, with the video hosts hidden **after** the stop; it must not reuse the popcorn loading art or appear as Retry. Normal Back and subsequent Play must still follow the established reset and handoff path.

## 4. Core and App work

**Pure Core policy:** Add `SleepTimerPolicy.cs` in `src/Tvivo.Core/`. Give it explicit states (`Off`, `Armed`, `Warning`, `Fading`, `Ended`), modes (`Duration`, `EndOfCurrentItem`), a monotonic start/deadline supplied by the caller, and transition methods for tick, extend, cancel, manual item change, genuine end, and final failure. It returns decisions such as `ShowWarning`, `StopPlayback`, and fade fraction; it never calls a player, clock, file system, or WinUI control. Represent the end-of-item target with an opaque item identity. Clamp custom input and remaining-time display in Core.

**App wiring:** Add the controls and static ended layer to `src/Tvivo.App/MainWindow.xaml`; wire them in `MainWindow.xaml.cs` to `PlaybackUiTimer_Tick`, `StartPlaybackAsync`, `ResetPlayerForNewPlayback`, `OpenSeriesEpisode`, `PlayRelatedChannel`, `ShowPage`, `StopPlaybackForNavigation`, `OnClosed`, cinema layout and cursor handling, both mini-player transitions, and both engine callbacks. Timer state belongs to the viewing session, **not** the changing playback generation: recovery and automatic next-item starts must preserve a duration timer. Add a single `CancelSleepTimer(reason)` helper used by navigation and final failure.

No active timer goes into `playback-options.json`. If a later settings default is approved, add it through `PlaybackModePreferences` in `src/Tvivo.Core/PlaybackMode.cs`, its read/write calls in `MainWindow.xaml.cs`, and the existing **Settings → Player** panel in `Pages/AccountPage.xaml(.cs)`; its current serializer rewrites that file, so a new field needs a backward-compatible default.

## 5. Small implementation slices

Effort is a rough coding estimate, excluding review and human playback checks. Work in order. Assign **one owner at a time** to `MainWindow.xaml.cs` and `MainWindow.xaml` to avoid merge collisions.

| Slice | Owner and files | Deliverable | Effort |
|---|---|---|---|
| 1 | Luna: `Tvivo.Core/SleepTimerPolicy.cs`; later Opus-owned `Tvivo.Core.Tests/SleepTimerPolicyTests.cs` | Pure state and timing contract, including all modes and boundary decisions. | 0.5–1 day |
| 2 | Luna: `MainWindow.xaml.cs` only | Session state, monotonic checks, cancellation, recovery/autoplay priority, direct stop at zero, progress save, safe volume restoration. | 1–1.5 days |
| 3 | Luna: `MainWindow.xaml` and `MainWindow.xaml.cs` | Full-player/cinema button, choices, countdown, warning, ended screen, keyboard and screen-reader names. | 1–1.5 days |
| 4 | Luna: same App files, after slice 3 is merged | Mini-player and window-mini access, constrained layout, minimise/resume behaviour. | 0.5–1 day |
| 5 | Luna: App files; Opus writes separate test cases | Five-second audio fade behind the tested direct-stop path; only retain it if both engines behave correctly. | 0.5–1 day |
| 6 | Opus test-writer owns test files and test plan; implementer owns code fixes | Regression tests, dark-theme human look, focused corrections, documentation of the chosen behaviour in `docs/player-invariants.md` and release notes when shipped. | 0.5–1 day plus human check |

## 6. Test plan for the separate Opus test-writer

Unit tests in `tests/Tvivo.Core.Tests/SleepTimerPolicyTests.cs` should use supplied elapsed times, never sleep or open a stream. Cover: each preset and custom bounds; exact warning and deadline boundaries; one warning only; +15 minutes from the existing deadline; cancellation; paused, seek and wall-clock-independent behaviour; duration across manual and automatic item changes; end-of-item genuine versus unexpected end; Live rejecting end-of-item; final failure; system-resume-sized time jump; duplicate tick idempotence; stale generation/callback decisions; and fade fractions plus restoration decisions. Add focused tests beside `PlaybackInterruptionPolicyTests.cs` showing stopped intent cannot recover, and beside `PlaybackModeTests.cs` for the “timer wins over next/shuffle” decision if that decision is factored into Core.

The short **human look** should use **Dark theme with audio muted** and a local permitted fixture, one playback at a time. Check 100%, 150%, and 200% display scale; Windows Contrast theme as an additional check; keyboard alone and pointer alone; Narrator names and warning announcement; normal, cinema/fullscreen, in-window mini, window-mini, and taskbar-minimised states; both Native and LibVLC. Watch the two-minute warning, extension, cancellation, genuine item end, expired static screen, Back, and a fresh play with the prior volume restored. Confirm no flashing, trapped focus, hidden action, old frame, Retry prompt, unwanted next item, or sound after expiry. Use the existing [interruption verification](D:/HCode/Tvivo/windows-spike/docs/playback-interruption-verification.md) as a regression reference, while keeping its diagnostics free of titles and URLs. The [1.0.1 notes](D:/HCode/Tvivo/windows-spike/docs/release-notes-1.0.1.md) already list unverified player layouts and contrast details, so the human look should include those nearby surfaces.

## Optional additions, ranked

1. **Remember the last chosen duration in Settings → Player:** useful for repeat use, while keeping the active timer temporary. Add only after the base flow is clear.
2. **Stop after a chosen number of episodes:** useful for series, but more complex around manual skips and shuffle.
3. **Configurable fade length:** low value until a fixed fade proves reliable on both engines.
4. **Dim or turn off the display / hide the pointer after expiry:** defer. It changes system behaviour, varies by device, and is not needed to stop playback. Existing cinema pointer hiding already covers active viewing.

## Owner decisions

1. **When paused, should the timer keep counting?** Recommended: **yes**; the chosen bedtime remains predictable.
2. **When “end of this episode/movie” is set and the viewer manually changes videos, should it cancel?** Recommended: **yes, with a visible notice**; “this” refers to the selected video.
3. **Should the five-second audio fade ship with the first release?** Recommended: **ship direct stop first**, then enable the fade after both engines pass the muted and audible checks.

This was a read-only planning pass. No files were edited, built, tested, or committed.


## Owner decisions (2026-10-15) — all recommended answers accepted
1. Timer keeps counting while paused.
2. Changing video by hand cancels an "end of this movie/episode" timer, with a visible notice.
3. First release stops directly at zero; the 5-second audio fade comes later (slice 5) once both engines pass.

Status: slices 1-4 built and committed (v1 = direct stop, no fade). Test plan: docs/test-plan-sleep-timer.md. Slice 5 (fade) deferred by owner decision.
