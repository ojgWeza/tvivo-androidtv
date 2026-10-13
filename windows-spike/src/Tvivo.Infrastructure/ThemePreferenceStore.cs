using System.Text.Json;
using Tvivo.Core;

namespace Tvivo.Infrastructure;

public enum ThemePreference
{
    Dark,
    Light,
}

/// <summary>Persists the app-wide light or dark appearance preference.</summary>
public sealed class ThemePreferenceStore
{
    private readonly string _path;

    public ThemePreferenceStore(string? path = null) =>
        _path = path ?? TvivoDataPaths.For("theme-preference.json");

    public ThemePreference Read()
    {
        if (!File.Exists(_path)) return ThemePreference.Dark;
        try
        {
            using var document = JsonDocument.Parse(File.ReadAllText(_path));
            if (document.RootElement.ValueKind != JsonValueKind.Object ||
                !document.RootElement.TryGetProperty("theme", out var value) ||
                value.ValueKind != JsonValueKind.String)
                return ThemePreference.Dark;

            return value.GetString() switch
            {
                "Dark" => ThemePreference.Dark,
                "Light" => ThemePreference.Light,
                _ => ThemePreference.Dark,
            };
        }
        catch (Exception exception) when (exception is JsonException or IOException or UnauthorizedAccessException or InvalidOperationException)
        {
            return ThemePreference.Dark;
        }
    }

    public void Write(ThemePreference theme)
    {
        if (theme is not (ThemePreference.Dark or ThemePreference.Light))
            throw new ArgumentOutOfRangeException(nameof(theme));

        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(_path))!);
        var temporary = _path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            File.WriteAllText(temporary, JsonSerializer.Serialize(new
            {
                theme = theme.ToString(),
            }));
            File.Move(temporary, _path, true);
        }
        finally
        {
            if (File.Exists(temporary)) File.Delete(temporary);
        }
    }
}
