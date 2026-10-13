using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media;
using Windows.UI.ViewManagement;

namespace Tvivo.App;

internal static class AppThemeBrushResolver
{
    public static Brush For(FrameworkElement element, string key) => For(element.ActualTheme, key);

    public static Brush For(ElementTheme theme, string key)
    {
        var themeKey = new AccessibilitySettings().HighContrast
            ? "HighContrast"
            : theme == ElementTheme.Light ? "Light" : "Dark";

        if (Application.Current.Resources.ThemeDictionaries.TryGetValue(themeKey, out var dictionaryValue) &&
            dictionaryValue is ResourceDictionary dictionary &&
            dictionary.TryGetValue(key, out var resource) && resource is Brush brush)
            return brush;

        throw new InvalidOperationException($"Theme brush '{key}' is missing from the {themeKey} theme dictionary.");
    }
}
