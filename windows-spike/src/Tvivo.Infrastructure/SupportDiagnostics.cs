using System.IO.Compression;
using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using Microsoft.Data.Sqlite;
using Tvivo.Core;

namespace Tvivo.Infrastructure;

public static class SupportDiagnostics
{
    public static void Reveal(string path)
    {
        var start = new ProcessStartInfo("explorer.exe") { UseShellExecute = true };
        start.ArgumentList.Add("/select,");
        start.ArgumentList.Add(path);
        Process.Start(start);
    }

    public static string SaveToDesktop(string appVersion, string? desktop = null, string? dataRoot = null)
    {
        desktop ??= Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
        dataRoot ??= TvivoDataPaths.Root;
        Directory.CreateDirectory(desktop);
        var filename = $"tvivo-diagnostics-{DateTimeOffset.Now:yyyyMMdd-HHmmss}.zip";
        var path = Path.Combine(desktop, filename);
        var logPath = Path.Combine(dataRoot, "tvivo-launch.log");
        var lines = ReadLogTail(logPath, 500);
        var requests = ReadRequestTail(logPath, 100);
        try
        {
            using var file = new FileStream(path, FileMode.CreateNew, FileAccess.Write);
            using var zip = new ZipArchive(file, ZipArchiveMode.Create);
            var safeVersion = new string(appVersion.Where(character => char.IsAsciiDigit(character) || character == '.').Take(32).ToArray());
            Add(zip, "summary.txt", $"appVersion={safeVersion}\n" +
                $"os={Environment.OSVersion.Version}\n{NetworkSummary()}\n{DatabaseSummary(Path.Combine(dataRoot, "winui-catalog.sqlite"))}\n");
            Add(zip, "app-log.txt", string.Join('\n', lines));
            Add(zip, "catalog-requests.txt", string.Join('\n', requests));
        }
        catch
        {
            if (File.Exists(path)) File.Delete(path);
            throw;
        }
        if (new FileInfo(path).Length >= 2_000_000)
        {
            File.Delete(path);
            throw new IOException("The diagnostics export exceeded its size limit.");
        }
        return path;
    }

    public static IReadOnlyList<string> ReadLogTail(string logPath, int count)
    {
        var lines = new Queue<string>();
        foreach (var file in new[] { logPath + ".2", logPath + ".1", logPath })
        {
            if (!File.Exists(file)) continue;
            foreach (var line in File.ReadLines(file))
            {
                var safe = DiagnosticRedactor.RedactLine(line);
                if (safe.Length == 0) continue;
                lines.Enqueue(safe);
                if (lines.Count > count) lines.Dequeue();
            }
        }
        return lines.ToArray();
    }

    public static IReadOnlyList<string> ReadRequestTail(string logPath, int count)
    {
        var lines = new Queue<string>();
        foreach (var file in new[] { logPath + ".2", logPath + ".1", logPath })
        {
            if (!File.Exists(file)) continue;
            foreach (var line in File.ReadLines(file))
            {
                var safe = DiagnosticRedactor.RedactLine(line);
                if (!safe.Contains(" trace=", StringComparison.Ordinal) ||
                    !safe.Contains(" op=", StringComparison.Ordinal)) continue;
                lines.Enqueue(safe);
                if (lines.Count > count) lines.Dequeue();
            }
        }
        return lines.ToArray();
    }

    private static string NetworkSummary()
    {
        try
        {
            var interfaces = NetworkInterface.GetAllNetworkInterfaces()
                .Where(item => item.OperationalStatus == OperationalStatus.Up).ToArray();
            var addresses = interfaces.SelectMany(item => item.GetIPProperties().UnicastAddresses);
            var families = addresses.Select(address => address.Address.AddressFamily).ToHashSet();
            var probe = new Uri("https://example.invalid/");
            var proxy = HttpClient.DefaultProxy.GetProxy(probe) != probe;
            return $"online={NetworkInterface.GetIsNetworkAvailable()} proxy={proxy} ipv4={families.Contains(AddressFamily.InterNetwork)} ipv6={families.Contains(AddressFamily.InterNetworkV6)}";
        }
        catch { return "online=unknown proxy=unknown ipv4=unknown ipv6=unknown"; }
    }

    private static string DatabaseSummary(string databasePath)
    {
        if (!File.Exists(databasePath)) return "database=absent";
        try
        {
            var builder = new SqliteConnectionStringBuilder { DataSource = databasePath, Mode = SqliteOpenMode.ReadOnly };
            using var connection = new SqliteConnection(builder.ToString());
            connection.Open();
            using var command = connection.CreateCommand();
            command.CommandText = "PRAGMA user_version";
            var version = Convert.ToInt32(command.ExecuteScalar());
            var counts = new List<string>();
            foreach (var type in new[] { "Live", "Movie", "Series" })
            {
                command.CommandText = "SELECT COUNT(*),COALESCE(SUM(CASE WHEN size_bytes>0 THEN 1 ELSE 0 END),0) FROM items WHERE type=$type";
                command.Parameters.Clear();
                command.Parameters.AddWithValue("$type", type);
                using var reader = command.ExecuteReader();
                reader.Read();
                counts.Add($"{type.ToLowerInvariant()}Rows={reader.GetInt64(0)} {type.ToLowerInvariant()}SizeRows={reader.GetInt64(1)}");
            }
            return $"dbUserVersion={version} {string.Join(' ', counts)}";
        }
        catch (SqliteException) { return "database=unavailable"; }
    }

    private static void Add(ZipArchive zip, string name, string content)
    {
        var entry = zip.CreateEntry(name, CompressionLevel.Optimal);
        using var writer = new StreamWriter(entry.Open(), new UTF8Encoding(false));
        writer.Write(content);
    }
}
