using Microsoft.Data.Sqlite;
using Tvivo.Core;

namespace Tvivo.Infrastructure;

public sealed record CatalogPage(IReadOnlyList<Channel> Items, int TotalCount);

public sealed class SqliteCatalogRepository
{
    private const int SchemaVersion = 8;
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
        if (version != 0 && version != SchemaVersion) throw new InvalidOperationException($"Catalog database schema {version} is unsupported; expected schema {SchemaVersion}.");
        if (version == 0)
        {
            command.CommandText = """
                CREATE TABLE categories(account_id TEXT NOT NULL,type TEXT NOT NULL,id TEXT NOT NULL,name TEXT NOT NULL,position INTEGER NOT NULL,PRIMARY KEY(account_id,type,id));
                CREATE TABLE items(account_id TEXT NOT NULL,type TEXT NOT NULL,id TEXT NOT NULL,category_id TEXT NOT NULL,title TEXT NOT NULL,artwork TEXT,extension TEXT,rating TEXT,plot TEXT,added_at INTEGER NOT NULL,favourite INTEGER NOT NULL DEFAULT 0,resume_ms INTEGER NOT NULL DEFAULT 0,resume_updated_at INTEGER,first_indexed_at INTEGER,last_tuned_at INTEGER,PRIMARY KEY(account_id,type,id));
                CREATE INDEX items_browse ON items(account_id,type,category_id);
                CREATE INDEX items_recent_added ON items(account_id,type,added_at DESC);
                PRAGMA user_version=8;
                """;
            command.ExecuteNonQuery();
        }
    }

    public void ReplaceLiveSnapshot(ProviderAccount account, IReadOnlyList<ChannelGroup> groups, IReadOnlyList<Channel> channels)
    {
        using var connection = Open();
        using var transaction = connection.BeginTransaction();
        Execute(connection, transaction, "DELETE FROM categories WHERE account_id=$account AND type='Live'; DELETE FROM items WHERE account_id=$account AND type='Live';", ("$account", account.AccountId));
        for (var i = 0; i < groups.Count; i++)
            Execute(connection, transaction, "INSERT INTO categories(account_id,type,id,name,position) VALUES($a,'Live',$id,$name,$pos)", ("$a", account.AccountId), ("$id", groups[i].Id), ("$name", groups[i].Name), ("$pos", groups[i].SortOrder ?? i));
        foreach (var channel in channels)
            Execute(connection, transaction, "INSERT INTO items(account_id,type,id,category_id,title,artwork,extension,added_at,first_indexed_at) VALUES($a,'Live',$id,$cat,$title,$art,$ext,$added,$now)",
                ("$a", account.AccountId), ("$id", channel.Id), ("$cat", channel.GroupId ?? string.Empty), ("$title", channel.Name), ("$art", channel.LogoUri?.ToString()), ("$ext", channel.Source.ContainerExtension ?? "ts"), ("$added", channel.AddedAt?.ToUnixTimeMilliseconds() ?? 0), ("$now", DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()));
        transaction.Commit();
    }

    public IReadOnlyList<ChannelGroup> GetGroups(ProviderAccount account)
    {
        using var connection = Open(); using var command = connection.CreateCommand();
        command.CommandText = "SELECT id,name,position FROM categories WHERE account_id=$a AND type='Live' ORDER BY position,id"; command.Parameters.AddWithValue("$a", account.AccountId);
        using var reader = command.ExecuteReader(); var rows = new List<ChannelGroup>();
        while (reader.Read()) rows.Add(new(account.AccountId, reader.GetString(0), reader.GetString(1), reader.GetString(1), reader.GetInt32(2)));
        return rows;
    }

    public CatalogPage GetChannels(ProviderAccount account, string? groupId = null, string? filter = null, int offset = 0, int limit = 100)
    {
        using var connection = Open();
        var where = "account_id=$a AND type='Live' AND ($cat IS NULL OR category_id=$cat) AND ($filter IS NULL OR title LIKE $pattern ESCAPE '\\')";
        using var count = connection.CreateCommand(); count.CommandText = $"SELECT COUNT(*) FROM items WHERE {where}";
        count.Parameters.AddWithValue("$a", account.AccountId); count.Parameters.AddWithValue("$cat", (object?)groupId ?? DBNull.Value); count.Parameters.AddWithValue("$filter", (object?)filter ?? DBNull.Value); count.Parameters.AddWithValue("$pattern", filter is null ? DBNull.Value : "%" + filter.Replace("\\", "\\\\").Replace("%", "\\%").Replace("_", "\\_") + "%");
        var total = Convert.ToInt32(count.ExecuteScalar());
        using var command = connection.CreateCommand(); command.CommandText = $"SELECT id,category_id,title,artwork,extension,added_at FROM items WHERE {where} ORDER BY title,id LIMIT $limit OFFSET $offset";
        command.Parameters.AddWithValue("$a", account.AccountId); command.Parameters.AddWithValue("$cat", (object?)groupId ?? DBNull.Value); command.Parameters.AddWithValue("$filter", (object?)filter ?? DBNull.Value); command.Parameters.AddWithValue("$pattern", filter is null ? DBNull.Value : "%" + filter.Replace("\\", "\\\\").Replace("%", "\\%").Replace("_", "\\_") + "%"); command.Parameters.AddWithValue("$limit", Math.Max(1, limit)); command.Parameters.AddWithValue("$offset", Math.Max(0, offset));
        using var reader = command.ExecuteReader(); var rows = new List<Channel>();
        while (reader.Read())
        {
            var id = reader.GetString(0); Uri? artwork = Uri.TryCreate(reader.IsDBNull(3) ? null : reader.GetString(3), UriKind.Absolute, out var uri) ? uri : null;
            DateTimeOffset? added = reader.IsDBNull(5) || reader.GetInt64(5) == 0 ? null : DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(5));
            rows.Add(new(account.AccountId, id, reader.IsDBNull(1) ? null : reader.GetString(1), reader.GetString(2), reader.GetString(2), artwork, added, null, new StreamSource(id, StreamKind.Live, reader.IsDBNull(4) ? null : reader.GetString(4)), new Dictionary<string,string>()));
        }
        return new(rows, total);
    }

    private SqliteConnection Open() { var connection = new SqliteConnection(_connectionString); connection.Open(); return connection; }
    private static void Execute(SqliteConnection c, SqliteTransaction t, string sql, params (string Name, object? Value)[] values)
    { using var cmd = c.CreateCommand(); cmd.Transaction = t; cmd.CommandText = sql; foreach (var (name,value) in values) cmd.Parameters.AddWithValue(name, value ?? DBNull.Value); cmd.ExecuteNonQuery(); }
}
