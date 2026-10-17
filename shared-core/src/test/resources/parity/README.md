# Playback parity vectors

Each JSON file is an array of cases. Every case has a short `name`, input fields, and `expected` (a scalar or object). Stateful policies use ordered `steps`; each step has its own `expected` decision or selection. Durations and positions are milliseconds. Null means unknown or no selection. The Kotlin runner is `ParityVectorsTest.kt`.

| File | C# source and test |
| --- | --- |
| `episode-completion.json` | `EpisodeCompletion.cs`, `PlaybackEndPolicy.cs`; `EpisodeCompletionTests.cs` |
| `playback-mode.json` | `PlaybackMode.cs`; `PlaybackModeTests.cs` |
| `next-playback-selector.json` | `NextPlaybackSelector.cs`; `NextPlaybackSelectorTests.cs` |
| `next-prompt.json` | `NextPrompt.cs`; `NextPromptTests.cs` |
| `held-seek-accelerator.json` | `HeldSeekAccelerator.cs`; `HeldSeekAcceleratorTests.cs` |
| `track-selection.json` | `TrackSelection.cs`; `TrackSelectionPolicyTests.cs` |
| `stall-recovery-policy.json` | `StallRecoveryPolicy.cs`; `StallRecoveryPolicyTests.cs` |

Shuffle vectors with `randomIndex` specify a zero-based selection from the candidate pool. A future C# parity test should inject a `Random` subclass whose `Next(int maxValue)` returns that index, checking that it is below `maxValue`. Do not compare a raw C# seed with a Kotlin seed: their generators do not promise the same sequence. The `repeatFromSeed` case checks repeatability within each runtime. Playlist and stall cases create a fresh policy instance per case and apply `steps` in order. `begin` starts a stall session; `second` is converted to milliseconds. For `advanceSeconds` through `throughSeconds`, emit one observation per second with position equal to elapsed milliseconds.

To add the C# runner later, include the existing files as linked test content in `windows-spike/tests/Tvivo.Core.Tests/Tvivo.Core.Tests.csproj`:

```xml
<ItemGroup>
  <None Include="../../../shared-core/src/test/resources/parity/*.json"
        Link="parity/%(Filename)%(Extension)"
        CopyToOutputDirectory="PreserveNewest" />
</ItemGroup>
```

Then, in a C# xUnit test, open each file with `Path.Combine(AppContext.BaseDirectory, "parity", fileName)`, parse it with `JsonDocument.Parse(File.ReadAllText(path))`, enumerate `RootElement.EnumerateArray()`, dispatch on `op` or file name, and assert the `expected` field after each call. Use `TryGetProperty` for optional or null fields. The linked files remain the single source of expected values; do not copy them into the Windows tree. The C# runner is deliberately deferred to a later slice.
