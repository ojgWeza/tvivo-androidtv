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
}
