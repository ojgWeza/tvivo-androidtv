# Plain Material 3 candidate: pass/fail checklist

This is the isolated 1D candidate from `.codex-android-phase01-plan.md` §1.3. The tests are written but have **not been run**. No acceptance gate is marked passed yet.

| §1.3 decision criterion | Evidence to run or inspect | Current status |
|---|---|---|
| One visible focus ring, in `AccentSoft` | `M3CandidateSpikeTest.tvArrowsOkBackAndSingleAccentSoftFrame` checks one focused node and samples the top border of each card. The RTL test repeats the check. | Pending instrumented run and visual TV screenshot. |
| D-pad traversal, OK and Back on TV | `tvArrowsOkBackAndSingleAccentSoftFrame` moves between both cards, activates each with Enter, returns from the poster preview with Back and restores poster focus. | Pending TV AVD run. |
| Phone taps activate each root card with no gesture shim | `touchActivatesBothCardsWithoutGestureShimAndKeepsTouchTargets` sends actual touch input to both cards and checks the callbacks. The candidate contains no pointer gesture handler. | Pending phone or Mi 10 run; Mi 10 Q-14 still needs physical evidence. |
| Touch targets at least 48 dp; TV does not force that minimum | The touch test checks both bounds and observes the Material 3 minimum-target local as enabled. The TV test observes it as disabled. | Pending instrumented run. |
| RTL positions and arrow focus order mirror | `rtlMirrorsPhysicalPositionsAndArrowTraversal` checks card bounds and Left from the logical first card. | Pending RTL TV run. |
| Text metrics and colors stay stable | `textMetricsAndColorRolesMatchTheCurrentTvTheme` compares layout size, baselines, line counts, font size and line height for the same strings and checks the mapped color roles, including TV `border` to Material 3 `outline`. | Pending instrumented run; visual Arabic and 1.3× font-scale checks remain. |
| Screen readers expose labels | The metrics test checks the poster's title, quality and favourite description; the click assertions cover activation semantics. | Pending TalkBack pass; spoken progress needs confirmation. |
| No removed TV library dependency remains | Candidate production is untouched and both themes coexist. | Intentionally open until the later 19-file migration and dependency audit. |
| Existing login, settings, detail and paging focus tests pass | The candidate does not change those screens. Existing characterization tests remain available. | Intentionally open until the later migration and authorized test run. |

Review correction 10 is represented by the dual theme, per-form-factor minimum-target local, disabled local indication plus `tvClickable`, and `border`/`borderVariant` mapping to `outline`/`outlineVariant`. Review correction 14 is represented by debug-only source placement and the `AccentSoft` focus frame. If a runtime check fails, record the smallest failing primitive and observed device behavior before choosing a hybrid.
