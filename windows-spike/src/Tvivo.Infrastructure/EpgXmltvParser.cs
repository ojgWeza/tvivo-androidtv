using System.Globalization;
using System.Text.RegularExpressions;
using System.Xml;
using Tvivo.Core;

namespace Tvivo.Infrastructure;

public sealed record EpgXmltvParseResult(IReadOnlyList<EpgProgramme> Programmes, int ProgrammeCount);

public sealed class EpgImportLimitExceededException(string message) : Exception(message);

public static class EpgXmltvParser
{
    public const long MaxExpandedBytes = 512L * 1024 * 1024;
    public const int MaxProgrammes = 1_000_000;
    public const int BatchSize = 5_000;

    private static readonly Regex OffsetWithoutColon = new(@"([+-]\d{2})(\d{2})$", RegexOptions.Compiled | RegexOptions.CultureInvariant);

    public static async Task<EpgXmltvParseResult> ParseAsync(
        Stream source,
        DateTimeOffset utcNow,
        CancellationToken cancellationToken = default)
    {
        var programmes = new List<EpgProgramme>();
        await ParseIntoAsync(source, utcNow, batch =>
        {
            programmes.AddRange(batch);
            return ValueTask.CompletedTask;
        }, cancellationToken).ConfigureAwait(false);
        return new EpgXmltvParseResult(programmes, programmes.Count);
    }

    public static async Task<int> ParseIntoAsync(
        Stream source,
        DateTimeOffset utcNow,
        Func<IReadOnlyList<EpgProgramme>, ValueTask> onBatch,
        CancellationToken cancellationToken = default,
        long maxExpandedBytes = MaxExpandedBytes,
        int maxProgrammes = MaxProgrammes)
    {
        ArgumentNullException.ThrowIfNull(source);
        ArgumentNullException.ThrowIfNull(onBatch);
        if (maxExpandedBytes <= 0) throw new ArgumentOutOfRangeException(nameof(maxExpandedBytes));
        if (maxProgrammes <= 0) throw new ArgumentOutOfRangeException(nameof(maxProgrammes));

        var windowStart = utcNow.ToUniversalTime().Subtract(TimeSpan.FromHours(6));
        var windowEnd = utcNow.ToUniversalTime().AddHours(48);
        var batch = new List<EpgProgramme>(BatchSize);
        var count = 0;
        using var counted = new CountingReadStream(source, maxExpandedBytes);
        var settings = new XmlReaderSettings
        {
            DtdProcessing = DtdProcessing.Ignore,
            XmlResolver = null,
            Async = true,
            IgnoreComments = true,
            IgnoreWhitespace = true,
        };
        using var reader = XmlReader.Create(counted, settings);

        while (await reader.ReadAsync().ConfigureAwait(false))
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (reader.NodeType != XmlNodeType.Element || !string.Equals(reader.LocalName, "programme", StringComparison.OrdinalIgnoreCase))
                continue;

            var programme = await ReadProgrammeAsync(reader, cancellationToken).ConfigureAwait(false);
            if (programme is null || programme.StartUtc >= windowEnd || programme.EndUtc <= windowStart)
                continue;

            if (++count > maxProgrammes)
                throw new EpgImportLimitExceededException("The EPG programme limit was exceeded.");
            batch.Add(programme);
            if (batch.Count < BatchSize) continue;
            await onBatch(batch).ConfigureAwait(false);
            batch = new List<EpgProgramme>(BatchSize);
        }

        if (batch.Count > 0)
            await onBatch(batch).ConfigureAwait(false);
        return count;
    }

    public static bool TryParseTimestamp(string? value, out DateTimeOffset utc)
    {
        utc = default;
        if (string.IsNullOrWhiteSpace(value)) return false;
        var normalized = OffsetWithoutColon.Replace(value.Trim(), "$1:$2");
        if (!Regex.IsMatch(normalized, @"(?:Z|[+-]\d{2}:\d{2})$", RegexOptions.CultureInvariant)) return false;
        if (!DateTimeOffset.TryParseExact(normalized, "yyyyMMddHHmmss zzz", CultureInfo.InvariantCulture,
                DateTimeStyles.None, out var parsed)) return false;
        utc = parsed.ToUniversalTime();
        return true;
    }

    private static async Task<EpgProgramme?> ReadProgrammeAsync(XmlReader reader, CancellationToken cancellationToken)
    {
        var channelId = reader.GetAttribute("channel")?.Trim();
        var startText = reader.GetAttribute("start");
        var endText = reader.GetAttribute("stop") ?? reader.GetAttribute("end");
        string? title = null;
        string? description = null;

        if (!reader.IsEmptyElement)
        {
            await reader.ReadAsync().ConfigureAwait(false);
            while (!reader.EOF)
            {
                cancellationToken.ThrowIfCancellationRequested();
                if (reader.NodeType == XmlNodeType.EndElement && string.Equals(reader.LocalName, "programme", StringComparison.OrdinalIgnoreCase))
                    break;
                if (reader.NodeType != XmlNodeType.Element)
                {
                    await reader.ReadAsync().ConfigureAwait(false);
                    continue;
                }

                // ReadElementContentAsString and Skip already advance past the element, so no extra Read here.
                if (string.Equals(reader.LocalName, "title", StringComparison.OrdinalIgnoreCase))
                {
                    var text = await reader.ReadElementContentAsStringAsync().ConfigureAwait(false);
                    title ??= text;
                }
                else if (string.Equals(reader.LocalName, "desc", StringComparison.OrdinalIgnoreCase) ||
                         string.Equals(reader.LocalName, "description", StringComparison.OrdinalIgnoreCase))
                    description = await reader.ReadElementContentAsStringAsync().ConfigureAwait(false);
                else
                    await reader.SkipAsync().ConfigureAwait(false);
            }
        }

        if (string.IsNullOrWhiteSpace(channelId) || !TryParseTimestamp(startText, out var start) ||
            !TryParseTimestamp(endText, out var end) || end <= start || string.IsNullOrWhiteSpace(title))
            return null;
        return new EpgProgramme(channelId, start, end, title.Trim(), string.IsNullOrWhiteSpace(description) ? null : description.Trim());
    }

    private sealed class CountingReadStream(Stream inner, long maxBytes) : Stream
    {
        private long _read;

        public override bool CanRead => inner.CanRead;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => _read; set => throw new NotSupportedException(); }
        public override void Flush() => throw new NotSupportedException();
        public override int Read(byte[] buffer, int offset, int count) => Count(inner.Read(buffer, offset, count));
        public override int Read(Span<byte> buffer) => Count(inner.Read(buffer));
        public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken) =>
            ReadAsyncCore(buffer.AsMemory(offset, count), cancellationToken).AsTask();
        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default) =>
            ReadAsyncCore(buffer, cancellationToken);
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();

        private async ValueTask<int> ReadAsyncCore(Memory<byte> buffer, CancellationToken cancellationToken)
        {
            var read = await inner.ReadAsync(buffer, cancellationToken).ConfigureAwait(false);
            return Count(read);
        }

        private int Count(int read)
        {
            if (read <= 0) return read;
            _read += read;
            if (_read > maxBytes) throw new EpgImportLimitExceededException("The expanded EPG feed size limit was exceeded.");
            return read;
        }

        protected override void Dispose(bool disposing)
        {
            base.Dispose(disposing);
        }
    }
}
