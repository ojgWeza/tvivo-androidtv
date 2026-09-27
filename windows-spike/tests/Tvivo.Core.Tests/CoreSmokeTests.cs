using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class CoreSmokeTests
{
    [Fact]
    public void CoreTypesCanBeConstructed()
    {
        var endpoint = new ProviderEndpoint("https", "example.invalid", 443);
        var source = new StreamSource("stream-1", StreamKind.Live);

        Assert.Equal("https", endpoint.Scheme);
        Assert.Equal("stream-1", source.StreamId);
    }

    [Fact]
    public void Series_resolution_uses_first_then_last_opened_then_next_after_finish()
    {
        static SeriesEpisode Episode(string id, int season, int number) => new(id, id, season.ToString(), $"Season {season}", season,
            number, new StreamSource(id, StreamKind.Episode));
        var first = Episode("first", 1, 1);
        var second = Episode("second", 1, 2);
        var third = Episode("third", 2, 1);
        var details = new SeriesDetails("show", "Show", new[]
        {
            new SeriesSeason("2", "Season 2", 2, new[] { third }),
            new SeriesSeason("1", "Season 1", 1, new[] { second, first }),
        });

        Assert.Equal(first, SeriesEpisodeResolver.Resolve(details, null, false));
        Assert.Equal(second, SeriesEpisodeResolver.Resolve(details, "second", false));
        Assert.Equal(third, SeriesEpisodeResolver.Resolve(details, "second", true));
        Assert.Equal(third, SeriesEpisodeResolver.Resolve(details, "third", true));
        Assert.Equal(first, SeriesEpisodeResolver.Resolve(details, "missing", false));
    }

    [Fact]
    public void Player_side_titles_name_the_list_contents()
    {
        Assert.Equal("The Expanse", PlayerSideTitleResolver.ForEpisodes(" The Expanse "));
        Assert.Equal("Episodes", PlayerSideTitleResolver.ForEpisodes(null));
        Assert.Equal("Science Fiction", PlayerSideTitleResolver.ForMovie(" Science Fiction "));
        Assert.Equal("More like this", PlayerSideTitleResolver.ForMovie(null));
    }

    [Theory]
    [InlineData("7.234", "7")]
    [InlineData("6.74", "6.5")]
    [InlineData("6.76", "7")]
    [InlineData("6.5", "6.5")]
    [InlineData("6", "6")]
    public void Ratings_round_to_the_nearest_half_star(string raw, string expected) =>
        Assert.Equal(expected, RatingDisplayFormatter.Format(raw));
}
