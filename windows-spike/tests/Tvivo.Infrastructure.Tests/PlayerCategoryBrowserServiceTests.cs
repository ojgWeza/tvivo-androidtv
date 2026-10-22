using Microsoft.Data.Sqlite;
using Tvivo.Core;
using Tvivo.Infrastructure;
using Xunit;

namespace Tvivo.Infrastructure.Tests;

public sealed class PlayerCategoryBrowserServiceTests
{
    [Fact]
    public void Folder_pages_stay_bounded_and_stably_ordered_with_100k_rows()
    {
        var directory = Path.Combine(Path.GetTempPath(), "tvivo-browser-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        var path = Path.Combine(directory, "catalog.sqlite");
        try
        {
            using var repository = new SqliteCatalogRepository(path);
            var account = new ProviderAccount("fixture", new ProviderEndpoint("http", "fixture.invalid", 0), "fixture");
            using (var db = new SqliteConnection($"Data Source={path}"))
            {
                db.Open();
                using var transaction = db.BeginTransaction();
                using (var category = db.CreateCommand())
                {
                    category.Transaction = transaction;
                    category.CommandText = "INSERT INTO categories(account_id,type,id,name,position) VALUES ('fixture','Movie','full','Full',2),('fixture','Movie','empty','Empty',1)";
                    category.ExecuteNonQuery();
                }
                using var insert = db.CreateCommand();
                insert.Transaction = transaction;
                insert.CommandText = "INSERT INTO items(account_id,type,id,category_id,title,title_sort,added_at) VALUES ('fixture','Movie',$id,'full','Same title','Same title',0)";
                var id = insert.Parameters.Add("$id", SqliteType.Text);
                for (var i = 0; i < 100_000; i++)
                {
                    id.Value = $"item-{i:000000}";
                    insert.ExecuteNonQuery();
                }
                transaction.Commit();
            }

            var service = new PlayerCategoryBrowserService(repository, null);
            Assert.Equal(new[] { "empty", "full" }, service.GetFolders(account, CatalogItemType.Movie).Select(folder => folder.Group.Id));
            Assert.Equal(100_000, service.GetFolders(account, CatalogItemType.Movie)[1].TotalCount);
            Assert.Equal(0, service.GetFolders(account, CatalogItemType.Movie)[0].TotalCount);
            Assert.Equal("item-000000", repository.GetCategoryChannelIds(account, CatalogItemType.Movie, "full")[0]);
            repository.SaveProgress(account, "movie", "item-000000", null, 0, null, finished: true);
            Assert.Equal(new[] { "item-000000" }, repository.GetFinishedMovieIds(account, "full"));
            var first = service.GetFolderPage(account, CatalogItemType.Movie, "full", 0, 50);
            var middle = service.GetFolderPage(account, CatalogItemType.Movie, "full", 50_000, 50);
            var last = service.GetFolderPage(account, CatalogItemType.Movie, "full", 99_950, 50);
            Assert.All(new[] { first, middle, last }, page => Assert.Equal(50, page.Items.Count));
            Assert.All(new[] { first, middle, last }, page => Assert.Equal(100_000, page.TotalCount));
            Assert.Equal("item-000000", first.Items[0].Id);
            Assert.Equal("item-050000", middle.Items[0].Id);
            Assert.Equal("item-099999", last.Items[^1].Id);
            Assert.Equal(middle.Items.Select(item => item.Id),
                service.GetFolderPage(account, CatalogItemType.Movie, "full", 50_000, 50).Items.Select(item => item.Id));
            Assert.Empty(service.GetFolderPage(account, CatalogItemType.Movie, "empty", 0, 50).Items);
            Assert.Empty(service.GetFolderPage(account, CatalogItemType.Movie, "unknown", 0, 50).Items);
        }
        finally { SqliteConnection.ClearAllPools(); Directory.Delete(directory, true); }
    }
}
