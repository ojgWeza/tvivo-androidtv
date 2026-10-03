namespace Tvivo.Core;

/// <summary>Selects the next item in a visible playback playlist, optionally tracking a shuffle round.</summary>
public sealed class NextPlaybackSelector(Random? random = null)
{
    private readonly Random _random = random ?? Random.Shared;
    private readonly HashSet<string> _playedThisRound = new(StringComparer.Ordinal);
    private string[] _playlist = Array.Empty<string>();

    public string? SelectNext(
        IReadOnlyList<string> playlist,
        string currentId,
        IReadOnlySet<string>? finishedIds = null,
        bool shuffle = false)
    {
        var ids = playlist.Distinct(StringComparer.Ordinal).ToArray();
        if (!_playlist.SequenceEqual(ids, StringComparer.Ordinal))
        {
            _playlist = ids;
            _playedThisRound.Clear();
        }

        var currentIndex = Array.FindIndex(ids, id => string.Equals(id, currentId, StringComparison.Ordinal));
        if (ids.Length < 2 || (!shuffle && currentIndex < 0))
            return null;

        if (!shuffle)
            return currentIndex + 1 < ids.Length ? ids[currentIndex + 1] : null;

        if (currentIndex >= 0)
            _playedThisRound.Add(currentId);
        var candidates = ids
            .Where(id => !string.Equals(id, currentId, StringComparison.Ordinal) && !_playedThisRound.Contains(id))
            .ToArray();
        if (candidates.Length == 0)
        {
            _playedThisRound.Clear();
            if (currentIndex >= 0)
                _playedThisRound.Add(currentId);
            candidates = ids.Where(id => !string.Equals(id, currentId, StringComparison.Ordinal)).ToArray();
        }

        var unfinished = candidates.Where(id => finishedIds?.Contains(id) != true).ToArray();
        var pool = unfinished.Length > 0 ? unfinished : candidates;
        var selected = pool[_random.Next(pool.Length)];
        return selected;
    }

    public void Reset()
    {
        _playlist = Array.Empty<string>();
        _playedThisRound.Clear();
    }
}
