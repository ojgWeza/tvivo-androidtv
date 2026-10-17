using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Tvivo.Core;

namespace Tvivo.Infrastructure;

/// <summary>Versioned, account-scoped Spotlight source preferences.</summary>
public sealed class SpotlightSettingsStore
{
    private const int CurrentVersion = 1;
    private readonly string _directory;
    public SpotlightSettingsStore(string? directory = null) => _directory = directory ?? TvivoDataPaths.For("spotlight-settings");

    public SpotlightSettings Read(string opaqueAccountKey)
    {
        var path = PathFor(opaqueAccountKey);
        if (!File.Exists(path)) return SpotlightSettings.Default;
        try
        {
            using var document = JsonDocument.Parse(File.ReadAllText(path));
            var root = document.RootElement;
            if (root.GetProperty("version").GetInt32() != CurrentVersion) return SpotlightSettings.Default;
            return new SpotlightSettings(ReadContent(root.GetProperty("movies")), ReadContent(root.GetProperty("series")));
        }
        catch (Exception exception) when (exception is JsonException or IOException or UnauthorizedAccessException or
                                             KeyNotFoundException or InvalidOperationException or FormatException)
        {
            return SpotlightSettings.Default;
        }
    }

    public void Write(string opaqueAccountKey, SpotlightSettings settings)
    {
        ArgumentNullException.ThrowIfNull(settings);
        var movies = ValidateContent(settings.Movies);
        var series = ValidateContent(settings.Series);
        Directory.CreateDirectory(_directory);
        var path = PathFor(opaqueAccountKey);
        var temporary = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            var json = JsonSerializer.Serialize(new { version = CurrentVersion, movies, series });
            File.WriteAllText(temporary, json);
            File.Move(temporary, path, true);
        }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }

    private static SpotlightContentSettings ReadContent(JsonElement node)
    {
        if (!Enum.TryParse(node.GetProperty("sourceMode").GetString(), ignoreCase: false, out SpotlightSourceMode mode) ||
            !Enum.IsDefined(mode)) throw new JsonException("Unknown Spotlight source mode.");
        var ids = node.GetProperty("selectedCategoryIds").EnumerateArray().Select(item => item.GetString() ?? throw new JsonException())
            .ToArray();
        if (ids.Any(string.IsNullOrWhiteSpace) || ids.Distinct(StringComparer.Ordinal).Count() != ids.Length)
            throw new JsonException("Invalid Spotlight category IDs.");
        return new(mode, ids);
    }

    private static object ValidateContent(SpotlightContentSettings? content)
    {
        content ??= SpotlightSettings.Default.Movies;
        if (!Enum.IsDefined(content.SourceMode)) throw new ArgumentOutOfRangeException(nameof(content));
        var ids = (content.SelectedCategoryIds ?? Array.Empty<string>()).ToArray();
        if (ids.Any(string.IsNullOrWhiteSpace) || ids.Distinct(StringComparer.Ordinal).Count() != ids.Length)
            throw new ArgumentException("Spotlight category IDs must be non-empty and unique.", nameof(content));
        return new { sourceMode = content.SourceMode.ToString(), selectedCategoryIds = ids };
    }

    private string PathFor(string key) => Path.Combine(_directory, Hash(key) + ".json");
    private static string Hash(string value) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value ?? string.Empty)));
}

public sealed record SpotlightDailyItem(CatalogItemType Type, string ItemId);
public sealed record SpotlightDailySelection(string AccountKey, SpotlightPageKind Page, DateOnly UtcDate,
    SpotlightSourceMode SourceMode, string FolderFingerprint, IReadOnlyList<SpotlightDailyItem> Items);

/// <summary>Stores only bounded daily type/id lists under opaque account keys.</summary>
public sealed class SpotlightDailySelectionStore
{
    private const int MaximumStoredSelections = 90;
    private const int MaximumDays = 30;
    private readonly string _directory;
    public SpotlightDailySelectionStore(string? directory = null) => _directory = directory ?? TvivoDataPaths.For("spotlight-daily");

    public SpotlightDailySelection? Read(string accountKey, SpotlightPageKind page, DateOnly utcDate,
        SpotlightSourceMode sourceMode, string folderFingerprint)
    {
        var entries = Load(accountKey);
        return entries.FirstOrDefault(item => item.Page == page && item.UtcDate == utcDate &&
            item.SourceMode == sourceMode && item.FolderFingerprint == folderFingerprint);
    }

    public void Write(SpotlightDailySelection selection)
    {
        ArgumentNullException.ThrowIfNull(selection);
        if (selection.Items.Count > SpotlightSelector.SelectionSize || selection.Items.Any(item =>
                item.Type is not (CatalogItemType.Movie or CatalogItemType.Series) || string.IsNullOrWhiteSpace(item.ItemId)))
            throw new ArgumentException("Daily Spotlight selections must contain at most twelve movie/series IDs.", nameof(selection));
        var entries = Load(selection.AccountKey)
            .Where(item => !(item.Page == selection.Page && item.UtcDate == selection.UtcDate &&
                            item.SourceMode == selection.SourceMode && item.FolderFingerprint == selection.FolderFingerprint))
            .Append(selection).OrderByDescending(item => item.UtcDate).Take(MaximumStoredSelections)
            .Where(item => item.UtcDate >= selection.UtcDate.AddDays(-MaximumDays + 1)).ToArray();
        Directory.CreateDirectory(_directory);
        var path = PathFor(selection.AccountKey);
        var temp = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            File.WriteAllText(temp, JsonSerializer.Serialize(entries));
            File.Move(temp, path, true);
        }
        finally { if (File.Exists(temp)) File.Delete(temp); }
    }

    private IReadOnlyList<SpotlightDailySelection> Load(string key)
    {
        var path = PathFor(key);
        if (!File.Exists(path)) return Array.Empty<SpotlightDailySelection>();
        try
        {
            var entries = JsonSerializer.Deserialize<List<SpotlightDailySelection>>(File.ReadAllText(path)) ?? new();
            return entries.Where(item => item.AccountKey == key && Enum.IsDefined(item.Page) && Enum.IsDefined(item.SourceMode) &&
                item.Items.Count <= SpotlightSelector.SelectionSize && item.Items.All(value =>
                    value.Type is CatalogItemType.Movie or CatalogItemType.Series && !string.IsNullOrWhiteSpace(value.ItemId)))
                .Take(MaximumStoredSelections).ToArray();
        }
        catch (Exception exception) when (exception is JsonException or IOException or UnauthorizedAccessException or
                                             NotSupportedException or ArgumentException)
        { return Array.Empty<SpotlightDailySelection>(); }
    }

    private string PathFor(string key) => Path.Combine(_directory, Hash(key) + ".json");
    private static string Hash(string value) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value ?? string.Empty)));
}
