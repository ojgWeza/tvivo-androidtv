using System.Net;
using Tvivo.Core;
using Xunit;

namespace Tvivo.Core.Tests;

public sealed class SecretScrubberTests
{
    [Theory]
    [InlineData("/series/alice/p%40ss/42.mkv")]
    [InlineData("/live/alice/p%40ss/1.ts")]
    [InlineData("MRL=\"http://PRIVATE.example:8080/movie/alice/p%40ss/2.mp4\"")]
    [InlineData("Location: http://private.example:8080/series/alice/p%40ss/2.mkv")]
    [InlineData("?username=alice&password=p%40ss")]
    [InlineData("Cookie: login=alice; key=p%40ss")]
    [InlineData("InvalidOperationException: private.example alice p@ss")]
    public void Scrub_removes_active_values_in_diagnostic_contexts(string input)
    {
        var scrubber = new SecretScrubber();
        scrubber.SetActiveSecrets("private.example", "alice", "p@ss");

        var output = scrubber.Scrub(input);

        Assert.DoesNotContain("private.example", output, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("alice", output, StringComparison.Ordinal);
        Assert.DoesNotContain("p@ss", output, StringComparison.Ordinal);
        Assert.DoesNotContain("p%40ss", output, StringComparison.Ordinal);
    }

    [Fact]
    public void Scrub_removes_uri_and_form_encoded_passwords_longest_first()
    {
        const string password = "ab@:/% cd";
        var scrubber = new SecretScrubber();
        scrubber.SetActiveSecrets("host.example", "alex", password);
        var uri = Uri.EscapeDataString(password);
        var form = WebUtility.UrlEncode(password);

        var output = scrubber.Scrub($"raw={password} uri={uri} form={form}");

        Assert.DoesNotContain(password, output, StringComparison.Ordinal);
        Assert.DoesNotContain(uri, output, StringComparison.Ordinal);
        Assert.DoesNotContain(form, output, StringComparison.Ordinal);
        Assert.Equal(3, output.Split("[redacted-secret]", StringSplitOptions.None).Length - 1);
    }

    [Fact]
    public void Short_secrets_only_match_complete_path_segments()
    {
        var scrubber = new SecretScrubber();
        scrubber.SetActiveSecrets("", "u", "p");

        var output = scrubber.Scrub("/series/u/p/2.mkv user=public password=p /series/user/pass/3.mkv");

        Assert.Contains("/series/[redacted-secret]/[redacted-secret]/2.mkv", output, StringComparison.Ordinal);
        Assert.Contains("user=public password=p /series/user/pass/3.mkv", output, StringComparison.Ordinal);
    }

    [Fact]
    public void Clear_removes_previous_registration()
    {
        var scrubber = new SecretScrubber();
        scrubber.SetActiveSecrets("private.example", "alice", "p@ss");
        scrubber.Clear();

        Assert.Equal("private.example alice p@ss", scrubber.Scrub("private.example alice p@ss"));
    }
}
