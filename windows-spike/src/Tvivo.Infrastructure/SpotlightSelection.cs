using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace Tvivo.Infrastructure;

public static class SpotlightSelection
{
    public const int MinimumDiscoveryCandidates = 3;

    public static bool ShouldUseDiscovery(int candidateCount) => candidateCount >= MinimumDiscoveryCandidates;

    public static int Index(string accountId, string type, DateOnly utcDate, int candidateCount)
    {
        if (candidateCount <= 0) return -1;
        var identity = SHA256.HashData(Encoding.UTF8.GetBytes($"{accountId}\n{type}"));
        var offset = BinaryPrimitives.ReadUInt32BigEndian(identity);
        return (int)((offset + (uint)utcDate.DayNumber) % (uint)candidateCount);
    }

    public static int RestoreIndex(IReadOnlyList<string> orderedCandidateIds, string? savedCandidateId, int savedIndex)
    {
        if (orderedCandidateIds.Count == 0) return -1;
        if (!string.IsNullOrEmpty(savedCandidateId))
        {
            var restored = -1;
            for (var index = 0; index < orderedCandidateIds.Count; index++)
                if (string.Equals(orderedCandidateIds[index], savedCandidateId, StringComparison.Ordinal))
                {
                    restored = index;
                    break;
                }
            if (restored >= 0) return restored;
        }
        return ((savedIndex % orderedCandidateIds.Count) + orderedCandidateIds.Count) % orderedCandidateIds.Count;
    }

    public static string ShortHash(string value) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value)))[..8].ToLowerInvariant();

    public static string OrderedIdsHash(IEnumerable<string> orderedCandidateIds) =>
        ShortHash(string.Join("\n", orderedCandidateIds));
}
