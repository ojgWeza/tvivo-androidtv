namespace Tvivo.Core;

/// <summary>Resolves the root directory for Tvivo's local settings, databases, and diagnostics.</summary>
public static class TvivoDataPaths
{
    public const string OverrideEnvironmentVariable = "TVIVO_DATA_ROOT";

    public static string Root => ResolveRoot(
        Environment.GetEnvironmentVariable(OverrideEnvironmentVariable),
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData));

    public static string For(params string[] pathParts) => Path.Combine([Root, .. pathParts]);

    public static string ResolveRoot(string? overrideRoot, string localApplicationDataPath) =>
        string.IsNullOrWhiteSpace(overrideRoot)
            ? Path.Combine(localApplicationDataPath, "Tvivo")
            : Path.GetFullPath(overrideRoot);
}
