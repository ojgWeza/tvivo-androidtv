using Tvivo.App;
using Xunit;

namespace Tvivo.App.Tests;

public sealed class SingleInstanceGuardTests
{
    [Fact]
    public void TryAcquire_returns_false_when_named_mutex_is_already_held()
    {
        var mutexName = $@"Local\Tvivo.App.Tests.{Guid.NewGuid():N}";
        Assert.True(SingleInstanceGuard.TryAcquire(mutexName, out var first));

        try
        {
            var secondAcquired = true;
            var secondThread = new Thread(() =>
            {
                var acquired = SingleInstanceGuard.TryAcquire(mutexName, out var second);
                if (acquired)
                {
                    second!.ReleaseMutex();
                    second.Dispose();
                }
                secondAcquired = acquired;
            });
            secondThread.Start();
            secondThread.Join();

            Assert.False(secondAcquired);
        }
        finally
        {
            first!.ReleaseMutex();
            first.Dispose();
        }
    }

    [Fact]
    public void GetMutexName_isolates_test_data_roots()
    {
        const string dataRoot = @"D:\Scratch\Tvivo App.Tests";

        var name = SingleInstanceGuard.GetMutexName(dataRoot);

        Assert.StartsWith(@"Local\Tvivo.WinUI.SingleInstance.", name, StringComparison.Ordinal);
        Assert.NotEqual(name, SingleInstanceGuard.GetMutexName(null));
        Assert.NotEqual(name, SingleInstanceGuard.GetMutexName(dataRoot + "-other"));
    }
}
