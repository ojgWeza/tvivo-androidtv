using Microsoft.Data.Sqlite;
using Tvivo.Core;

namespace Tvivo.Infrastructure;

public sealed record EpgSyncState(string AccountId, DateTimeOffset FetchedAt, string Status, int ProgrammeCount);

public sealed class EpgRepository : IDisposable
{
    private const int SchemaVersion = 1;
    private readonly string _connectionString;

    public EpgRepository(string? databasePath = null)
    {
        databasePath ??= Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Tvivo", "winui-epg.sqlite");
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(databasePath))!);
        _connectionString = new SqliteConnectionStringBuilder { DataSource = databasePath }.ToString();
        using var connection = Open();
        using var versionCommand = connection.CreateCommand();
        versionCommand.CommandText = "PRAGMA user_version;";
        var version = Convert.ToInt32(versionCommand.ExecuteScalar());
        if (version == 0) CreateSchema(connection);
        else if (version != SchemaVersion) throw new InvalidOperationException($"Unsupported EPG database schema version {version}.");
    }

    public EpgSyncState? GetSyncState(ProviderAccount account)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT fetched_at,status,programme_count FROM epg_sync_state WHERE account_id=$account;";
        command.Parameters.AddWithValue("$account", account.AccountId);
        using var reader = command.ExecuteReader();
        return reader.Read()
            ? new EpgSyncState(account.AccountId, DateTimeOffset.FromUnixTimeSeconds(reader.GetInt64(0)), reader.GetString(1), reader.GetInt32(2))
            : null;
    }

    public IReadOnlyList<EpgChannelMap> GetChannelMap(ProviderAccount account)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT stream_id,epg_channel_id FROM epg_channel_map WHERE account_id=$account ORDER BY stream_id;";
        command.Parameters.AddWithValue("$account", account.AccountId);
        using var reader = command.ExecuteReader();
        var result = new List<EpgChannelMap>();
        while (reader.Read()) result.Add(new EpgChannelMap(reader.GetString(0), reader.GetString(1)));
        return result;
    }

    public IReadOnlyDictionary<string, EpgNowNext> GetNowNext(
        ProviderAccount account,
        IReadOnlyCollection<string> epgChannelIds,
        DateTimeOffset utcNow)
    {
        var ids = epgChannelIds.Where(id => !string.IsNullOrWhiteSpace(id)).Select(id => id.Trim()).Distinct(StringComparer.Ordinal).ToArray();
        var result = ids.ToDictionary(id => id, _ => new EpgNowNext(null, null), StringComparer.Ordinal);
        if (ids.Length == 0) return result;

        using var connection = Open();
        using var command = connection.CreateCommand();
        var placeholders = new string[ids.Length];
        for (var index = 0; index < ids.Length; index++)
        {
            placeholders[index] = $"$id{index}";
            command.Parameters.AddWithValue(placeholders[index], ids[index]);
        }
        command.CommandText = $"""
            SELECT epg_channel_id,start_utc,end_utc,title,description
            FROM epg_programmes
            WHERE account_id=$account AND epg_channel_id IN ({string.Join(',', placeholders)})
              AND ((start_utc <= $now AND end_utc > $now) OR start_utc > $now)
            ORDER BY epg_channel_id,start_utc;
            """;
        command.Parameters.AddWithValue("$account", account.AccountId);
        command.Parameters.AddWithValue("$now", utcNow.ToUniversalTime().ToUnixTimeSeconds());
        using var reader = command.ExecuteReader();
        var nowById = new Dictionary<string, EpgProgramme>(StringComparer.Ordinal);
        var nextById = new Dictionary<string, EpgProgramme>(StringComparer.Ordinal);
        while (reader.Read())
        {
            var id = reader.GetString(0);
            var programme = ReadProgramme(reader);
            if (programme.StartUtc <= utcNow && programme.EndUtc > utcNow)
                nowById.TryAdd(id, programme);
            else if (programme.StartUtc > utcNow)
                nextById.TryAdd(id, programme);
        }
        foreach (var id in ids)
            result[id] = new EpgNowNext(nowById.GetValueOrDefault(id), nextById.GetValueOrDefault(id));
        return result;
    }

    public IReadOnlyList<EpgProgramme> GetProgrammes(ProviderAccount account, string epgChannelId, DateTimeOffset fromUtc, DateTimeOffset toUtc)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT epg_channel_id,start_utc,end_utc,title,description
            FROM epg_programmes
            WHERE account_id=$account AND epg_channel_id=$channel
              AND start_utc < $to_utc AND end_utc > $from_utc
            ORDER BY start_utc;
            """;
        command.Parameters.AddWithValue("$account", account.AccountId);
        command.Parameters.AddWithValue("$channel", epgChannelId.Trim());
        command.Parameters.AddWithValue("$from_utc", fromUtc.ToUniversalTime().ToUnixTimeSeconds());
        command.Parameters.AddWithValue("$to_utc", toUtc.ToUniversalTime().ToUnixTimeSeconds());
        using var reader = command.ExecuteReader();
        var result = new List<EpgProgramme>();
        while (reader.Read()) result.Add(ReadProgramme(reader));
        return result;
    }

    public int PruneExpired(ProviderAccount account, DateTimeOffset utcNow)
    {
        using var connection = Open();
        using var command = connection.CreateCommand();
        command.CommandText = "DELETE FROM epg_programmes WHERE account_id=$account AND end_utc <= $now;";
        command.Parameters.AddWithValue("$account", account.AccountId);
        command.Parameters.AddWithValue("$now", utcNow.ToUniversalTime().ToUnixTimeSeconds());
        return command.ExecuteNonQuery();
    }

    public void Import(
        ProviderAccount account,
        IReadOnlyCollection<EpgChannelMap> channelMap,
        IReadOnlyCollection<EpgProgramme> programmes,
        DateTimeOffset fetchedAt,
        EpgCapability capability = EpgCapability.Usable) =>
        ImportCoreAsync(account, channelMap, fetchedAt, capability, async add =>
        {
            foreach (var batch in programmes.Chunk(EpgXmltvParser.BatchSize)) await add(batch).ConfigureAwait(false);
        }, programmes.Count).GetAwaiter().GetResult();

    public Task<int> ImportXmltvAsync(
        ProviderAccount account,
        IReadOnlyCollection<EpgChannelMap> channelMap,
        Stream source,
        DateTimeOffset utcNow,
        CancellationToken cancellationToken = default) =>
        ImportXmltvCoreAsync(account, channelMap, source, utcNow, cancellationToken);

    private async Task<int> ImportXmltvCoreAsync(
        ProviderAccount account,
        IReadOnlyCollection<EpgChannelMap> channelMap,
        Stream source,
        DateTimeOffset utcNow,
        CancellationToken cancellationToken)
    {
        var result = 0;
        await ImportCoreAsync(account, channelMap, utcNow, EpgCapability.Usable, async add =>
        {
            result = await EpgXmltvParser.ParseIntoAsync(source, utcNow, add, cancellationToken).ConfigureAwait(false);
        }, null, cancellationToken).ConfigureAwait(false);
        return result;
    }

    private async Task ImportCoreAsync(
        ProviderAccount account,
        IReadOnlyCollection<EpgChannelMap> channelMap,
        DateTimeOffset fetchedAt,
        EpgCapability capability,
        Func<Func<IReadOnlyList<EpgProgramme>, ValueTask>, Task> populate,
        int? knownProgrammeCount,
        CancellationToken cancellationToken = default)
    {
        using var connection = Open();
        using var transaction = connection.BeginTransaction();
        Execute(connection, transaction, """
            DROP TABLE IF EXISTS temp.stage_epg_channel_map;
            DROP TABLE IF EXISTS temp.stage_epg_programmes;
            CREATE TEMP TABLE stage_epg_channel_map(stream_id TEXT NOT NULL PRIMARY KEY, epg_channel_id TEXT NOT NULL);
            CREATE TEMP TABLE stage_epg_programmes(epg_channel_id TEXT NOT NULL, start_utc INTEGER NOT NULL, end_utc INTEGER NOT NULL, title TEXT NOT NULL, description TEXT NULL, PRIMARY KEY(epg_channel_id,start_utc));
            """);

        using var mapCommand = connection.CreateCommand();
        mapCommand.Transaction = transaction;
        mapCommand.CommandText = "INSERT OR REPLACE INTO stage_epg_channel_map(stream_id,epg_channel_id) VALUES($stream,$channel);";
        var streamParameter = mapCommand.Parameters.Add("$stream", SqliteType.Text);
        var channelParameter = mapCommand.Parameters.Add("$channel", SqliteType.Text);
        mapCommand.Prepare();
        foreach (var map in channelMap.Where(map => !string.IsNullOrWhiteSpace(map.StreamId) && !string.IsNullOrWhiteSpace(map.EpgChannelId)))
        {
            streamParameter.Value = map.StreamId.Trim();
            channelParameter.Value = map.EpgChannelId.Trim();
            mapCommand.ExecuteNonQuery();
        }

        using var programmeCommand = connection.CreateCommand();
        programmeCommand.Transaction = transaction;
        programmeCommand.CommandText = "INSERT OR REPLACE INTO stage_epg_programmes(epg_channel_id,start_utc,end_utc,title,description) VALUES($channel,$start,$end,$title,$description);";
        var programmeChannel = programmeCommand.Parameters.Add("$channel", SqliteType.Text);
        var programmeStart = programmeCommand.Parameters.Add("$start", SqliteType.Integer);
        var programmeEnd = programmeCommand.Parameters.Add("$end", SqliteType.Integer);
        var programmeTitle = programmeCommand.Parameters.Add("$title", SqliteType.Text);
        var programmeDescription = programmeCommand.Parameters.Add("$description", SqliteType.Text);
        programmeCommand.Prepare();
        async ValueTask AddBatch(IReadOnlyList<EpgProgramme> batch)
        {
            foreach (var programme in batch)
            {
                cancellationToken.ThrowIfCancellationRequested();
                programmeChannel.Value = programme.ChannelId.Trim();
                programmeStart.Value = programme.StartUtc.ToUniversalTime().ToUnixTimeSeconds();
                programmeEnd.Value = programme.EndUtc.ToUniversalTime().ToUnixTimeSeconds();
                programmeTitle.Value = programme.Title;
                programmeDescription.Value = programme.Description is null ? DBNull.Value : programme.Description;
                programmeCommand.ExecuteNonQuery();
            }
            await ValueTask.CompletedTask;
        }

        await populate(AddBatch).ConfigureAwait(false);
        using var countCommand = connection.CreateCommand();
        countCommand.Transaction = transaction;
        countCommand.CommandText = "SELECT COUNT(*) FROM stage_epg_programmes;";
        var count = Convert.ToInt32(countCommand.ExecuteScalar());
        if (count == 0 || (knownProgrammeCount is > 0 && knownProgrammeCount != count))
            throw new InvalidDataException("The EPG import contained no usable programmes.");

        Execute(connection, transaction, "DELETE FROM epg_channel_map WHERE account_id=$account;", ("$account", account.AccountId));
        Execute(connection, transaction, "DELETE FROM epg_programmes WHERE account_id=$account;", ("$account", account.AccountId));
        Execute(connection, transaction, """
            INSERT INTO epg_channel_map(account_id,stream_id,epg_channel_id)
            SELECT $account,stream_id,epg_channel_id FROM stage_epg_channel_map;
            INSERT INTO epg_programmes(account_id,epg_channel_id,start_utc,end_utc,title,description)
            SELECT $account,epg_channel_id,start_utc,end_utc,title,description FROM stage_epg_programmes;
            INSERT INTO epg_sync_state(account_id,fetched_at,status,programme_count)
            VALUES($account,$fetched_at,$status,$programme_count)
            ON CONFLICT(account_id) DO UPDATE SET fetched_at=excluded.fetched_at,status=excluded.status,programme_count=excluded.programme_count;
            """, ("$account", account.AccountId), ("$fetched_at", fetchedAt.ToUniversalTime().ToUnixTimeSeconds()),
            ("$status", capability.ToString()), ("$programme_count", count));
        transaction.Commit();
    }

    private SqliteConnection Open()
    {
        var connection = new SqliteConnection(_connectionString);
        connection.Open();
        return connection;
    }

    private static EpgProgramme ReadProgramme(SqliteDataReader reader) => new(
        reader.GetString(0),
        DateTimeOffset.FromUnixTimeSeconds(reader.GetInt64(1)),
        DateTimeOffset.FromUnixTimeSeconds(reader.GetInt64(2)),
        reader.GetString(3),
        reader.IsDBNull(4) ? null : reader.GetString(4));

    private static void CreateSchema(SqliteConnection connection)
    {
        using var command = connection.CreateCommand();
        command.CommandText = """
            CREATE TABLE epg_channel_map(account_id TEXT NOT NULL,stream_id TEXT NOT NULL,epg_channel_id TEXT NOT NULL,PRIMARY KEY(account_id,stream_id));
            CREATE TABLE epg_programmes(account_id TEXT NOT NULL,epg_channel_id TEXT NOT NULL,start_utc INTEGER NOT NULL,end_utc INTEGER NOT NULL,title TEXT NOT NULL,description TEXT NULL,PRIMARY KEY(account_id,epg_channel_id,start_utc));
            CREATE INDEX epg_programmes_channel_end ON epg_programmes(account_id,epg_channel_id,end_utc);
            CREATE TABLE epg_sync_state(account_id TEXT NOT NULL PRIMARY KEY,fetched_at INTEGER NOT NULL,status TEXT NOT NULL,programme_count INTEGER NOT NULL);
            PRAGMA user_version=1;
            """;
        command.ExecuteNonQuery();
    }

    private static void Execute(SqliteConnection connection, SqliteTransaction transaction, string sql, params (string Name, object Value)[] parameters)
    {
        using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = sql;
        foreach (var (name, value) in parameters) command.Parameters.AddWithValue(name, value);
        command.ExecuteNonQuery();
    }

    public void Dispose()
    {
    }
}
