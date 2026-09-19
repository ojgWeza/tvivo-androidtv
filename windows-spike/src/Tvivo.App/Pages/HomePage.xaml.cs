using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Tvivo.App.Pages;

public sealed partial class HomePage : UserControl
{
    public HomePage()
    {
        InitializeComponent();
    }

    public event EventHandler? ProviderSetupRequested;

    private void SetupProvider_Click(object sender, RoutedEventArgs args) =>
        ProviderSetupRequested?.Invoke(this, EventArgs.Empty);
}
