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
}
