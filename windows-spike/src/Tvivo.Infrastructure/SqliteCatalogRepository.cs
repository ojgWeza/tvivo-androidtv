using Microsoft.Data.Sqlite;
using System.Text.RegularExpressions;
using Tvivo.Core;

namespace Tvivo.Infrastructure;

public sealed record CatalogPage(IReadOnlyList<Channel> Items, int TotalCount);

public sealed class SqliteCatalogRepository : IDisposable
{
    private const int SchemaVersion = 15;
    private static readonly Regex EmptyBrackets = new(@"[\(\[][\s\-:|]*[\)\]]", RegexOptions.Compiled);
    private static readonly Regex Whitespace = new(@"\s+", RegexOptions.Compiled);
    private readonly string _connectionString;

    public SqliteCatalogRepository(string? databasePath = null)
    {
        databasePath ??= Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Tvivo", "winui-catalog.sqlite");
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(databasePath))!);
        _connectionString = new SqliteConnectionStringBuilder { DataSource = databasePath }.ToString();
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "PRAGMA user_version";
        var version = Convert.ToInt32(command.ExecuteScalar());
        if (version != 0 && version != 8 && version != 9 && version != 10 && version != 11 && version != 12 && version != 13 && version != 14 && version != SchemaVersion) throw new InvalidOperationException($"Catalog database schema {version} is unsupported; expected schema {SchemaVersion}.");
        if (version == 0)
        {
            command.CommandText = """
                CREATE TABLE categories(account_id TEXT NOT NULL,type TEXT NOT NULL,id TEXT NOT NULL,name TEXT NOT NULL,position INTEGER NOT NULL,PRIMARY KEY(account_id,type,id));
                CREATE TABLE items(account_id TEXT NOT NULL,type TEXT NOT NULL,id TEXT NOT NULL,category_id TEXT NOT NULL,title TEXT NOT NULL,title_sort TEXT NOT NULL,artwork TEXT,extension TEXT,rating TEXT,plot TEXT,year TEXT,genre TEXT,cast TEXT,metadata_fetched INTEGER NOT NULL DEFAULT 0,added_at INTEGER NOT NULL,favourite INTEGER NOT NULL DEFAULT 0,favourite_added_at INTEGER,resume_ms INTEGER NOT NULL DEFAULT 0,resume_updated_at INTEGER,first_indexed_at INTEGER,last_tuned_at INTEGER,visit_count INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(account_id,type,id));
                CREATE INDEX items_browse ON items(account_id,type,category_id);
                CREATE INDEX items_recent_added ON items(account_id,type,added_at DESC);
                CREATE INDEX items_favourites_added ON items(account_id,type,favourite,favourite_added_at DESC);
                CREATE INDEX items_most_visited ON items(account_id,type,visit_count DESC,last_tuned_at DESC,title_sort COLLATE NOCASE,title COLLATE NOCASE,id);
                CREATE TABLE series_playback(account_id TEXT NOT NULL,series_id TEXT NOT NULL,last_episode_id TEXT NOT NULL,finished INTEGER NOT NULL DEFAULT 0,updated_at INTEGER NOT NULL,PRIMARY KEY(account_id,series_id));
                CREATE TABLE favorites(account_id TEXT NOT NULL,type TEXT NOT NULL,item_id TEXT NOT NULL,added_at INTEGER NOT NULL,PRIMARY KEY(account_id,type,item_id));
                CREATE INDEX favorites_added ON favorites(account_id,added_at DESC,type,item_id);
                PRAGMA user_version=15;
                """;
            command.ExecuteNonQuery();
        }
        else if (version == 8)
        {
            MigrateTitleCleanup(connection);
        }
        using (var currentVersion = connection.CreateCommand())
        {
            currentVersion.CommandText = "PRAGMA user_version";
            if (Convert.ToInt32(currentVersion.ExecuteScalar()) == 9)
                MigrateFavouriteAddedAt(connection);
        }
        using (var currentVersion = connection.CreateCommand())
        {
            currentVersion.CommandText = "PRAGMA user_version";
            if (Convert.ToInt32(currentVersion.ExecuteScalar()) == 10)
                MigrateVisitOrdering(connection);
        }
        using (var currentVersion = connection.CreateCommand())
        {
            currentVersion.CommandText = "PRAGMA user_version";
            if (Convert.ToInt32(currentVersion.ExecuteScalar()) == 11)
                MigrateSeriesPlayback(connection);
        }
        using (var currentVersion = connection.CreateCommand())
        {
            currentVersion.CommandText = "PRAGMA user_version";
            if (Convert.ToInt32(currentVersion.ExecuteScalar()) == 12)
                MigrateTitleOrdering(connection);
        }
        using (var currentVersion = connection.CreateCommand())
        {
            currentVersion.CommandText = "PRAGMA user_version";
            if (Convert.ToInt32(currentVersion.ExecuteScalar()) == 13)
                MigrateCatalogMetadata(connection);
        }
        using (var currentVersion = connection.CreateCommand())
        {
            currentVersion.CommandText = "PRAGMA user_version";
            if (Convert.ToInt32(currentVersion.ExecuteScalar()) == 14)
                MigrateFavorites(connection);
        }
    }

    private static void MigrateFavorites(SqliteConnection connection)
    {
        using var transaction = connection.BeginTransaction();
        using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = "CREATE TABLE favorites(account_id TEXT NOT NULL,type TEXT NOT NULL,item_id TEXT NOT NULL,added_at INTEGER NOT NULL,PRIMARY KEY(account_id,type,item_id)); CREATE INDEX favorites_added ON favorites(account_id,added_at DESC,type,item_id); INSERT INTO favorites(account_id,type,item_id,added_at) SELECT account_id,type,id,COALESCE(favourite_added_at,0) FROM items WHERE favourite=1; PRAGMA user_version=15;";
        command.ExecuteNonQuery();
        transaction.Commit();
    }

    private static void MigrateCatalogMetadata(SqliteConnection connection)
    {
        using var transaction = connection.BeginTransaction();
        using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = "ALTER TABLE items ADD COLUMN year TEXT; ALTER TABLE items ADD COLUMN genre TEXT; ALTER TABLE items ADD COLUMN cast TEXT; ALTER TABLE items ADD COLUMN metadata_fetched INTEGER NOT NULL DEFAULT 0; PRAGMA user_version=14;";
        command.ExecuteNonQuery();
        transaction.Commit();
    }

    public void SaveMetadata(ProviderAccount account, CatalogItemType type, string itemId, CatalogMetadata metadata)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "UPDATE items SET year=$year,rating=$rating,genre=$genre,plot=$plot,[cast]=$cast,metadata_fetched=1 WHERE account_id=$a AND type=$type AND id=$id";
        command.Parameters.AddWithValue("$year", (object?)metadata.Year ?? DBNull.Value);
        command.Parameters.AddWithValue("$rating", (object?)metadata.Rating ?? DBNull.Value);
        command.Parameters.AddWithValue("$genre", (object?)metadata.Genre ?? DBNull.Value);
        command.Parameters.AddWithValue("$plot", (object?)metadata.Plot ?? DBNull.Value);
        command.Parameters.AddWithValue("$cast", (object?)metadata.Cast ?? DBNull.Value);
        command.Parameters.AddWithValue("$a", account.AccountId);
        command.Parameters.AddWithValue("$type", TypeName(type));
        command.Parameters.AddWithValue("$id", itemId);
        command.ExecuteNonQuery();
    }

    public CatalogMetadata? GetMetadata(ProviderAccount account, CatalogItemType type, string itemId)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT year,rating,genre,plot,[cast],metadata_fetched FROM items WHERE account_id=$a AND type=$type AND id=$id";
        command.Parameters.AddWithValue("$a", account.AccountId);
        command.Parameters.AddWithValue("$type", TypeName(type));
        command.Parameters.AddWithValue("$id", itemId);
        using var reader = command.ExecuteReader();
        if (!reader.Read() || reader.GetInt32(5) == 0) return null;
        return new CatalogMetadata(ReadNullable(reader, 0), ReadNullable(reader, 1), ReadNullable(reader, 2),
            ReadNullable(reader, 3), ReadNullable(reader, 4));
    }

    private static string? ReadNullable(SqliteDataReader reader, int ordinal) => reader.IsDBNull(ordinal) ? null : reader.GetString(ordinal);

    private static void MigrateSeriesPlayback(SqliteConnection connection)
    {
        using var command = connection.CreateCommand();
        command.CommandText = "CREATE TABLE series_playback(account_id TEXT NOT NULL,series_id TEXT NOT NULL,last_episode_id TEXT NOT NULL,finished INTEGER NOT NULL DEFAULT 0,updated_at INTEGER NOT NULL,PRIMARY KEY(account_id,series_id)); PRAGMA user_version=12;";
        command.ExecuteNonQuery();
    }

    private static void MigrateTitleOrdering(SqliteConnection connection)
    {
        using var transaction = connection.BeginTransaction();
        var rows = new List<(string Account, string Type, string Id, string Title)>();
        using (var select = connection.CreateCommand())
        {
            select.Transaction = transaction;
            select.CommandText = "SELECT account_id,type,id,title FROM items";
            using var reader = select.ExecuteReader();
            while (reader.Read()) rows.Add((reader.GetString(0), reader.GetString(1), reader.GetString(2), reader.GetString(3)));
        }
        foreach (var row in rows)
            Execute(connection, transaction, "UPDATE items SET title_sort=$sort WHERE account_id=$a AND type=$type AND id=$id",
                ("$sort", SortTitle(row.Title)), ("$a", row.Account), ("$type", row.Type), ("$id", row.Id));
        Execute(connection, transaction, "DROP INDEX IF EXISTS items_most_visited; CREATE INDEX items_most_visited ON items(account_id,type,visit_count DESC,last_tuned_at DESC,title_sort COLLATE NOCASE,title COLLATE NOCASE,id); PRAGMA user_version=13;");
        transaction.Commit();
    }

    private static void MigrateVisitOrdering(SqliteConnection connection)
    {
        using var transaction = connection.BeginTransaction();
        using var command = connection.CreateCommand();
        command.Transaction = transaction;
        var existingColumns = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        using (var columns = connection.CreateCommand())
        {
            columns.Transaction = transaction;
            columns.CommandText = "PRAGMA table_info(items)";
            using var reader = columns.ExecuteReader();
            while (reader.Read()) existingColumns.Add(reader.GetString(1));
        }
        command.CommandText = "ALTER TABLE items ADD COLUMN title_sort TEXT NOT NULL DEFAULT ''; ALTER TABLE items ADD COLUMN visit_count INTEGER NOT NULL DEFAULT 0;" +
            (existingColumns.Contains("last_tuned_at") ? string.Empty : " ALTER TABLE items ADD COLUMN last_tuned_at INTEGER;");
        command.ExecuteNonQuery();
        var rows = new List<(string Account, string Type, string Id, string SortTitle)>();
        using (var select = connection.CreateCommand())
        {
            select.Transaction = transaction;
            select.CommandText = "SELECT account_id,type,id,title FROM items";
            using var reader = select.ExecuteReader();
            while (reader.Read()) rows.Add((reader.GetString(0), reader.GetString(1), reader.GetString(2), SortTitle(reader.GetString(3))));
        }
        foreach (var row in rows)
            Execute(connection, transaction, "UPDATE items SET title_sort=$sort WHERE account_id=$a AND type=$type AND id=$id",
                ("$sort", row.SortTitle), ("$a", row.Account), ("$type", row.Type), ("$id", row.Id));
        command.CommandText = "CREATE INDEX items_most_visited ON items(account_id,type,visit_count DESC,last_tuned_at DESC,title_sort,id); PRAGMA user_version=11;";
        command.ExecuteNonQuery();
        transaction.Commit();
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

    private static void MigrateFavouriteAddedAt(SqliteConnection connection)
    {
        using var transaction = connection.BeginTransaction();
        var columns = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        using (var columnList = connection.CreateCommand())
        {
            columnList.Transaction = transaction;
            columnList.CommandText = "PRAGMA table_info(items)";
            using var reader = columnList.ExecuteReader();
            while (reader.Read()) columns.Add(reader.GetString(1));
        }
        using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = "ALTER TABLE items ADD COLUMN favourite_added_at INTEGER;";
        command.ExecuteNonQuery();
        if (columns.Contains("account_id") && columns.Contains("type") && columns.Contains("favourite"))
        {
            command.CommandText = "CREATE INDEX items_favourites_added ON items(account_id,type,favourite,favourite_added_at DESC);";
            command.ExecuteNonQuery();
        }
        command.CommandText = "PRAGMA user_version=10;";
        command.ExecuteNonQuery();
        transaction.Commit();
    }

    private static string CleanTitle(string? title)
    {
        var cleaned = Whitespace.Replace(EmptyBrackets.Replace(title ?? string.Empty, " "), " ").Trim();
        return string.IsNullOrWhiteSpace(cleaned) ? "Untitled" : cleaned;
    }

    // Tie-break keys group Unicode letters first, digits second, and punctuation/symbols last.
    // Preserve the whole title so punctuation does not turn a leading number into a letter.
    private static string SortTitle(string? title)
    {
        var cleaned = CleanTitle(title);
        var first = cleaned[0];
        var bucket = char.IsLetter(first) ? '0' : char.IsDigit(first) ? '1' : '2';
        return $"{bucket}{cleaned}";
    }

    public void ReplaceSnapshot(ProviderAccount account, CatalogItemType type, IReadOnlyList<ChannelGroup> groups, IReadOnlyList<Channel> channels)
    {
        var typeName = TypeName(type);
        using var connection = Open();
        using var transaction = connection.BeginTransaction();
        Execute(connection, transaction, "DELETE FROM categories WHERE account_id=$account AND type=$type; DROP TABLE IF EXISTS temp.incoming_item_ids; CREATE TEMP TABLE incoming_item_ids(id TEXT PRIMARY KEY);", ("$account", account.AccountId), ("$type", typeName));
        for (var i = 0; i < groups.Count; i++)
            Execute(connection, transaction, "INSERT INTO categories(account_id,type,id,name,position) VALUES($a,$type,$id,$name,$pos)", ("$a", account.AccountId), ("$type", typeName), ("$id", groups[i].Id), ("$name", groups[i].Name), ("$pos", groups[i].SortOrder ?? i));
        foreach (var channel in channels)
        {
            Execute(connection, transaction, "INSERT INTO items(account_id,type,id,category_id,title,title_sort,artwork,extension,added_at,first_indexed_at) VALUES($a,$type,$id,$cat,$title,$sort,$art,$ext,$added,$now) ON CONFLICT(account_id,type,id) DO UPDATE SET category_id=excluded.category_id,title=excluded.title,title_sort=excluded.title_sort,artwork=excluded.artwork,extension=excluded.extension,added_at=excluded.added_at",
                ("$a", account.AccountId), ("$type", typeName), ("$id", channel.Id), ("$cat", channel.GroupId ?? string.Empty), ("$title", CleanTitle(channel.Name)), ("$sort", SortTitle(channel.Name)), ("$art", channel.LogoUri?.ToString()), ("$ext", channel.Source.ContainerExtension ?? DefaultExtension(type)), ("$added", channel.AddedAt?.ToUnixTimeMilliseconds() ?? 0), ("$now", DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()));
            Execute(connection, transaction, "INSERT INTO incoming_item_ids(id) VALUES($id)", ("$id", channel.Id));
        }
        Execute(connection, transaction, "DELETE FROM items WHERE account_id=$a AND type=$type AND id NOT IN (SELECT id FROM incoming_item_ids)",
            ("$a", account.AccountId), ("$type", typeName));
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

    public CatalogPage GetChannels(ProviderAccount account, CatalogItemType type, string? groupId = null, string? filter = null, int offset = 0, int limit = 100, bool mostVisited = false)
        => GetChannels(account, null, type, groupId, filter, offset, limit, mostVisited);

    public CatalogPage GetChannels(ProviderAccount account, ProviderConnection? providerConnection, CatalogItemType type, string? groupId = null, string? filter = null, int offset = 0, int limit = 100, bool mostVisited = false)
    {
        using var sqliteConnection = Open();
        var where = "account_id=$a AND type=$type AND ($cat IS NULL OR category_id=$cat) AND ($filter IS NULL OR title LIKE $pattern ESCAPE '\\')";
        using var count = sqliteConnection.CreateCommand(); count.CommandText = $"SELECT COUNT(*) FROM items WHERE {where}";
        AddBrowseParameters(count, account, type, groupId, filter);
        var total = Convert.ToInt32(count.ExecuteScalar());
        using var command = sqliteConnection.CreateCommand(); command.CommandText = $"SELECT id,category_id,title,artwork,extension,added_at,year,rating,genre,plot,[cast] FROM items WHERE {where} ORDER BY {(mostVisited ? "visit_count DESC,last_tuned_at DESC,title_sort COLLATE NOCASE,title COLLATE NOCASE,id" : "title COLLATE NOCASE,id")} LIMIT $limit OFFSET $offset";
        AddBrowseParameters(command, account, type, groupId, filter); command.Parameters.AddWithValue("$limit", Math.Max(1, limit)); command.Parameters.AddWithValue("$offset", Math.Max(0, offset));
        using var reader = command.ExecuteReader(); var rows = new List<Channel>();
        while (reader.Read())
        {
            var id = reader.GetString(0); Uri? artwork = Uri.TryCreate(reader.IsDBNull(3) ? null : reader.GetString(3), UriKind.Absolute, out var uri) ? uri : null;
            DateTimeOffset? added = reader.IsDBNull(5) || reader.GetInt64(5) == 0 ? null : DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(5));
            var title = CleanTitle(reader.GetString(2));
            var extension = reader.IsDBNull(4) ? null : reader.GetString(4);
            rows.Add(new(account.AccountId, id, reader.IsDBNull(1) ? null : reader.GetString(1), title, title, artwork, added, null, StreamSourceFor(providerConnection, type, id, extension), ReadChannelMetadata(reader, 6)));
        }
        return new(rows, total);
    }

    public IReadOnlyList<Channel> GetAllChannels(
        ProviderAccount account,
        ProviderConnection? providerConnection,
        CatalogItemType type,
        string groupId,
        int pageSize = 100)
    {
        var batchSize = Math.Max(1, pageSize);
        var firstPage = GetChannels(account, providerConnection, type, groupId, offset: 0, limit: batchSize);
        if (firstPage.TotalCount <= firstPage.Items.Count)
            return firstPage.Items;

        var channels = new List<Channel>(firstPage.TotalCount);
        channels.AddRange(firstPage.Items);
        for (var offset = firstPage.Items.Count; offset < firstPage.TotalCount; offset += batchSize)
            channels.AddRange(GetChannels(account, providerConnection, type, groupId, offset: offset, limit: batchSize).Items);
        return channels;
    }

    public IReadOnlyDictionary<string, CatalogPage> GetChannelsGroupedByCategory(
        ProviderAccount account,
        ProviderConnection? connection,
        CatalogItemType type,
        string? filter = null,
        int limit = 100)
    {
        using var sqliteConnection = Open();
        using var command = sqliteConnection.CreateCommand();
        command.CommandText = """
            WITH ranked AS (
                SELECT id, category_id, title, artwork, extension, added_at, year, rating, genre, plot, [cast],
                       COUNT(*) OVER (PARTITION BY category_id) AS total_count,
                       ROW_NUMBER() OVER (PARTITION BY category_id ORDER BY title, id) AS row_number
                FROM items
                WHERE account_id=$a AND type=$type
                  AND ($filter IS NULL OR title LIKE $pattern ESCAPE '\')
            )
            SELECT id, category_id, title, artwork, extension, added_at, total_count, year, rating, genre, plot, [cast]
            FROM ranked
            WHERE row_number <= $limit
            ORDER BY category_id, title, id;
            """;
        command.Parameters.AddWithValue("$a", account.AccountId);
        command.Parameters.AddWithValue("$type", TypeName(type));
        command.Parameters.AddWithValue("$filter", string.IsNullOrWhiteSpace(filter) ? DBNull.Value : filter);
        command.Parameters.AddWithValue("$pattern", string.IsNullOrWhiteSpace(filter) ? DBNull.Value : "%" + filter.Replace("\\", "\\\\").Replace("%", "\\%").Replace("_", "\\_") + "%");
        command.Parameters.AddWithValue("$limit", Math.Max(1, limit));

        var pages = new Dictionary<string, List<Channel>>(StringComparer.Ordinal);
        var counts = new Dictionary<string, int>(StringComparer.Ordinal);
        using var reader = command.ExecuteReader();
        while (reader.Read())
        {
            var categoryId = reader.GetString(1);
            var id = reader.GetString(0);
            Uri? artwork = Uri.TryCreate(reader.IsDBNull(3) ? null : reader.GetString(3), UriKind.Absolute, out var uri) ? uri : null;
            DateTimeOffset? added = reader.IsDBNull(5) || reader.GetInt64(5) == 0 ? null : DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(5));
            var extension = reader.IsDBNull(4) ? null : reader.GetString(4);
            if (!pages.TryGetValue(categoryId, out var rows))
            {
                rows = new List<Channel>();
                pages[categoryId] = rows;
                counts[categoryId] = reader.GetInt32(6);
            }
            var title = CleanTitle(reader.GetString(2));
            rows.Add(new(account.AccountId, id, categoryId, title, title, artwork, added, null, StreamSourceFor(connection, type, id, extension), ReadChannelMetadata(reader, 7)));
        }

        return pages.ToDictionary(pair => pair.Key, pair => new CatalogPage(pair.Value, counts[pair.Key]), StringComparer.Ordinal);
    }

    public IReadOnlyList<Channel> GetRecentlyAdded(ProviderAccount account, CatalogItemType type, ProviderConnection? connection = null, int limit = 50, string? filter = null)
        => GetOrderedItems(account, type, connection, "added_at > 0", "added_at DESC, id ASC", Math.Clamp(limit, 0, 50), filter);

    public IReadOnlyList<Channel> GetRecentlyAdded(ProviderAccount account, ProviderConnection? connection = null, int limit = 50, string? filter = null)
        => Enum.GetValues<CatalogItemType>()
            .SelectMany(type => GetRecentlyAdded(account, type, connection, limit, filter))
            .OrderByDescending(item => item.AddedAt)
            .ThenBy(item => item.Source.Kind.ToString(), StringComparer.Ordinal)
            .ThenBy(item => item.Id, StringComparer.Ordinal)
            .ToArray();

    public IReadOnlyList<Channel> GetFavorites(ProviderAccount account, CatalogItemType type, ProviderConnection? connection = null, int limit = 100, string? filter = null)
        => GetOrderedItems(account, type, connection, "EXISTS (SELECT 1 FROM favorites f WHERE f.account_id=items.account_id AND f.type=items.type AND f.item_id=items.id)", "(SELECT f.added_at FROM favorites f WHERE f.account_id=items.account_id AND f.type=items.type AND f.item_id=items.id) DESC, id ASC", limit, filter);

    public IReadOnlyList<Channel> GetRecentlyPlayed(ProviderAccount account, CatalogItemType type, ProviderConnection? connection = null, int limit = 100, string? filter = null)
        => GetOrderedItems(account, type, connection, "visit_count > 0", "last_tuned_at DESC, id ASC", limit, filter);

    public IReadOnlyList<Channel> GetRecentlyPlayed(ProviderAccount account, ProviderConnection? connection = null, int limit = 100, string? filter = null)
        => GetOrderedItemsAcrossTypes(account, connection, "items.visit_count > 0", "items.last_tuned_at DESC, items.type, items.id", limit, filter);

    public IReadOnlyList<Channel> GetFavorites(ProviderAccount account, ProviderConnection? connection = null, int limit = 100, string? filter = null)
        => GetOrderedItemsAcrossTypes(account, connection,
            "EXISTS (SELECT 1 FROM favorites f WHERE f.account_id=items.account_id AND f.type=items.type AND f.item_id=items.id)",
            "(SELECT f.added_at FROM favorites f WHERE f.account_id=items.account_id AND f.type=items.type AND f.item_id=items.id) DESC, items.type, items.id", limit, filter);

    public IReadOnlyList<Channel> GetContinueWatching(ProviderAccount account, CatalogItemType type, ProviderConnection? connection = null, int limit = 100)
        => GetOrderedItems(account, type, connection, "resume_ms > 0", "resume_updated_at DESC, id ASC", limit);

    public void SetFavorite(ProviderAccount account, CatalogItemType type, string id, bool isFavorite)
    {
        using var connection = Open();
        using var transaction = connection.BeginTransaction();
        using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = isFavorite
            ? "INSERT INTO favorites(account_id,type,item_id,added_at) VALUES($a,$type,$id,$now) ON CONFLICT(account_id,type,item_id) DO NOTHING; UPDATE items SET favourite=1, favourite_added_at=COALESCE(favourite_added_at,$now) WHERE account_id=$a AND type=$type AND id=$id"
            : "DELETE FROM favorites WHERE account_id=$a AND type=$type AND item_id=$id; UPDATE items SET favourite=0, favourite_added_at=NULL WHERE account_id=$a AND type=$type AND id=$id";
        command.Parameters.AddWithValue("$a", account.AccountId);
        command.Parameters.AddWithValue("$type", TypeName(type));
        command.Parameters.AddWithValue("$id", id);
        if (isFavorite) command.Parameters.AddWithValue("$now", DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        command.ExecuteNonQuery();
        transaction.Commit();
    }

    public void AddFavorite(ProviderAccount account, CatalogItemType type, string id) => SetFavorite(account, type, id, true);

    public void RemoveFavorite(ProviderAccount account, CatalogItemType type, string id) => SetFavorite(account, type, id, false);

    public bool IsFavorite(ProviderAccount account, CatalogItemType type, string id)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT EXISTS(SELECT 1 FROM favorites WHERE account_id=$a AND type=$type AND item_id=$id)";
        command.Parameters.AddWithValue("$a", account.AccountId);
        command.Parameters.AddWithValue("$type", TypeName(type));
        command.Parameters.AddWithValue("$id", id);
        return Convert.ToInt32(command.ExecuteScalar()) != 0;
    }

    public bool ToggleFavorite(ProviderAccount account, CatalogItemType type, string id)
    {
        var isFavorite = !IsFavorite(account, type, id);
        if (isFavorite) AddFavorite(account, type, id);
        else RemoveFavorite(account, type, id);
        return isFavorite;
    }

    public void UpdateResumePosition(ProviderAccount account, CatalogItemType type, string id, long positionMilliseconds)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "UPDATE items SET resume_ms=$position, resume_updated_at=$now WHERE account_id=$a AND type=$type AND id=$id";
        command.Parameters.AddWithValue("$position", Math.Max(0, positionMilliseconds));
        command.Parameters.AddWithValue("$now", DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        command.Parameters.AddWithValue("$a", account.AccountId);
        command.Parameters.AddWithValue("$type", TypeName(type));
        command.Parameters.AddWithValue("$id", id);
        command.ExecuteNonQuery();
    }

    public void RecordVisit(ProviderAccount account, CatalogItemType type, string id)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "UPDATE items SET visit_count=visit_count+1,last_tuned_at=$now WHERE account_id=$a AND type=$type AND id=$id";
        command.Parameters.AddWithValue("$now", DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        command.Parameters.AddWithValue("$a", account.AccountId);
        command.Parameters.AddWithValue("$type", TypeName(type));
        command.Parameters.AddWithValue("$id", id);
        command.ExecuteNonQuery();
    }

    public (string? EpisodeId, bool Finished) GetSeriesPlayback(ProviderAccount account, string seriesId)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT last_episode_id,finished FROM series_playback WHERE account_id=$a AND series_id=$id";
        command.Parameters.AddWithValue("$a", account.AccountId);
        command.Parameters.AddWithValue("$id", seriesId);
        using var reader = command.ExecuteReader();
        return reader.Read() ? (reader.GetString(0), reader.GetInt32(1) != 0) : (null, false);
    }

    public void UpdateSeriesPlayback(ProviderAccount account, string seriesId, string episodeId, bool finished)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "INSERT INTO series_playback(account_id,series_id,last_episode_id,finished,updated_at) VALUES($a,$series,$episode,$finished,$now) ON CONFLICT(account_id,series_id) DO UPDATE SET last_episode_id=excluded.last_episode_id,finished=excluded.finished,updated_at=excluded.updated_at";
        command.Parameters.AddWithValue("$a", account.AccountId);
        command.Parameters.AddWithValue("$series", seriesId);
        command.Parameters.AddWithValue("$episode", episodeId);
        command.Parameters.AddWithValue("$finished", finished ? 1 : 0);
        command.Parameters.AddWithValue("$now", DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        command.ExecuteNonQuery();
    }

    private IReadOnlyList<Channel> GetOrderedItems(ProviderAccount account, CatalogItemType type, ProviderConnection? providerConnection, string predicate, string ordering, int limit, string? filter = null)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = $"SELECT id,category_id,title,artwork,extension,added_at,year,rating,genre,plot,[cast] FROM items WHERE account_id=$a AND type=$type AND {predicate} AND ($filter IS NULL OR title LIKE $pattern ESCAPE '\\') ORDER BY {ordering} LIMIT $limit";
        command.Parameters.AddWithValue("$a", account.AccountId);
        command.Parameters.AddWithValue("$type", TypeName(type));
        command.Parameters.AddWithValue("$limit", Math.Max(1, limit));
        command.Parameters.AddWithValue("$filter", string.IsNullOrWhiteSpace(filter) ? DBNull.Value : filter);
        command.Parameters.AddWithValue("$pattern", string.IsNullOrWhiteSpace(filter) ? DBNull.Value : "%" + filter.Replace("\\", "\\\\").Replace("%", "\\%").Replace("_", "\\_") + "%");
        using var reader = command.ExecuteReader();
        var rows = new List<Channel>();
        while (reader.Read())
        {
            var id = reader.GetString(0);
            Uri? artwork = Uri.TryCreate(reader.IsDBNull(3) ? null : reader.GetString(3), UriKind.Absolute, out var uri) ? uri : null;
            DateTimeOffset? added = reader.IsDBNull(5) || reader.GetInt64(5) == 0 ? null : DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(5));
            var title = CleanTitle(reader.GetString(2));
            var extension = reader.IsDBNull(4) ? null : reader.GetString(4);
            rows.Add(new(account.AccountId, id, reader.IsDBNull(1) ? null : reader.GetString(1), title, title, artwork, added, null, StreamSourceFor(providerConnection, type, id, extension), ReadChannelMetadata(reader, 6)));
        }
        return rows;
    }

    private IReadOnlyList<Channel> GetOrderedItemsAcrossTypes(ProviderAccount account, ProviderConnection? providerConnection, string predicate, string ordering, int limit, string? filter)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = $"SELECT items.type,items.id,items.category_id,items.title,items.artwork,items.extension,items.added_at,items.year,items.rating,items.genre,items.plot,items.[cast] FROM items WHERE items.account_id=$a AND items.type IN ('Live','Movie','Series') AND {predicate} AND ($filter IS NULL OR items.title LIKE $pattern ESCAPE '\\') ORDER BY {ordering} LIMIT $limit";
        command.Parameters.AddWithValue("$a", account.AccountId);
        command.Parameters.AddWithValue("$limit", Math.Max(1, limit));
        command.Parameters.AddWithValue("$filter", string.IsNullOrWhiteSpace(filter) ? DBNull.Value : filter);
        command.Parameters.AddWithValue("$pattern", string.IsNullOrWhiteSpace(filter) ? DBNull.Value : "%" + filter.Replace("\\", "\\\\").Replace("%", "\\%").Replace("_", "\\_") + "%");
        using var reader = command.ExecuteReader();
        var rows = new List<Channel>();
        while (reader.Read())
        {
            var type = Enum.Parse<CatalogItemType>(reader.GetString(0));
            var id = reader.GetString(1);
            Uri? artwork = Uri.TryCreate(reader.IsDBNull(4) ? null : reader.GetString(4), UriKind.Absolute, out var uri) ? uri : null;
            DateTimeOffset? added = reader.IsDBNull(6) || reader.GetInt64(6) == 0 ? null : DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(6));
            var title = CleanTitle(reader.GetString(3));
            var extension = reader.IsDBNull(5) ? null : reader.GetString(5);
            rows.Add(new(account.AccountId, id, reader.IsDBNull(2) ? null : reader.GetString(2), title, title, artwork, added, null,
                StreamSourceFor(providerConnection, type, id, extension), ReadChannelMetadata(reader, 7)));
        }
        return rows;
    }

    private SqliteConnection Open() { var connection = new SqliteConnection(_connectionString); connection.Open(); return connection; }
    private static IReadOnlyDictionary<string, string> ReadChannelMetadata(SqliteDataReader reader, int start)
    {
        var metadata = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var (index, key) in new[] { (0, "year"), (1, "rating"), (2, "genre"), (3, "plot"), (4, "cast") })
            if (!reader.IsDBNull(start + index)) metadata[key] = reader.GetString(start + index);
        return metadata;
    }
    private static StreamSource StreamSourceFor(ProviderConnection? connection, CatalogItemType type, string id, string? extension)
    {
        var directUri = connection is null ? null : type switch
        {
            CatalogItemType.Movie => new Uri(StreamUrlBuilder.Vod(connection.Endpoint, connection.Username, connection.Password, id, extension)),
            CatalogItemType.Series => new Uri(StreamUrlBuilder.Series(connection.Endpoint, connection.Username, connection.Password, id, extension)),
            _ => new Uri(StreamUrlBuilder.Live(connection.Endpoint, connection.Username, connection.Password, id, extension)),
        };
        return new StreamSource(id, StreamKindFor(type), extension, directUri);
    }
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
        CatalogItemType.Series => StreamKind.Series,
        _ => StreamKind.Live,
    };
    private static void Execute(SqliteConnection c, SqliteTransaction t, string sql, params (string Name, object? Value)[] values)
    { using var cmd = c.CreateCommand(); cmd.Transaction = t; cmd.CommandText = sql; foreach (var (name,value) in values) cmd.Parameters.AddWithValue(name, value ?? DBNull.Value); cmd.ExecuteNonQuery(); }

    public void Dispose()
    {
        using var connection = new SqliteConnection(_connectionString);
        SqliteConnection.ClearPool(connection);
    }
}
