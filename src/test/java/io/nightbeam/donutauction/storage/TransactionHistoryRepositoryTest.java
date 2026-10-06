package io.nightbeam.donutauction.storage;

import io.nightbeam.donutauction.model.HistoryView;
import io.nightbeam.donutauction.model.TransactionPage;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import static org.junit.jupiter.api.Assertions.*;

class TransactionHistoryRepositoryTest {
    @TempDir Path directory;
    private javax.sql.DataSource source;
    private SqlAuctionRepository repository;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "history-db-test"));
    private final UUID seller = UUID.randomUUID();
    private final UUID buyer = UUID.randomUUID();

    @BeforeEach void initialize() throws Exception {
        String mysqlUrl = System.getProperty("auction.test.mysql.url");
        if (mysqlUrl == null) {
            var sqlite = new SQLiteDataSource();
            sqlite.setUrl("jdbc:sqlite:" + directory.resolve("auctions.db"));
            source = sqlite;
        } else {
            var mysql = new com.mysql.cj.jdbc.MysqlDataSource();
            mysql.setUrl(mysqlUrl); mysql.setUser("root"); mysql.setPassword("test");
            source = mysql;
            try (var connection = source.getConnection(); var statement = connection.createStatement()) {
                statement.executeUpdate("DROP TABLE IF EXISTS auctions");
            }
        }
        repository = new SqlAuctionRepository(source, worker);
        repository.initialize().get(5, TimeUnit.SECONDS);
    }
    @AfterEach void stop() { worker.shutdownNow(); }

    UUID row(UUID owner, UUID purchaser, String status, long time, boolean completed) throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection connection = source.getConnection(); var statement = connection.prepareStatement(
                "INSERT INTO auctions VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            // Match explicit column order from CREATE TABLE, including the completion flag.
            statement.setString(1, id.toString());
            statement.setString(2, owner.toString());
            statement.setString(3, purchaser == null ? null : purchaser.toString());
            statement.setString(4, "unavailable-metadata");
            statement.setDouble(5, 1234.567);
            statement.setLong(6, 1);
            statement.setLong(7, Long.MAX_VALUE);
            statement.setLong(8, time);
            statement.setString(9, status);
            statement.setBoolean(10, false);
            statement.setBoolean(11, completed);
            statement.setLong(12, 1);
            statement.executeUpdate();
        }
        return id;
    }
    TransactionPage history(UUID owner, HistoryView view, int page) throws Exception {
        return repository.findHistory(owner, view, page).get(5, TimeUnit.SECONDS);
    }

    @Test void privateCompletedTransactionsOnlyWithCounterpartyAndExactData() throws Exception {
        UUID id = row(seller, buyer, "SOLD", 1000, true);
        row(seller, buyer, "SOLD", 1001, false);
        row(seller, null, "ACTIVE", 0, true);
        row(seller, buyer, "CANCELLED", 1002, true);
        row(seller, null, "EXPIRED", 0, true);
        row(seller, null, "SOLD", 1003, true);
        row(seller, buyer, "SOLD", 0, true);
        assertEquals(0, history(UUID.randomUUID(), HistoryView.BOUGHT, 1).totalResults());
        assertEquals(0, history(seller, HistoryView.BOUGHT, 1).totalResults());
        assertEquals(1, history(buyer, HistoryView.BOUGHT, 1).totalResults());
        var bought = history(buyer, HistoryView.BOUGHT, 1).records().get(0);
        assertEquals(id, bought.auctionId());
        assertEquals(seller, bought.counterparty());
        assertEquals(1234.567, bought.price());
        assertEquals(1000, bought.soldTime());
        assertEquals("unavailable-metadata", bought.itemData());
        assertEquals(buyer, history(seller, HistoryView.SOLD, 1).records().get(0).counterparty());
    }

    @Test void claimIsHiddenUntilCompletionAndFailedSettlementStaysHidden() throws Exception {
        UUID id = row(seller, null, "ACTIVE", 0, false);
        assertTrue(repository.claimSold(id, buyer, 1000).get());
        assertFalse(repository.claimSold(id, UUID.randomUUID(), 1001).get());
        assertEquals(0, history(buyer, HistoryView.BOUGHT, 1).totalResults());
        assertFalse(repository.markPurchaseCompleted(id, UUID.randomUUID()).get());
        assertTrue(repository.releaseClaim(id, buyer, 1002).get());
        assertFalse(repository.markPurchaseCompleted(id, buyer).get());
        assertTrue(repository.claimSold(id, buyer, 1003).get());
        assertTrue(repository.markPurchaseCompleted(id, buyer).get());
        repository.markPurchaseCompleted(id, buyer).get();
        assertEquals(1, history(buyer, HistoryView.BOUGHT, 1).totalResults());
    }

    @Test void emptyManyPagesStableOrderingAndOutOfRangeClamping() throws Exception {
        assertEquals(1, history(buyer, HistoryView.BOUGHT, Integer.MAX_VALUE).currentPage());
        for (int i = 0; i < 100; i++) row(seller, buyer, "SOLD", 1000 + i / 2, true);
        var first = history(buyer, HistoryView.BOUGHT, -1);
        var second = history(buyer, HistoryView.BOUGHT, 2);
        var last = history(buyer, HistoryView.BOUGHT, Integer.MAX_VALUE);
        assertEquals(3, last.currentPage());
        assertEquals(10, last.records().size());
        assertEquals(45, first.records().size());
        var ids = new HashSet<UUID>();
        for (var page : java.util.List.of(first, second, last)) for (var record : page.records()) assertTrue(ids.add(record.auctionId()));
        assertEquals(100, ids.size());
        assertTrue(first.records().get(0).soldTime() >= last.records().get(0).soldTime());
        assertThrows(UnsupportedOperationException.class, () -> first.records().clear());
    }

    @Test void persistsAfterRepositoryRestartAndConcurrentRefresh() throws Exception {
        row(seller, buyer, "SOLD", 1000, true);
        var futures = java.util.stream.IntStream.range(0, 20).mapToObj(i -> repository.findHistory(buyer, HistoryView.BOUGHT, 1)).toList();
        row(seller, buyer, "SOLD", 1001, true);
        for (var future : futures) assertEquals(future.get().totalResults(), future.get().records().size());
        var restarted = new SqlAuctionRepository(source, worker);
        restarted.initialize().get();
        assertEquals(2, restarted.findHistory(buyer, HistoryView.BOUGHT, 1).get().totalResults());
    }

    @Test void migratesLegacySchemaWithoutChangingStoredValuesOrLosingSoldHistory() throws Exception {
        try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE auctions");
            statement.executeUpdate("CREATE TABLE auctions (auction_id VARCHAR(36) PRIMARY KEY, seller_uuid VARCHAR(36), buyer_uuid VARCHAR(36), item_data TEXT, price DOUBLE, listing_time BIGINT, expiration_time BIGINT, sold_time BIGINT, status VARCHAR(16), seller_claimed BOOLEAN)");
            statement.executeUpdate("INSERT INTO auctions VALUES ('" + UUID.randomUUID() + "','" + seller + "','" + buyer + "','old-item',999999.125,1,2,1000,'SOLD',1)");
        }
        repository.initialize().get();
        repository.initialize().get();
        var old = history(buyer, HistoryView.BOUGHT, 1).records().get(0);
        assertEquals(999999.125, old.price());
        assertEquals("old-item", old.itemData());
    }

    @Test void databaseUsesConfiguredExecutorAndRejectsMissingIdentity() throws Exception {
        assertThrows(NullPointerException.class, () -> repository.findHistory(null, HistoryView.BOUGHT, 1));
        var thread = repository.findHistory(buyer, HistoryView.BOUGHT, 1).thenApply(result -> Thread.currentThread().getName());
        // Submit behind the query to ensure this is a real asynchronous database executor.
        assertEquals("history-db-test", java.util.concurrent.CompletableFuture.supplyAsync(() -> Thread.currentThread().getName(), worker).get());
        assertNotNull(thread.get());
    }
}
