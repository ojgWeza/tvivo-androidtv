namespace Tvivo.Core;

/// <summary>Lets provider lookups wait for the current saved-account sign-in.</summary>
public sealed class ProviderAuthenticationGate
{
    private TaskCompletionSource<bool>? _attempt;

    public TaskCompletionSource<bool> Begin()
    {
        _attempt?.TrySetResult(false);
        return _attempt = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
    }

    public void Cancel()
    {
        _attempt?.TrySetResult(false);
        _attempt = null;
    }

    public Task<bool> WaitAsync(CancellationToken cancellationToken = default) =>
        _attempt?.Task.WaitAsync(cancellationToken) ?? Task.FromResult(true);
}
