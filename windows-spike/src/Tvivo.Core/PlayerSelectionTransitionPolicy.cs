namespace Tvivo.Core;

public static class PlayerSelectionTransitionPolicy
{
    public static bool CanApplyResolution(long requestedGeneration, long currentGeneration, bool cancellationRequested) =>
        !cancellationRequested && requestedGeneration == currentGeneration;

    public static bool ShouldShowResolutionError(long requestedGeneration, long currentGeneration,
        bool cancellationRequested, bool resolutionFailed) =>
        resolutionFailed && CanApplyResolution(requestedGeneration, currentGeneration, cancellationRequested);
}
