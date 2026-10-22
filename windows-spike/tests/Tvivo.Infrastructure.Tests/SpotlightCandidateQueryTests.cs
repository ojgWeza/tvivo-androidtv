using Tvivo.Core;
using Tvivo.Infrastructure;
using Xunit;

namespace Tvivo.Infrastructure.Tests;

public sealed class SpotlightCandidateQueryTests
{
    [Fact]
    public void Spotlight_queries_are_bounded_category_scoped_and_reject_live()
    {
        var directory = Path.Combine(Path.GetTempPath(), "tvivo-spotlight-query-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        try
        {
            var account = new ProviderAccount("acct", new("http", "fixture.invalid", 8080), "fixture");
            using var repository = new SqliteCatalogRepository(Path.Combine(directory, "catalog.sqlite"));
            var groups = new[] { new ChannelGroup(account.AccountId, "a", "A", "A"), new ChannelGroup(account.AccountId, "b", "B", "B") };
            var movies = Enumerable.Range(0, 30).Select(index => new Channel(account.AccountId, $"m{index:00}",
                index % 2 == 0 ? "a" : "b", $"Movie {index:00}", $"Movie {index:00}", null,
                DateTimeOffset.UnixEpoch.AddDays(index + 1), null, new($"m{index:00}", StreamKind.Movie, "mkv"),
                new Dictionary<string, string>()) with
                {
                    LogoUri = index is 0 or 4 ? new Uri("https://art.example/poster") : null,
                }).ToArray();
            repository.ReplaceSnapshot(account, CatalogItemType.Movie, groups, movies);
            Assert.Equal(7, repository.GetSpotlightRecentlyAddedCandidates(account, CatalogItemType.Movie, limit: 7).Count);
            Assert.Equal(5, repository.GetSpotlightCategoryCandidates(account, CatalogItemType.Movie, "a", limit: 5).Count);
            Assert.Equal(new[] { "m04", "m00" }, repository.GetSpotlightCategoryCandidates(account,
                CatalogItemType.Movie, "a", limit: 2).Take(2).Select(channel => channel.Id));
            Assert.Empty(repository.GetSpotlightCategoryCandidates(account, CatalogItemType.Movie, "deleted"));
            Assert.Throws<ArgumentOutOfRangeException>(() => repository.GetSpotlightRecentlyAddedCandidates(account, CatalogItemType.Live));
        }
        finally { if (Directory.Exists(directory)) Directory.Delete(directory, true); }
    }
}
