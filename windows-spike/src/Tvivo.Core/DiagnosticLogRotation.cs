namespace Tvivo.Core;

public static class DiagnosticLogRotation
{
    public static void RotateIfNeeded(string path, long incomingBytes, long limitBytes = 1024 * 1024)
    {
        if (!File.Exists(path) || new FileInfo(path).Length + incomingBytes <= limitBytes) return;
        if (File.Exists(path + ".1")) File.Move(path + ".1", path + ".2", overwrite: true);
        File.Move(path, path + ".1", overwrite: true);
    }
}
