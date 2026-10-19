namespace Tvivo.Core;

public static class SearchActivationPolicy
{
    public static bool Allows(bool searchActive, string input, string key, bool sameItem, long elapsedMilliseconds)
    {
        if (!searchActive) return true;
        if (!sameItem || elapsedMilliseconds is < 0 or > 2000) return false;
        return input == "pointer" || (input == "keyboard" && key == "Enter");
    }
}
