using Xunit;
using Tvivo.Core;

namespace Tvivo.Core.Tests;

public sealed class GenreTextFormatterTests
{
    [Fact]
    public void Short_genre_is_unchanged() => Assert.Equal("Drama, Crime", GenreTextFormatter.Truncate("Drama, Crime"));

    [Fact]
    public void Long_genre_list_is_cut_at_a_whole_genre()
    {
        var result = GenreTextFormatter.Truncate("Action, Adventure, Science Fiction, Thriller, Mystery");
        Assert.Equal("Action, Adventure…", result);
        Assert.True(result!.Length <= GenreTextFormatter.DefaultMaxLength + 1);
    }

    [Fact]
    public void Single_very_long_word_is_cut_at_the_limit() =>
        Assert.Equal(GenreTextFormatter.DefaultMaxLength + 1, GenreTextFormatter.Truncate(new string('x', 80))!.Length);

    [Fact]
    public void Blank_is_passed_through() => Assert.Null(GenreTextFormatter.Truncate(null));

    [Fact]
    public void Arabic_genre_and_english_size_are_isolated_from_each_other()
    {
        Assert.Equal("⁨دراما⁩", GenreTextFormatter.IsolateAuto("دراما"));
        Assert.Equal("⁦File size: 1.4 GB⁩", GenreTextFormatter.IsolateLtr("File size: 1.4 GB"));
        Assert.Null(GenreTextFormatter.IsolateLtr(null));
    }

    [Fact]
    public void Lost_backslash_unicode_runs_are_decoded()
    {
        Assert.Equal("دراما", GenreTextFormatter.Truncate("u062fu0631u0627u0645u0627"));
        Assert.Equal("دراما, Action", GenreTextFormatter.Truncate("\u062f\u0631\u0627\u0645\u0627, Action"));
        Assert.Equal("Menu1234 Lou0041", GenreTextFormatter.Truncate("Menu1234 Lou0041"));
    }

    [Fact]
    public void Slash_separated_genres_become_a_comma_list() =>
        Assert.Equal("Drama, Crime, Romance", GenreTextFormatter.Truncate("Drama/Crime / Romance"));
}
