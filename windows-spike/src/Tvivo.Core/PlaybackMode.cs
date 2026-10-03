using System.Text.Json;
using System.Text.Json.Serialization;

namespace Tvivo.Core;

public enum PlaybackMode
{
    Off,
    Next,
    Shuffle,
}

public static class PlaybackModeLogic
{
    public static bool ShouldAutoAdvance(PlaybackMode mode) => mode is PlaybackMode.Next or PlaybackMode.Shuffle;

    public static bool ShouldShowNextPrompt(PlaybackMode mode) => ShouldAutoAdvance(mode);

    public static bool ShouldShuffle(PlaybackMode mode) => mode == PlaybackMode.Shuffle;

    public static PlaybackMode FromLegacyAutoplay(bool? autoplayNext, bool isMovie) => autoplayNext switch
    {
        true => PlaybackMode.Next,
        false => isMovie ? PlaybackMode.Shuffle : PlaybackMode.Off,
        null => isMovie ? PlaybackMode.Shuffle : PlaybackMode.Next,
    };
}

public sealed record PlaybackModePreferences(PlaybackMode EpisodeMode, PlaybackMode MovieMode)
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
            ReadMode(root, "MovieMode") ?? PlaybackModeLogic.FromLegacyAutoplay(ReadBoolean(root, "MovieAutoplayNext"), isMovie: true));
    }

    public string ToJson() => JsonSerializer.Serialize(this, JsonOptions);

    private static PlaybackMode? ReadMode(JsonElement root, string name)
    {
        if (!TryGetProperty(root, name, out var value) || value.ValueKind != JsonValueKind.String)
            return null;
        return Enum.TryParse<PlaybackMode>(value.GetString(), ignoreCase: true, out var mode) && Enum.IsDefined(mode)
            ? mode
            : null;
    }

    private static bool? ReadBoolean(JsonElement root, string name) =>
        TryGetProperty(root, name, out var value) && value.ValueKind is JsonValueKind.True or JsonValueKind.False
            ? value.GetBoolean()
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
