using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Tvivo.App;

namespace Tvivo.App.Controls;

public sealed partial class ShellNavigationButton : UserControl
{
    public ShellNavigationButton()
    {
        InitializeComponent();
        ActualThemeChanged += (_, _) => ApplyNavigationTheme();
        ApplyNavigationTheme();
    }

    public string Label
    {
        get => LabelText.Text;
        set
        {
            LabelText.Text = value;
            AutomationProperties.SetName(NavigationButton, value);
        }
    }

    public bool IsSelected
    {
        get => SelectionMark.Opacity > 0;
        set
        {
            SelectionMark.Opacity = value ? 1 : 0;
            ApplyNavigationTheme();
        }
    }

    private void ApplyNavigationTheme()
    {
        var selected = IsSelected;
        SelectionMark.Background = AppThemeBrushResolver.For(this, "AppAccentBrush");
        NavigationButton.Foreground = AppThemeBrushResolver.For(this,
            selected ? "AppTextBrush" : "AppMutedTextBrush");
        NavigationButton.Background = AppThemeBrushResolver.For(this,
            selected ? "AppRaisedSurfaceBrush" : "AppCanvasBrush");
    }

    public event EventHandler? Clicked;

    private void NavigationButton_Click(object sender, RoutedEventArgs args) => Clicked?.Invoke(this, EventArgs.Empty);
}
