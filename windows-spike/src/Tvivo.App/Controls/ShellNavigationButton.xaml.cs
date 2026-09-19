using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace Tvivo.App.Controls;

public sealed partial class ShellNavigationButton : UserControl
{
    public ShellNavigationButton()
    {
        InitializeComponent();
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
            NavigationButton.Foreground = (Brush)Application.Current.Resources[
                value ? "AppTextBrush" : "AppMutedTextBrush"];
            NavigationButton.Background = (Brush)Application.Current.Resources[
                value ? "AppRaisedSurfaceBrush" : "AppCanvasBrush"];
        }
    }

    public event EventHandler? Clicked;

    private void NavigationButton_Click(object sender, RoutedEventArgs args) => Clicked?.Invoke(this, EventArgs.Empty);
}
