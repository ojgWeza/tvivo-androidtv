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
}
