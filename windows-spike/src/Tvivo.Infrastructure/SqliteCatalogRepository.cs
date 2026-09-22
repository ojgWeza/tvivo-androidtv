using Microsoft.Data.Sqlite;
using System.Text.RegularExpressions;
using Tvivo.Core;

namespace Tvivo.Infrastructure;

public sealed record CatalogPage(IReadOnlyList<Channel> Items, int TotalCount);

public sealed class SqliteCatalogRepository
{
    private const int SchemaVersion = 9;
    private static readonly Regex EmptyBrackets = new(@"[\(\[][\s\-:|]*[\)\]]", RegexOptions.Compiled);
    private static readonly Regex Whitespace = new(@"\s+", RegexOptions.Compiled);
    private readonly string _connectionString;

    public SqliteCatalogRepository(string? databasePath = null)
    {
        databasePath ??= Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Tvivo", "winui-catalog.sqlite");
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(databasePath))!);
        _connectionString = new SqliteConnectionStringBuilder { DataSource = databasePath, Pooling = false }.ToString();
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "PRAGMA user_version";
        var version = Convert.ToInt32(command.ExecuteScalar());
        if (version != 0 && version != 8 && version != SchemaVersion) throw new InvalidOperationException($"Catalog database schema {version} is unsupported; expected schema {SchemaVersion}.");
        if (version == 0)
        {
            command.CommandText = """
                CREATE TABLE categories(account_id TEXT NOT NULL,type TEXT NOT NULL,id TEXT NOT NULL,name TEXT NOT NULL,position INTEGER NOT NULL,PRIMARY KEY(account_id,type,id));
                CREATE TABLE items(account_id TEXT NOT NULL,type TEXT NOT NULL,id TEXT NOT NULL,category_id TEXT NOT NULL,title TEXT NOT NULL,artwork TEXT,extension TEXT,rating TEXT,plot TEXT,added_at INTEGER NOT NULL,favourite INTEGER NOT NULL DEFAULT 0,resume_ms INTEGER NOT NULL DEFAULT 0,resume_updated_at INTEGER,first_indexed_at INTEGER,last_tuned_at INTEGER,PRIMARY KEY(account_id,type,id));
                CREATE INDEX items_browse ON items(account_id,type,category_id);
                CREATE INDEX items_recent_added ON items(account_id,type,added_at DESC);
                PRAGMA user_version=9;
                """;
            command.ExecuteNonQuery();
        }
        else if (version == 8)
        {
            MigrateTitleCleanup(connection);
        }
    }

    private static void MigrateTitleCleanup(SqliteConnection connection)
    {
        using var transaction = connection.BeginTransaction();
        using var select = connection.CreateCommand();
        select.Transaction = transaction;
        select.CommandText = "SELECT account_id,type,id,title FROM items WHERE title LIKE '%(%' OR title LIKE '%[%';";
        var titles = new List<(string AccountId, string Type, string Id, string Title)>();
        using (var reader = select.ExecuteReader())
        {
            while (reader.Read()) titles.Add((reader.GetString(0), reader.GetString(1), reader.GetString(2), CleanTitle(reader.GetString(3))));
        }
        foreach (var row in titles)
        {
            using var update = connection.CreateCommand();
            update.Transaction = transaction;
            update.CommandText = "UPDATE items SET title=$title WHERE account_id=$account AND type=$type AND id=$id";
            update.Parameters.AddWithValue("$title", row.Title);
            update.Parameters.AddWithValue("$account", row.AccountId);
            update.Parameters.AddWithValue("$type", row.Type);
            update.Parameters.AddWithValue("$id", row.Id);
            update.ExecuteNonQuery();
        }
        using (var version = connection.CreateCommand())
        {
            version.Transaction = transaction;
            version.CommandText = "PRAGMA user_version=9";
            version.ExecuteNonQuery();
        }
        transaction.Commit();
    }

    private static string CleanTitle(string? title)
    {
        var cleaned = Whitespace.Replace(EmptyBrackets.Replace(title ?? string.Empty, " "), " ").Trim();
        return string.IsNullOrWhiteSpace(cleaned) ? "Untitled" : cleaned;
    }

    public void ReplaceSnapshot(ProviderAccount account, CatalogItemType type, IReadOnlyList<ChannelGroup> groups, IReadOnlyList<Channel> channels)
    {
        var typeName = TypeName(type);
        using var connection = Open();
        using var transaction = connection.BeginTransaction();
        Execute(connection, transaction, "DELETE FROM categories WHERE account_id=$account AND type=$type; DELETE FROM items WHERE account_id=$account AND type=$type;", ("$account", account.AccountId), ("$type", typeName));
        for (var i = 0; i < groups.Count; i++)
            Execute(connection, transaction, "INSERT INTO categories(account_id,type,id,name,position) VALUES($a,$type,$id,$name,$pos)", ("$a", account.AccountId), ("$type", typeName), ("$id", groups[i].Id), ("$name", groups[i].Name), ("$pos", groups[i].SortOrder ?? i));
        foreach (var channel in channels)
            Execute(connection, transaction, "INSERT INTO items(account_id,type,id,category_id,title,artwork,extension,added_at,first_indexed_at) VALUES($a,$type,$id,$cat,$title,$art,$ext,$added,$now)",
                ("$a", account.AccountId), ("$type", typeName), ("$id", channel.Id), ("$cat", channel.GroupId ?? string.Empty), ("$title", CleanTitle(channel.Name)), ("$art", channel.LogoUri?.ToString()), ("$ext", channel.Source.ContainerExtension ?? DefaultExtension(type)), ("$added", channel.AddedAt?.ToUnixTimeMilliseconds() ?? 0), ("$now", DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()));
        transaction.Commit();
    }

    public IReadOnlyList<ChannelGroup> GetGroups(ProviderAccount account, CatalogItemType type)
    {
        using var connection = Open(); using var command = connection.CreateCommand();
        command.CommandText = "SELECT id,name,position FROM categories WHERE account_id=$a AND type=$type ORDER BY position,id"; command.Parameters.AddWithValue("$a", account.AccountId); command.Parameters.AddWithValue("$type", TypeName(type));
        using var reader = command.ExecuteReader(); var rows = new List<ChannelGroup>();
        while (reader.Read()) rows.Add(new(account.AccountId, reader.GetString(0), reader.GetString(1), reader.GetString(1), reader.GetInt32(2)));
        return rows;
    }

    public CatalogPage GetChannels(ProviderAccount account, CatalogItemType type, string? groupId = null, string? filter = null, int offset = 0, int limit = 100)
    {
        using var connection = Open();
        var where = "account_id=$a AND type=$type AND ($cat IS NULL OR category_id=$cat) AND ($filter IS NULL OR title LIKE $pattern ESCAPE '\\')";
        using var count = connection.CreateCommand(); count.CommandText = $"SELECT COUNT(*) FROM items WHERE {where}";
        AddBrowseParameters(count, account, type, groupId, filter);
        var total = Convert.ToInt32(count.ExecuteScalar());
        using var command = connection.CreateCommand(); command.CommandText = $"SELECT id,category_id,title,artwork,extension,added_at FROM items WHERE {where} ORDER BY title,id LIMIT $limit OFFSET $offset";
        AddBrowseParameters(command, account, type, groupId, filter); command.Parameters.AddWithValue("$limit", Math.Max(1, limit)); command.Parameters.AddWithValue("$offset", Math.Max(0, offset));
        using var reader = command.ExecuteReader(); var rows = new List<Channel>();
        while (reader.Read())
        {
            var id = reader.GetString(0); Uri? artwork = Uri.TryCreate(reader.IsDBNull(3) ? null : reader.GetString(3), UriKind.Absolute, out var uri) ? uri : null;
            DateTimeOffset? added = reader.IsDBNull(5) || reader.GetInt64(5) == 0 ? null : DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(5));
            var title = CleanTitle(reader.GetString(2));
            rows.Add(new(account.AccountId, id, reader.IsDBNull(1) ? null : reader.GetString(1), title, title, artwork, added, null, new StreamSource(id, StreamKindFor(type), reader.IsDBNull(4) ? null : reader.GetString(4)), new Dictionary<string,string>()));
        }
        return new(rows, total);
    }

    private SqliteConnection Open() { var connection = new SqliteConnection(_connectionString); connection.Open(); return connection; }
    private static void AddBrowseParameters(SqliteCommand command, ProviderAccount account, CatalogItemType type, string? groupId, string? filter)
    {
        command.Parameters.AddWithValue("$a", account.AccountId);
        command.Parameters.AddWithValue("$type", TypeName(type));
        command.Parameters.AddWithValue("$cat", (object?)groupId ?? DBNull.Value);
        command.Parameters.AddWithValue("$filter", string.IsNullOrWhiteSpace(filter) ? DBNull.Value : filter);
        command.Parameters.AddWithValue("$pattern", string.IsNullOrWhiteSpace(filter) ? DBNull.Value : "%" + filter.Replace("\\", "\\\\").Replace("%", "\\%").Replace("_", "\\_") + "%");
    }
    private static string TypeName(CatalogItemType type) => type.ToString();
    private static string DefaultExtension(CatalogItemType type) => type == CatalogItemType.Live ? "ts" : "mp4";
    private static StreamKind StreamKindFor(CatalogItemType type) => type switch
    {
        CatalogItemType.Movie => StreamKind.Movie,
        CatalogItemType.Series => StreamKind.Episode,
        _ => StreamKind.Live,
    };
    private static void Execute(SqliteConnection c, SqliteTransaction t, string sql, params (string Name, object? Value)[] values)
    { using var cmd = c.CreateCommand(); cmd.Transaction = t; cmd.CommandText = sql; foreach (var (name,value) in values) cmd.Parameters.AddWithValue(name, value ?? DBNull.Value); cmd.ExecuteNonQuery(); }
}
