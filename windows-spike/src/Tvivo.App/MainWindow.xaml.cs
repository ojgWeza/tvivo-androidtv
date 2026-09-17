using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.Extensions.DependencyInjection;
using Tvivo.Core;
using Tvivo.Playback;

namespace Tvivo.App;

public sealed partial class MainWindow : Window
{
    private readonly PlaybackService _playback;

    public MainWindow()
    {
        LaunchDiagnostics.Write("MainWindow constructor entered");
        _playback = App.Services.GetRequiredService<PlaybackService>();
        InitializeComponent();
        PathBox.Text = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8";
        LaunchDiagnostics.Write("MainWindow XAML initialized");
        VideoHost.Content = App.Services.GetRequiredService<WindowsPlaybackEngine>().Element;
        Closed += OnClosed;
        LaunchDiagnostics.Write("MainWindow constructor completed");
    }

    private async void Play_Click(object sender, RoutedEventArgs args)
    {
        if (!Uri.TryCreate(PathBox.Text.Trim(), UriKind.Absolute, out var uri))
        {
            StatusText.Text = "Enter a valid media URI.";
            return;
        }

        StatusText.Text = "Starting…";
        var result = await _playback.PlayAsync(new StreamSource("desktop", StreamKind.Movie, DirectUri: uri));
        StatusText.Text = result.ToString();
    }

    private async void Stop_Click(object sender, RoutedEventArgs args)
    {
        await _playback.StopAsync();
        StatusText.Text = "Stopped";
    }

    private async void OnClosed(object sender, WindowEventArgs args)
    {
        LaunchDiagnostics.Write("MainWindow closed");
        await Task.CompletedTask;
    }
}
