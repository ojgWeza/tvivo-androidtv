namespace Tvivo.Playback;

/// <summary>
/// Maps the user-facing volume percentage to LibVLC's mmdevice volume input.
/// VLC 3.0.23 cubes that input before applying it to the Windows audio session.
/// </summary>
public static class VlcVolumeCurve
{
    public static int ToPlayerVolume(int sliderPercent)
    {
        var clampedPercent = Math.Clamp(sliderPercent, 0, 100);
        if (clampedPercent is 0 or 100)
            return clampedPercent;

        return Math.Clamp(
            (int)Math.Round(100d * Math.Cbrt(clampedPercent / 100d)),
            0,
            100);
    }

    public static int ToSliderPercent(int playerVolume)
    {
        var clampedVolume = Math.Clamp(playerVolume, 0, 100);
        if (clampedVolume is 0 or 100)
            return clampedVolume;

        return Math.Clamp(
            (int)Math.Round(100d * Math.Pow(clampedVolume / 100d, 3d)),
            0,
            100);
    }
}
