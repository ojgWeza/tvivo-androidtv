using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Tvivo.App.Pages;

public sealed partial class HomePage : UserControl
{
    public HomePage()
    {
        InitializeComponent();
    }

    public event EventHandler? OpenPlayerRequested;

    private void OpenPlayer_Click(object sender, RoutedEventArgs args) =>
        OpenPlayerRequested?.Invoke(this, EventArgs.Empty);
}
