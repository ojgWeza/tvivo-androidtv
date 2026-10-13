using System.Text.Json;
using Tvivo.Core;

namespace Tvivo.Infrastructure;

public sealed record MyTvivoShelfPreference(string Id, bool Visible);

/// <summary>Persists which For You shelves are shown and in what order.</summary>
public sealed class MyTvivoLayoutStore
{
    private readonly string _path;

    public MyTvivoLayoutStore(string? path = null) => _path = path ?? TvivoDataPaths.For("my-tvivo-layout.json");

    public IReadOnlyList<MyTvivoShelfPreference> Read()
    {
        if (!File.Exists(_path)) return Array.Empty<MyTvivoShelfPreference>();
        try
        {
            using var document = JsonDocument.Parse(File.ReadAllText(_path));
            return document.RootElement.GetProperty("shelves").EnumerateArray()
                .Select(item => new MyTvivoShelfPreference(item.GetProperty("id").GetString() ?? string.Empty, item.GetProperty("visible").GetBoolean()))
                .Where(item => item.Id.Length > 0)
                .ToArray();
        }
        catch (Exception exception) when (exception is JsonException or KeyNotFoundException or InvalidOperationException)
        {
            return Array.Empty<MyTvivoShelfPreference>();
        }
    }

    public void Write(IEnumerable<MyTvivoShelfPreference> preferences)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(_path))!);
        var temporary = _path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            File.WriteAllText(temporary, JsonSerializer.Serialize(new
            {
                shelves = preferences.Select(item => new { id = item.Id, visible = item.Visible }),
            }));
            File.Move(temporary, _path, true);
        }
        finally
        {
            if (File.Exists(temporary)) File.Delete(temporary);
        }
    }

    public void Reset()
    {
        if (File.Exists(_path)) File.Delete(_path);
    }
}
