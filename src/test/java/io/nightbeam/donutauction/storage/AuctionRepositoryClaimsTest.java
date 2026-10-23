package io.nightbeam.donutauction.storage;

import io.nightbeam.donutauction.model.HistoryView;
import io.nightbeam.donutauction.model.SaleDetails;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 2.0 row claims on native SQLite, and on MySQL when -Dauction.test.mysql.url is set (CI). Rows hold
 * placeholder item data: every method here works on columns only, so no Bukkit server is needed.
 */
class AuctionRepositoryClaimsTest {

    @TempDir Path directory;
    private javax.sql.DataSource source;
    private SqlAuctionRepository repository;
    private final ExecutorService worker = Executors.newFixedThreadPool(4);
    private final UUID seller = UUID.randomUUID();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @BeforeEach void initialize() throws Exception {
        String mysqlUrl = System.getProperty("auction.test.mysql.url");
        if (mysqlUrl == null) {
            var sqlite = new SQLiteDataSource();
            sqlite.setUrl("jdbc:sqlite:" + directory.resolve("claims.db"));
            source = sqlite;
        } else {
            var mysql = new com.mysql.cj.jdbc.MysqlDataSource();
            mysql.setUrl(mysqlUrl); mysql.setUser("root"); mysql.setPassword("test");
            source = mysql;
            try (var connection = source.getConnection(); var statement = connection.createStatement()) {
                statement.executeUpdate("DROP TABLE IF EXISTS auctions");
                statement.executeUpdate("DROP TABLE IF EXISTS auction_meta");
            }
        }
        repository = new SqlAuctionRepository(source, worker);
        repository.initialize().get(10, TimeUnit.SECONDS);
    }

    @AfterEach void stop() { worker.shutdownNow(); }

    /** Inserts a row with the 2.0 columns; returns its ID. */
    private UUID row(String status, String type, long expiration, String material, int amount, double price) throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection connection = source.getConnection(); var statement = connection.prepareStatement("""
                INSERT INTO auctions(auction_id, seller_uuid, buyer_uuid, item_data, price, listing_time, expiration_time, sold_time,
                    status, seller_claimed, purchase_completed, updated_at, item_material, item_amount, listing_type, seller_name)
                VALUES (?, ?, NULL, 'placeholder', ?, 1, ?, 0, ?, FALSE, TRUE, 1, ?, ?, ?, 'Seller')
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, seller.toString());
            statement.setDouble(3, price);
            statement.setLong(4, expiration);
            statement.setString(5, status);
            statement.setString(6, material);
            statement.setInt(7, amount);
            statement.setString(8, type);
            statement.executeUpdate();
        }
        return id;
    }

    private String column(UUID id, String column) throws Exception {
        try (Connection connection = source.getConnection(); var statement = connection.prepareStatement(
                "SELECT " + column + " FROM auctions WHERE auction_id = ?")) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private static boolean truthy(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    @Test void bidsOnlyLandOnTheVersionTheBidderSaw() throws Exception {
        long future = System.currentTimeMillis() + 3_600_000L;
        UUID id = row("ACTIVE", "BID", future, "DIAMOND", 1, 100);
        long now = System.currentTimeMillis();
        assertTrue(repository.placeBid(id, 0, 100, alice, "Alice", future, now).get());
        // Bob read the row before Alice's bid landed: his bid is refused, not stacked.
        assertFalse(repository.placeBid(id, 0, 150, bob, "Bob", future, now + 1).get());
        assertTrue(repository.placeBid(id, 1, 150, bob, "Bob", future + 30_000, now + 2).get());
        assertEquals("2", column(id, "bid_count"));
        assertEquals(bob.toString(), column(id, "current_bidder"));
        assertEquals("Bob", column(id, "current_bidder_name"));
        assertEquals(String.valueOf(future + 30_000), column(id, "expiration_time"));
        // Buy It Now rows never take bids, and BIN purchases never claim auctions.
        UUID bin = row("ACTIVE", "BIN", future, "DIAMOND", 1, 100);
        assertFalse(repository.placeBid(bin, 0, 200, alice, "Alice", future, now).get());
        assertFalse(repository.claimSold(id, alice, now + 3).get());
    }

    @Test void concurrentBidsWithTheSameViewHaveOneWinner() throws Exception {
        long future = System.currentTimeMillis() + 3_600_000L;
        UUID id = row("ACTIVE", "BID", future, "DIAMOND", 1, 100);
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            UUID bidder = UUID.randomUUID();
            double amount = 200 + i;
            futures.add(pool.submit(() -> {
                start.await();
                if (repository.placeBid(id, 0, amount, bidder, "B", future, System.currentTimeMillis()).get()) wins.incrementAndGet();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future1 : futures) future1.get(20, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertEquals(1, wins.get());
        assertEquals("1", column(id, "bid_count"));
    }

    @Test void endedAuctionsAreWonOnceAndPaidOutOnce() throws Exception {
        long past = System.currentTimeMillis() - 1000;
        UUID id = row("ACTIVE", "BID", System.currentTimeMillis() + 60_000, "DIAMOND", 1, 100);
        // The bid lands while the auction runs and (for the test) moves its end into the past.
        assertTrue(repository.placeBid(id, 0, 250, alice, "Alice", past, System.currentTimeMillis() - 5000).get());
        long now = System.currentTimeMillis();
        // An auction with bids is won, never expired.
        assertFalse(repository.claimExpired(id, now, now).get());
        assertTrue(repository.claimWon(id, now, now).get());
        assertFalse(repository.claimWon(id, now, now).get());
        assertEquals("SOLD", column(id, "status"));
        assertEquals(alice.toString(), column(id, "buyer_uuid"));
        assertEquals("Alice", column(id, "buyer_name"));
        assertEquals(250.0, Double.parseDouble(column(id, "price")));
        assertFalse(truthy(column(id, "buyer_claimed")));
        assertFalse(truthy(column(id, "purchase_completed")));
        assertTrue(repository.completeWonSettlement(id, 12.5).get());
        assertFalse(repository.completeWonSettlement(id, 12.5).get());
        assertTrue(truthy(column(id, "purchase_completed")));
        assertEquals(1, repository.findHistory(alice, HistoryView.BOUGHT, 1).get().totalResults());
        var sold = repository.findHistory(seller, HistoryView.SOLD, 1).get().records().get(0);
        assertEquals(12.5, sold.tax());
        assertEquals("Alice", sold.counterpartyName());
        // The winner collects exactly once.
        assertTrue(repository.claimBuyerDelivery(id, alice, now).get());
        assertFalse(repository.claimBuyerDelivery(id, alice, now).get());
        assertFalse(repository.claimBuyerDelivery(id, bob, now).get());
    }

    @Test void expiredItemsAreReclaimedOrRelistedOnce() throws Exception {
        long past = System.currentTimeMillis() - 1000;
        UUID reclaimed = row("ACTIVE", "BIN", past, "DIAMOND", 1, 100);
        UUID relisted = row("ACTIVE", "BIN", past, "DIAMOND", 1, 100);
        long now = System.currentTimeMillis();
        assertTrue(repository.claimExpired(reclaimed, now, now).get());
        assertTrue(repository.claimExpired(relisted, now, now).get());
        assertFalse(repository.claimSellerReturn(reclaimed, alice, now).get());
        assertTrue(repository.claimSellerReturn(reclaimed, seller, now).get());
        assertFalse(repository.claimSellerReturn(reclaimed, seller, now).get());
        assertFalse(repository.relist(reclaimed, seller, 100, 0.05, now, now + 3_600_000, now).get());
        assertTrue(repository.relist(relisted, seller, 120, 0.05, now, now + 3_600_000, now).get());
        assertFalse(repository.relist(relisted, seller, 120, 0.05, now, now + 3_600_000, now).get());
        assertEquals("ACTIVE", column(relisted, "status"));
        assertEquals(120.0, Double.parseDouble(column(relisted, "price")));
        assertEquals(0.05, Double.parseDouble(column(relisted, "tax_rate")));
        assertFalse(repository.claimSellerReturn(relisted, seller, now).get());
    }

    @Test void cancellingRespectsBidsAndCollectionFlag() throws Exception {
        long future = System.currentTimeMillis() + 3_600_000L;
        UUID full = row("ACTIVE", "BIN", future, "STONE", 64, 100);
        long now = System.currentTimeMillis();
        assertTrue(repository.claimCancelled(full, now, false).get());
        assertFalse(truthy(column(full, "seller_claimed")));
        assertTrue(repository.claimSellerReturn(full, seller, now).get());

        UUID auction = row("ACTIVE", "BID", future, "STONE", 1, 100);
        assertTrue(repository.placeBid(auction, 0, 120, alice, "Alice", future, now).get());
        assertFalse(repository.claimCancelled(auction, now, true).get());
        assertFalse(repository.claimCancelledWithBids(auction, now, 0).get());
        assertTrue(repository.claimCancelledWithBids(auction, now, 1).get());
        assertEquals("CANCELLED", column(auction, "status"));
    }

    @Test void salesAreReportedOnceAndStatsUseCompletedSales() throws Exception {
        long future = System.currentTimeMillis() + 3_600_000L;
        UUID first = row("ACTIVE", "BIN", future, "DIAMOND", 4, 400);
        UUID second = row("ACTIVE", "BIN", future, "DIAMOND", 1, 150);
        UUID open = row("ACTIVE", "BIN", future, "DIAMOND", 1, 1);
        long now = System.currentTimeMillis();
        assertTrue(repository.claimSold(first, alice, now).get());
        assertTrue(repository.markPurchaseCompleted(first, alice, new SaleDetails(20, true, "Alice")).get());
        assertTrue(repository.claimSold(second, bob, now + 1).get());
        assertTrue(repository.markPurchaseCompleted(second, bob, new SaleDetails(0, false, "Bob")).get());
        assertTrue(repository.claimSold(open, bob, now + 2).get());

        var unreported = repository.findUnnotifiedSales(seller, 10).get();
        assertEquals(2, unreported.size());
        assertEquals("Bob", unreported.get(0).counterpartyName());
        assertEquals(2, repository.markSellerNotified(seller, List.of(first, second)).get());
        assertEquals(0, repository.findUnnotifiedSales(seller, 10).get().size());

        var market = repository.marketStats("DIAMOND", now - 1000).get();
        assertEquals(2, market.sales());
        assertEquals(125.0, market.averageUnit(), 1e-9);
        assertEquals(100.0, market.lowestUnit(), 1e-9);
        assertEquals(150.0, market.highestUnit(), 1e-9);
        assertEquals(150.0, market.lastUnit(), 1e-9);
        assertEquals(0, repository.marketStats("EMERALD", now - 1000).get().sales());

        var sellerStats = repository.playerStats(seller).get();
        assertEquals(2, sellerStats.sold());
        assertEquals(530.0, sellerStats.earned(), 1e-9);
        var bobStats = repository.playerStats(bob).get();
        assertEquals(1, bobStats.bought());
        assertEquals(150.0, bobStats.spent(), 1e-9);
        // Bob's item did not fit: it waits for him and is not purged.
        assertFalse(truthy(column(second, "buyer_claimed")));
        assertEquals(1, repository.purgeHistory(Long.MAX_VALUE).get());
        assertEquals("SOLD", column(second, "status"));
    }

    @Test void migrationFrom1xReportsNoOldSalesAndIsIdempotent() throws Exception {
        try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE auctions");
            statement.executeUpdate("DROP TABLE auction_meta");
            statement.executeUpdate("CREATE TABLE auctions (auction_id VARCHAR(36) PRIMARY KEY, seller_uuid VARCHAR(36) NOT NULL,"
                    + " buyer_uuid VARCHAR(36) NULL, item_data LONGTEXT NOT NULL, price DOUBLE NOT NULL, listing_time BIGINT NOT NULL,"
                    + " expiration_time BIGINT NOT NULL, sold_time BIGINT NOT NULL, status VARCHAR(16) NOT NULL,"
                    + " seller_claimed BOOLEAN NOT NULL DEFAULT FALSE, purchase_completed BOOLEAN NOT NULL DEFAULT TRUE,"
                    + " updated_at BIGINT NOT NULL DEFAULT 0)");
            statement.executeUpdate("INSERT INTO auctions VALUES ('" + UUID.randomUUID() + "','" + seller + "','" + alice
                    + "','old',10,1,2,3,'SOLD',FALSE,TRUE,3)");
            statement.executeUpdate("INSERT INTO auctions VALUES ('" + UUID.randomUUID() + "','" + seller + "',NULL,'old',10,1,2,0,'EXPIRED',FALSE,TRUE,3)");
        }
        repository.initialize().get(10, TimeUnit.SECONDS);
        repository.initialize().get(10, TimeUnit.SECONDS);
        assertEquals(0, repository.findUnnotifiedSales(seller, 10).get().size());
        try (Connection connection = source.getConnection()) {
            assertEquals(SqlAuctionRepository.SCHEMA_VERSION, repository.schemaVersion(connection));
            try (var statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT seller_claimed, buyer_claimed, listing_type, tax_rate FROM auctions WHERE status = 'EXPIRED'")) {
                assertTrue(result.next());
                assertFalse(result.getBoolean(1));
                assertTrue(result.getBoolean(2));
                assertEquals("BIN", result.getString(3));
                assertEquals(-1.0, result.getDouble(4));
            }
        }
        // Legacy rows have no item index yet; undecodable items are indexed as unknown, never retried forever.
        assertEquals(2, repository.backfillItemIndex(100).get());
        assertEquals(0, repository.backfillItemIndex(100).get());
    }

    @Test void unreadableItemsAreSkippedNotFatal() throws Exception {
        row("ACTIVE", "BIN", System.currentTimeMillis() + 60_000, "DIAMOND", 1, 100);
        assertEquals(0, repository.loadLive().get().size());
        assertEquals(1, repository.unreadableRows());
    }
}
