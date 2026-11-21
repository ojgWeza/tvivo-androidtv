using System.Text.Json;
using System.Text.Json.Serialization;

namespace Tvivo.Core;

public enum PlaybackMode
{
    Off,
    Next,
    Shuffle,
    Loop,
}

public static class PlaybackModeTransitions
{
    public static PlaybackMode Next(PlaybackMode current, PlaybackMode clicked)
    {
        if (clicked is not (PlaybackMode.Next or PlaybackMode.Shuffle or PlaybackMode.Loop))
            return current is PlaybackMode.Next or PlaybackMode.Shuffle or PlaybackMode.Loop ? current : PlaybackMode.Off;

        return current == clicked ? PlaybackMode.Off : clicked;
    }
}

public enum MinimizeToMiniMode
{
    MiniPlayerWhilePlaying,
    MiniPlayerWhilePlayingOrPaused,
    AlwaysMinimizeNormally,
}

public static class PlaybackModeLogic
{
    public static bool ShouldAutoAdvance(PlaybackMode mode) => mode is PlaybackMode.Next or PlaybackMode.Shuffle;

    // Autoplay-next advances silently; the next-item offer is never forced on the viewer.
    // Next/previous stay available from the playlist and the transport buttons.
    public static bool ShouldShowNextPrompt(PlaybackMode mode) => false;

    public static bool ShouldLoopItem(PlaybackMode mode) => mode == PlaybackMode.Loop;

    public static bool ShouldShuffle(PlaybackMode mode) => mode == PlaybackMode.Shuffle;

    public static PlaybackMode FromLegacyAutoplay(bool? autoplayNext, bool isMovie) => autoplayNext switch
    {
        true => PlaybackMode.Next,
        false => isMovie ? PlaybackMode.Shuffle : PlaybackMode.Off,
        null => isMovie ? PlaybackMode.Shuffle : PlaybackMode.Next,
    };
}

public sealed record PlaybackModePreferences(
    PlaybackMode EpisodeMode,
    PlaybackMode MovieMode,
    MinimizeToMiniMode MinimizeToMiniMode = MinimizeToMiniMode.MiniPlayerWhilePlaying,
    int PlaybackAttempts = PlaybackRetryPolicy.DefaultAttempts,
    CinemaEscapeSetting CinemaEscape = CinemaEscapeSetting.NormalPlayer,
    SleepTimerItemChangeSetting SleepTimerOnItemChange = SleepTimerItemChangeSetting.Clear)
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        Converters = { new JsonStringEnumConverter() },
    };

    public static PlaybackModePreferences Defaults { get; } = new(PlaybackMode.Next, PlaybackMode.Shuffle);

    public static PlaybackModePreferences FromJson(string json)
    {
        using var document = JsonDocument.Parse(json);
        var root = document.RootElement;
        if (root.ValueKind != JsonValueKind.Object)
            return Defaults;

        return new PlaybackModePreferences(
            ReadMode(root, "EpisodeMode") ?? PlaybackModeLogic.FromLegacyAutoplay(ReadBoolean(root, "EpisodeAutoplayNext"), isMovie: false),
            ReadMode(root, "MovieMode") ?? PlaybackModeLogic.FromLegacyAutoplay(ReadBoolean(root, "MovieAutoplayNext"), isMovie: true),
            ReadMinimizeMode(root),
            PlaybackRetryPolicy.ClampAttempts(ReadInteger(root, nameof(PlaybackAttempts)) ?? PlaybackRetryPolicy.DefaultAttempts),
            ReadEnum(root, nameof(CinemaEscape), CinemaEscapeSetting.NormalPlayer),
            ReadEnum(root, nameof(SleepTimerOnItemChange), SleepTimerItemChangeSetting.Clear));
    }

    public string ToJson() => JsonSerializer.Serialize(this with
    {
        PlaybackAttempts = PlaybackRetryPolicy.ClampAttempts(PlaybackAttempts),
    }, JsonOptions);

    private static PlaybackMode? ReadMode(JsonElement root, string name)
    {
        if (!TryGetProperty(root, name, out var value) || value.ValueKind != JsonValueKind.String)
            return null;
        return Enum.TryParse<PlaybackMode>(value.GetString(), ignoreCase: true, out var mode) && Enum.IsDefined(mode)
            ? mode
            : null;
    }

    private static MinimizeToMiniMode ReadMinimizeMode(JsonElement root)
    {
        if (TryGetProperty(root, nameof(MinimizeToMiniMode), out var modeValue) &&
            modeValue.ValueKind == JsonValueKind.String &&
            Enum.TryParse<MinimizeToMiniMode>(modeValue.GetString(), ignoreCase: true, out var mode) &&
            Enum.IsDefined(mode))
            return mode;

        // Older playback-options.json files stored this choice as a boolean.
        return ReadBoolean(root, "MinimizeToMiniPlayerWhilePlaying") == false
            ? MinimizeToMiniMode.AlwaysMinimizeNormally
            : MinimizeToMiniMode.MiniPlayerWhilePlaying;
    }

    private static T ReadEnum<T>(JsonElement root, string name, T fallback) where T : struct, Enum =>
        TryGetProperty(root, name, out var value) && value.ValueKind == JsonValueKind.String &&
        Enum.TryParse<T>(value.GetString(), true, out var parsed) && Enum.IsDefined(parsed) ? parsed : fallback;

    private static bool? ReadBoolean(JsonElement root, string name) =>
        TryGetProperty(root, name, out var value) && value.ValueKind is JsonValueKind.True or JsonValueKind.False
            ? value.GetBoolean()
            : null;

    private static int? ReadInteger(JsonElement root, string name) =>
        TryGetProperty(root, name, out var value) && value.ValueKind == JsonValueKind.Number && value.TryGetInt32(out var result)
            ? result
            : null;

    private static bool TryGetProperty(JsonElement root, string name, out JsonElement value)
    {
        foreach (var property in root.EnumerateObject())
        {
            if (string.Equals(property.Name, name, StringComparison.OrdinalIgnoreCase))
            {
                value = property.Value;
                return true;
            }
        }
        value = default;
        return false;
    }
}
