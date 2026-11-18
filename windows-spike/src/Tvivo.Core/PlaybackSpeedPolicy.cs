using System.Globalization;

namespace Tvivo.Core;

public static class PlaybackSpeedPolicy
{
    public static IReadOnlyList<double> Speeds { get; } = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2];

    public static bool IsSupported(double speed) => Speeds.Contains(speed);

    public static string Label(double speed) => IsSupported(speed)
        ? $"{speed.ToString("0.##", CultureInfo.InvariantCulture)}x"
        : throw new ArgumentOutOfRangeException(nameof(speed));
}
