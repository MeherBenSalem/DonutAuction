package io.nightbeam.donutauction.storage;

import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.AuctionStatus;
import io.nightbeam.donutauction.model.Bid;
import io.nightbeam.donutauction.model.HistoryView;
import io.nightbeam.donutauction.model.ListingType;
import io.nightbeam.donutauction.model.MarketStats;
import io.nightbeam.donutauction.model.PlayerStats;
import io.nightbeam.donutauction.model.SaleDetails;
import io.nightbeam.donutauction.model.TransactionPage;
import io.nightbeam.donutauction.model.TransactionRecord;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.bukkit.inventory.ItemStack;

public final class SqlAuctionRepository implements AuctionRepository {

    /** Bumped when a release needs a one-time data migration (see {@link #migrate}). */
    static final int SCHEMA_VERSION = 2;

    private static final String LIVE = "status = 'ACTIVE' OR (status IN ('EXPIRED', 'CANCELLED') AND seller_claimed = FALSE)"
            + " OR (status = 'SOLD' AND buyer_claimed = FALSE AND buyer_uuid IS NOT NULL)";

    private final DataSource dataSource;
    private final Executor asyncExecutor;
    private final Logger logger;
    private final Set<String> unreadable = ConcurrentHashMap.newKeySet();

    public SqlAuctionRepository(DatabaseManager databaseManager, Executor asyncExecutor, Logger logger) {
        this(databaseManager.dataSource(), asyncExecutor, logger);
    }

    public SqlAuctionRepository(DataSource dataSource, Executor asyncExecutor) {
        this(dataSource, asyncExecutor, Logger.getLogger("DonutAuctionHouse"));
    }

    public SqlAuctionRepository(DataSource dataSource, Executor asyncExecutor, Logger logger) {
        this.dataSource = dataSource;
        this.asyncExecutor = asyncExecutor;
        this.logger = logger;
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS auctions (
                            auction_id VARCHAR(36) PRIMARY KEY,
                            seller_uuid VARCHAR(36) NOT NULL,
                            buyer_uuid VARCHAR(36) NULL,
                            item_data LONGTEXT NOT NULL,
                            price DOUBLE NOT NULL,
                            listing_time BIGINT NOT NULL,
                            expiration_time BIGINT NOT NULL,
                            sold_time BIGINT NOT NULL,
                            status VARCHAR(16) NOT NULL,
                            seller_claimed BOOLEAN NOT NULL DEFAULT FALSE,
                            purchase_completed BOOLEAN NOT NULL DEFAULT TRUE,
                            updated_at BIGINT NOT NULL DEFAULT 0
                        )
                        """);
                addColumnIfMissing(connection, "updated_at", "BIGINT NOT NULL DEFAULT 0");
                // Legacy SOLD rows retain their history. New claims explicitly clear this flag.
                addColumnIfMissing(connection, "purchase_completed", "BOOLEAN NOT NULL DEFAULT TRUE");
                // 2.0: price insight, sales tax, auctions with bids, stored names and the collection.
                addColumnIfMissing(connection, "item_material", "VARCHAR(64) NULL");
                addColumnIfMissing(connection, "item_amount", "INT NOT NULL DEFAULT 1");
                addColumnIfMissing(connection, "tax", "DOUBLE NOT NULL DEFAULT 0");
                addColumnIfMissing(connection, "listing_type", "VARCHAR(8) NOT NULL DEFAULT 'BIN'");
                addColumnIfMissing(connection, "current_bid", "DOUBLE NOT NULL DEFAULT 0");
                addColumnIfMissing(connection, "current_bidder", "VARCHAR(36) NULL");
                addColumnIfMissing(connection, "current_bidder_name", "VARCHAR(64) NULL");
                addColumnIfMissing(connection, "bid_count", "INT NOT NULL DEFAULT 0");
                addColumnIfMissing(connection, "seller_name", "VARCHAR(64) NULL");
                addColumnIfMissing(connection, "buyer_name", "VARCHAR(64) NULL");
                // Legacy SOLD rows were delivered at purchase time.
                addColumnIfMissing(connection, "buyer_claimed", "BOOLEAN NOT NULL DEFAULT TRUE");
                // The seller's tax rate when listing; negative means the default rate at sale time.
                addColumnIfMissing(connection, "tax_rate", "DOUBLE NOT NULL DEFAULT -1");
                ensureIndex(connection, "idx_auctions_status_expiration", "status, expiration_time");
                ensureIndex(connection, "idx_auctions_seller_status", "seller_uuid, status");
                ensureIndex(connection, "idx_auctions_updated_at", "updated_at");
                ensureIndex(connection, "idx_auctions_buyer_history", "buyer_uuid, status, purchase_completed, sold_time");
                ensureIndex(connection, "idx_auctions_seller_history", "seller_uuid, status, purchase_completed, sold_time");
                ensureIndex(connection, "idx_auctions_market", "item_material, status, sold_time");
                migrate(connection);
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        }, asyncExecutor);
    }

    private void migrate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS auction_meta (meta_key VARCHAR(64) PRIMARY KEY, meta_value VARCHAR(255) NOT NULL)");
        }
        int version = schemaVersion(connection);
        if (version < 2) {
            // 2.0 uses seller_claimed on sold rows to mean "the seller was told". Sales made before the
            // upgrade were already visible in the old Your Items menu, so they are not reported again.
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE auctions SET seller_claimed = TRUE WHERE status = 'SOLD'");
            }
        }
        if (version < SCHEMA_VERSION) {
            try (PreparedStatement update = connection.prepareStatement("UPDATE auction_meta SET meta_value = ? WHERE meta_key = 'schema_version'")) {
                update.setString(1, String.valueOf(SCHEMA_VERSION));
                if (update.executeUpdate() == 0) {
                    try (PreparedStatement insert = connection.prepareStatement("INSERT INTO auction_meta(meta_key, meta_value) VALUES('schema_version', ?)")) {
                        insert.setString(1, String.valueOf(SCHEMA_VERSION));
                        insert.executeUpdate();
                    } catch (SQLException exception) {
                        // Another server migrated at the same moment.
                        if (schemaVersion(connection) < SCHEMA_VERSION) throw exception;
                    }
                }
            }
        }
    }

    int schemaVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT meta_value FROM auction_meta WHERE meta_key = 'schema_version'")) {
            if (!result.next()) return 1;
            try {
                return Integer.parseInt(result.getString(1).trim());
            } catch (NumberFormatException exception) {
                return 1;
            }
        }
    }

    private void addColumnIfMissing(Connection connection, String column, String definition) throws SQLException {
        if (hasColumn(connection, column)) return;
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE auctions ADD COLUMN " + column + " " + definition);
        } catch (SQLException exception) {
            // Another node may migrate concurrently. Other failures must stop initialization.
            if (!hasColumn(connection, column)) throw exception;
        }
    }

    private boolean hasColumn(Connection connection, String column) throws SQLException {
        try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, "auctions", column)) {
            return columns.next();
        }
    }

    private void ensureIndex(Connection connection, String name, String columns) throws SQLException {
        if (hasIndex(connection, name)) return;
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE INDEX " + name + " ON auctions(" + columns + ")");
        } catch (SQLException exception) {
            if (!hasIndex(connection, name)) throw exception;
        }
    }

    private boolean hasIndex(Connection connection, String name) throws SQLException {
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, "auctions", false, false)) {
            while (indexes.next()) if (name.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) return true;
            return false;
        }
    }

    @Override
    public CompletableFuture<List<AuctionListing>> loadAll() {
        return CompletableFuture.supplyAsync(() -> queryList("SELECT * FROM auctions", statement -> {
        }), asyncExecutor);
    }

    @Override
    public CompletableFuture<List<AuctionListing>> loadLive() {
        return CompletableFuture.supplyAsync(() -> queryList("SELECT * FROM auctions WHERE " + LIVE, statement -> {
        }), asyncExecutor);
    }

    @Override
    public CompletableFuture<List<AuctionListing>> loadUpdatedSince(long updatedAtExclusive) {
        return CompletableFuture.supplyAsync(() -> queryList(
                "SELECT * FROM auctions WHERE updated_at > ? ORDER BY updated_at ASC",
                statement -> statement.setLong(1, updatedAtExclusive)
        ), asyncExecutor);
    }

    @Override
    public CompletableFuture<Void> save(AuctionListing listing) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         INSERT INTO auctions(auction_id, seller_uuid, buyer_uuid, item_data, price, listing_time, expiration_time,
                             sold_time, status, seller_claimed, updated_at, item_material, item_amount, tax, listing_type,
                             current_bid, current_bidder, current_bidder_name, bid_count, seller_name, buyer_claimed, tax_rate)
                         VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                         """)) {
                statement.setString(1, listing.auctionId().toString());
                statement.setString(2, listing.seller().toString());
                statement.setString(3, listing.buyer() == null ? null : listing.buyer().toString());
                statement.setString(4, ItemStackSerializer.serialize(listing.item()));
                statement.setDouble(5, listing.price());
                statement.setLong(6, listing.listingTime());
                statement.setLong(7, listing.expirationTime());
                statement.setLong(8, listing.soldTime());
                statement.setString(9, listing.status().name());
                statement.setBoolean(10, listing.sellerClaimed());
                statement.setLong(11, listing.updatedAt());
                statement.setString(12, material(listing.item()));
                statement.setInt(13, Math.max(1, listing.item().getAmount()));
                statement.setDouble(14, listing.tax());
                statement.setString(15, listing.type().name());
                statement.setDouble(16, listing.bid().amount());
                statement.setString(17, listing.bid().bidder() == null ? null : listing.bid().bidder().toString());
                statement.setString(18, listing.bid().bidderName());
                statement.setInt(19, listing.bid().count());
                statement.setString(20, listing.sellerName());
                statement.setBoolean(21, listing.buyerClaimed());
                statement.setDouble(22, listing.taxRate());
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<Void> update(AuctionListing listing) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         UPDATE auctions SET seller_uuid = ?, buyer_uuid = ?, item_data = ?, price = ?, listing_time = ?,
                             expiration_time = ?, sold_time = ?, status = ?, seller_claimed = ?, updated_at = ?, item_material = ?,
                             item_amount = ?, tax = ?, listing_type = ?, current_bid = ?, current_bidder = ?, current_bidder_name = ?,
                             bid_count = ?, seller_name = ?, buyer_claimed = ?, tax_rate = ?
                         WHERE auction_id = ?
                         """)) {
                statement.setString(1, listing.seller().toString());
                statement.setString(2, listing.buyer() == null ? null : listing.buyer().toString());
                statement.setString(3, ItemStackSerializer.serialize(listing.item()));
                statement.setDouble(4, listing.price());
                statement.setLong(5, listing.listingTime());
                statement.setLong(6, listing.expirationTime());
                statement.setLong(7, listing.soldTime());
                statement.setString(8, listing.status().name());
                statement.setBoolean(9, listing.sellerClaimed());
                statement.setLong(10, listing.updatedAt());
                statement.setString(11, material(listing.item()));
                statement.setInt(12, Math.max(1, listing.item().getAmount()));
                statement.setDouble(13, listing.tax());
                statement.setString(14, listing.type().name());
                statement.setDouble(15, listing.bid().amount());
                statement.setString(16, listing.bid().bidder() == null ? null : listing.bid().bidder().toString());
                statement.setString(17, listing.bid().bidderName());
                statement.setInt(18, listing.bid().count());
                statement.setString(19, listing.sellerName());
                statement.setBoolean(20, listing.buyerClaimed());
                statement.setDouble(21, listing.taxRate());
                statement.setString(22, listing.auctionId().toString());
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<Boolean> claimSold(UUID auctionId, UUID buyerId, long soldTime) {
        return claim("""
                        UPDATE auctions SET buyer_uuid = ?, sold_time = ?, status = 'SOLD', seller_claimed = FALSE, updated_at = ?,
                            purchase_completed = FALSE, buyer_claimed = TRUE, buyer_name = NULL
                        WHERE auction_id = ? AND status = 'ACTIVE' AND listing_type <> 'BID' AND expiration_time > ?
                        """,
                statement -> {
                    statement.setString(1, buyerId.toString());
                    statement.setLong(2, soldTime);
                    statement.setLong(3, soldTime);
                    statement.setString(4, auctionId.toString());
                    statement.setLong(5, soldTime);
                });
    }

    @Override
    public CompletableFuture<Boolean> releaseClaim(UUID auctionId, UUID buyerId, long updatedAt) {
        return claim("""
                        UPDATE auctions SET buyer_uuid = NULL, buyer_name = NULL, sold_time = 0, status = 'ACTIVE', updated_at = ?,
                            purchase_completed = FALSE
                        WHERE auction_id = ? AND status = 'SOLD' AND buyer_uuid = ? AND purchase_completed = FALSE
                            AND listing_type <> 'BID'
                        """,
                statement -> {
                    statement.setLong(1, updatedAt);
                    statement.setString(2, auctionId.toString());
                    statement.setString(3, buyerId.toString());
                });
    }

    @Override
    public CompletableFuture<Boolean> markPurchaseCompleted(UUID auctionId, UUID buyerId, SaleDetails details) {
        SaleDetails sale = details == null ? SaleDetails.DELIVERED : details;
        return claim("""
                        UPDATE auctions SET purchase_completed = TRUE, tax = ?, buyer_claimed = ?, buyer_name = COALESCE(?, buyer_name)
                        WHERE auction_id = ? AND buyer_uuid = ? AND status = 'SOLD'
                        """,
                statement -> {
                    statement.setDouble(1, sale.tax());
                    statement.setBoolean(2, sale.buyerClaimed());
                    statement.setString(3, sale.buyerName());
                    statement.setString(4, auctionId.toString());
                    statement.setString(5, buyerId.toString());
                });
    }

    @Override
    public CompletableFuture<Boolean> claimCancelled(UUID auctionId, long updatedAt, boolean sellerClaimed) {
        return claim("""
                        UPDATE auctions SET status = 'CANCELLED', seller_claimed = ?, updated_at = ?
                        WHERE auction_id = ? AND status = 'ACTIVE' AND (listing_type <> 'BID' OR bid_count = 0)
                        """,
                statement -> {
                    statement.setBoolean(1, sellerClaimed);
                    statement.setLong(2, updatedAt);
                    statement.setString(3, auctionId.toString());
                });
    }

    @Override
    public CompletableFuture<Boolean> claimCancelledWithBids(UUID auctionId, long updatedAt, int expectedBidCount) {
        return claim("""
                        UPDATE auctions SET status = 'CANCELLED', seller_claimed = FALSE, updated_at = ?
                        WHERE auction_id = ? AND status = 'ACTIVE' AND bid_count = ?
                        """,
                statement -> {
                    statement.setLong(1, updatedAt);
                    statement.setString(2, auctionId.toString());
                    statement.setInt(3, expectedBidCount);
                });
    }

    @Override
    public CompletableFuture<Boolean> claimExpired(UUID auctionId, long cutoffTime, long updatedAt) {
        return claim("""
                        UPDATE auctions SET status = 'EXPIRED', updated_at = ?
                        WHERE auction_id = ? AND status = 'ACTIVE' AND expiration_time <= ? AND (listing_type <> 'BID' OR bid_count = 0)
                        """,
                statement -> {
                    statement.setLong(1, updatedAt);
                    statement.setString(2, auctionId.toString());
                    statement.setLong(3, cutoffTime);
                });
    }

    @Override
    public CompletableFuture<Boolean> claimSellerReturn(UUID auctionId, UUID sellerId, long updatedAt) {
        return claim("""
                        UPDATE auctions SET seller_claimed = TRUE, updated_at = ?
                        WHERE auction_id = ? AND seller_uuid = ? AND status IN ('EXPIRED', 'CANCELLED') AND seller_claimed = FALSE
                        """,
                statement -> {
                    statement.setLong(1, updatedAt);
                    statement.setString(2, auctionId.toString());
                    statement.setString(3, sellerId.toString());
                });
    }

    @Override
    public CompletableFuture<Boolean> claimBuyerDelivery(UUID auctionId, UUID buyerId, long updatedAt) {
        return claim("""
                        UPDATE auctions SET buyer_claimed = TRUE, updated_at = ?
                        WHERE auction_id = ? AND buyer_uuid = ? AND status = 'SOLD' AND buyer_claimed = FALSE
                        """,
                statement -> {
                    statement.setLong(1, updatedAt);
                    statement.setString(2, auctionId.toString());
                    statement.setString(3, buyerId.toString());
                });
    }

    @Override
    public CompletableFuture<Boolean> relist(UUID auctionId, UUID sellerId, double price, double taxRate, long listingTime,
                                             long expirationTime, long updatedAt) {
        return claim("""
                        UPDATE auctions SET status = 'ACTIVE', price = ?, tax_rate = ?, listing_time = ?, expiration_time = ?,
                            buyer_uuid = NULL, buyer_name = NULL, sold_time = 0, seller_claimed = FALSE, current_bid = 0,
                            current_bidder = NULL, current_bidder_name = NULL, bid_count = 0, tax = 0, purchase_completed = TRUE,
                            buyer_claimed = TRUE, updated_at = ?
                        WHERE auction_id = ? AND seller_uuid = ? AND status IN ('EXPIRED', 'CANCELLED') AND seller_claimed = FALSE
                        """,
                statement -> {
                    statement.setDouble(1, price);
                    statement.setDouble(2, taxRate);
                    statement.setLong(3, listingTime);
                    statement.setLong(4, expirationTime);
                    statement.setLong(5, updatedAt);
                    statement.setString(6, auctionId.toString());
                    statement.setString(7, sellerId.toString());
                });
    }

    @Override
    public CompletableFuture<Boolean> placeBid(UUID auctionId, int expectedBidCount, double amount, UUID bidder, String bidderName,
                                               long expirationTime, long updatedAt) {
        return claim("""
                        UPDATE auctions SET current_bid = ?, current_bidder = ?, current_bidder_name = ?, bid_count = bid_count + 1,
                            expiration_time = ?, updated_at = ?
                        WHERE auction_id = ? AND status = 'ACTIVE' AND listing_type = 'BID' AND expiration_time > ? AND bid_count = ?
                        """,
                statement -> {
                    statement.setDouble(1, amount);
                    statement.setString(2, bidder.toString());
                    statement.setString(3, bidderName);
                    statement.setLong(4, expirationTime);
                    statement.setLong(5, updatedAt);
                    statement.setString(6, auctionId.toString());
                    statement.setLong(7, updatedAt);
                    statement.setInt(8, expectedBidCount);
                });
    }

    @Override
    public CompletableFuture<Boolean> claimWon(UUID auctionId, long cutoffTime, long updatedAt) {
        return claim("""
                        UPDATE auctions SET status = 'SOLD', buyer_uuid = current_bidder, buyer_name = current_bidder_name,
                            price = current_bid, sold_time = ?, seller_claimed = FALSE, buyer_claimed = FALSE,
                            purchase_completed = FALSE, updated_at = ?
                        WHERE auction_id = ? AND status = 'ACTIVE' AND listing_type = 'BID' AND bid_count > 0
                            AND current_bidder IS NOT NULL AND expiration_time <= ?
                        """,
                statement -> {
                    statement.setLong(1, updatedAt);
                    statement.setLong(2, updatedAt);
                    statement.setString(3, auctionId.toString());
                    statement.setLong(4, cutoffTime);
                });
    }

    @Override
    public CompletableFuture<Boolean> completeWonSettlement(UUID auctionId, double tax) {
        return claim("""
                        UPDATE auctions SET purchase_completed = TRUE, tax = ?
                        WHERE auction_id = ? AND status = 'SOLD' AND listing_type = 'BID' AND purchase_completed = FALSE
                        """,
                statement -> {
                    statement.setDouble(1, tax);
                    statement.setString(2, auctionId.toString());
                });
    }

    @Override
    public CompletableFuture<List<AuctionListing>> findUnsettledWins(long claimedBefore) {
        return CompletableFuture.supplyAsync(() -> queryList(
                "SELECT * FROM auctions WHERE status = 'SOLD' AND listing_type = 'BID' AND purchase_completed = FALSE AND sold_time <= ?",
                statement -> statement.setLong(1, claimedBefore)), asyncExecutor);
    }

    @Override
    public CompletableFuture<Optional<AuctionListing>> findById(UUID auctionId) {
        return CompletableFuture.supplyAsync(() -> queryList("SELECT * FROM auctions WHERE auction_id = ?",
                statement -> statement.setString(1, auctionId.toString())).stream().findFirst(), asyncExecutor);
    }

    @Override
    public CompletableFuture<List<AuctionListing>> findBySeller(UUID sellerId) {
        return CompletableFuture.supplyAsync(() -> queryList(
                "SELECT * FROM auctions WHERE seller_uuid = ? ORDER BY listing_time DESC",
                statement -> statement.setString(1, sellerId.toString())
        ), asyncExecutor);
    }

    @Override
    public CompletableFuture<List<TransactionRecord>> findUnnotifiedSales(UUID sellerId, int limit) {
        Objects.requireNonNull(sellerId, "sellerId");
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         SELECT auction_id, item_data, price, sold_time, buyer_uuid AS counterparty, buyer_name AS counterparty_name,
                             tax, listing_type
                         FROM auctions WHERE seller_uuid = ? AND status = 'SOLD' AND purchase_completed = TRUE AND seller_claimed = FALSE
                         ORDER BY sold_time DESC LIMIT ?
                         """)) {
                statement.setString(1, sellerId.toString());
                statement.setInt(2, Math.max(1, limit));
                try (ResultSet result = statement.executeQuery()) {
                    List<TransactionRecord> records = new ArrayList<>();
                    while (result.next()) records.add(mapRecord(result));
                    return records;
                }
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<Integer> markSellerNotified(UUID sellerId, List<UUID> auctionIds) {
        if (auctionIds.isEmpty()) return CompletableFuture.completedFuture(0);
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "UPDATE auctions SET seller_claimed = TRUE WHERE auction_id = ? AND seller_uuid = ? AND status = 'SOLD'")) {
                for (UUID auctionId : auctionIds) {
                    statement.setString(1, auctionId.toString());
                    statement.setString(2, sellerId.toString());
                    statement.addBatch();
                }
                int total = 0;
                for (int count : statement.executeBatch()) total += Math.max(0, count);
                return total;
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<MarketStats> marketStats(String material, long since) {
        Objects.requireNonNull(material, "material");
        return CompletableFuture.supplyAsync(() -> {
            String where = " FROM auctions WHERE item_material = ? AND status = 'SOLD' AND purchase_completed = TRUE"
                    + " AND sold_time >= ? AND item_amount > 0";
            try (Connection connection = dataSource.getConnection()) {
                long sales;
                double average;
                double lowest;
                double highest;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*), AVG(price / item_amount), MIN(price / item_amount), MAX(price / item_amount)" + where)) {
                    statement.setString(1, material);
                    statement.setLong(2, since);
                    try (ResultSet result = statement.executeQuery()) {
                        result.next();
                        sales = result.getLong(1);
                        average = result.getDouble(2);
                        lowest = result.getDouble(3);
                        highest = result.getDouble(4);
                    }
                }
                if (sales == 0) return MarketStats.empty(material, since);
                double last = 0.0D;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT price, item_amount" + where + " ORDER BY sold_time DESC LIMIT 1")) {
                    statement.setString(1, material);
                    statement.setLong(2, since);
                    try (ResultSet result = statement.executeQuery()) {
                        if (result.next()) last = result.getDouble(1) / Math.max(1, result.getInt(2));
                    }
                }
                return new MarketStats(material, sales, average, lowest, highest, last, since);
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<PlayerStats> playerStats(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                long sold;
                double earned;
                long bought;
                double spent;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*), COALESCE(SUM(price - tax), 0) FROM auctions WHERE seller_uuid = ? AND status = 'SOLD' AND purchase_completed = TRUE")) {
                    statement.setString(1, playerId.toString());
                    try (ResultSet result = statement.executeQuery()) {
                        result.next();
                        sold = result.getLong(1);
                        earned = result.getDouble(2);
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*), COALESCE(SUM(price), 0) FROM auctions WHERE buyer_uuid = ? AND status = 'SOLD' AND purchase_completed = TRUE")) {
                    statement.setString(1, playerId.toString());
                    try (ResultSet result = statement.executeQuery()) {
                        result.next();
                        bought = result.getLong(1);
                        spent = result.getDouble(2);
                    }
                }
                return new PlayerStats(sold, earned, bought, spent);
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<Integer> backfillItemIndex(int batchSize) {
        return CompletableFuture.supplyAsync(() -> {
            List<String[]> rows = new ArrayList<>();
            try (Connection connection = dataSource.getConnection()) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT auction_id, item_data FROM auctions WHERE item_material IS NULL LIMIT ?")) {
                    statement.setInt(1, Math.max(1, batchSize));
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) rows.add(new String[]{result.getString(1), result.getString(2)});
                    }
                }
                if (rows.isEmpty()) return 0;
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE auctions SET item_material = ?, item_amount = ? WHERE auction_id = ?")) {
                    for (String[] row : rows) {
                        String material = "";
                        int amount = 1;
                        try {
                            ItemStack item = ItemStackSerializer.deserialize(row[1]);
                            material = material(item);
                            amount = Math.max(1, item.getAmount());
                        } catch (RuntimeException | LinkageError ignored) {
                            // Unreadable items are indexed as unknown so they are not retried forever.
                        }
                        update.setString(1, material);
                        update.setInt(2, amount);
                        update.setString(3, row[0]);
                        update.addBatch();
                    }
                    update.executeBatch();
                }
                return rows.size();
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<Integer> purgeHistory(long olderThan) {
        return CompletableFuture.supplyAsync(() -> executeUpdate("""
                        DELETE FROM auctions WHERE updated_at < ? AND (
                            (status = 'SOLD' AND purchase_completed = TRUE AND buyer_claimed = TRUE)
                            OR (status IN ('EXPIRED', 'CANCELLED') AND seller_claimed = TRUE))
                        """,
                statement -> statement.setLong(1, olderThan)), asyncExecutor);
    }

    @Override
    public CompletableFuture<TransactionPage> findHistory(UUID viewer, HistoryView view, int page) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(view, "view");
        return CompletableFuture.supplyAsync(() -> {
            String ownerColumn = view == HistoryView.BOUGHT ? "buyer_uuid" : "seller_uuid";
            String otherColumn = view == HistoryView.BOUGHT ? "seller_uuid" : "buyer_uuid";
            String otherName = view == HistoryView.BOUGHT ? "seller_name" : "buyer_name";
            String where = " WHERE " + ownerColumn + " = ? AND status = 'SOLD' AND purchase_completed = TRUE AND sold_time > 0 AND buyer_uuid IS NOT NULL";
            try (Connection connection = dataSource.getConnection()) {
                // A consistent snapshot prevents count/page disagreement during new purchases.
                if (connection.getMetaData().supportsTransactionIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ)) {
                    connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                }
                connection.setAutoCommit(false);
                long count;
                try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM auctions" + where)) {
                    statement.setString(1, viewer.toString());
                    try (ResultSet result = statement.executeQuery()) { result.next(); count = result.getLong(1); }
                }
                int pages = (int) Math.max(1, Math.min(Integer.MAX_VALUE, (count + 44) / 45));
                int current = Math.max(1, Math.min(pages, page));
                List<TransactionRecord> records = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT auction_id, item_data, price, sold_time, " + otherColumn + " AS counterparty, " + otherName
                                + " AS counterparty_name, tax, listing_type FROM auctions" + where
                                + " ORDER BY sold_time DESC, auction_id DESC LIMIT 45 OFFSET ?")) {
                    statement.setString(1, viewer.toString());
                    statement.setLong(2, (long) (current - 1) * 45);
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) records.add(mapRecord(result));
                    }
                }
                connection.commit();
                return new TransactionPage(viewer, view, records, current, pages, count);
            } catch (SQLException exception) { throw new CompletionException(exception); }
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<List<AuctionListing>> findExpiredActive(long cutoffTime) {
        return CompletableFuture.supplyAsync(() -> queryList(
                "SELECT * FROM auctions WHERE status = 'ACTIVE' AND expiration_time <= ?",
                statement -> statement.setLong(1, cutoffTime)
        ), asyncExecutor);
    }

    @Override
    public int unreadableRows() {
        return unreadable.size();
    }

    private CompletableFuture<Boolean> claim(String sql, SqlBinder binder) {
        return CompletableFuture.supplyAsync(() -> executeUpdate(sql, binder) == 1, asyncExecutor);
    }

    private List<AuctionListing> queryList(String sql, SqlBinder binder) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AuctionListing> listings = new ArrayList<>();
                while (resultSet.next()) {
                    String auctionId = resultSet.getString("auction_id");
                    try {
                        listings.add(mapListing(resultSet));
                    } catch (RuntimeException | LinkageError exception) {
                        // One unreadable item (for example written by a newer Minecraft version) must not
                        // hide every other listing. The row stays in the database untouched.
                        if (unreadable.add(auctionId)) {
                            logger.warning("Skipping auction " + auctionId + ": its item could not be read on this server ("
                                    + exception.getMessage() + "). The row is kept unchanged.");
                        }
                    }
                }
                return listings;
            }
        } catch (SQLException exception) {
            throw new CompletionException(exception);
        }
    }

    private int executeUpdate(String sql, SqlBinder binder) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw new CompletionException(exception);
        }
    }

    private static String material(ItemStack item) {
        try {
            return item.getType().name();
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static TransactionRecord mapRecord(ResultSet result) throws SQLException {
        String other = result.getString("counterparty");
        return new TransactionRecord(UUID.fromString(result.getString("auction_id")), result.getString("item_data"),
                result.getDouble("price"), result.getLong("sold_time"), other == null ? null : UUID.fromString(other),
                result.getString("counterparty_name"), result.getDouble("tax"), ListingType.parse(result.getString("listing_type")));
    }

    private AuctionListing mapListing(ResultSet resultSet) throws SQLException {
        String buyerId = resultSet.getString("buyer_uuid");
        String bidder = resultSet.getString("current_bidder");
        Bid bid = bidder == null ? Bid.NONE : new Bid(resultSet.getDouble("current_bid"), UUID.fromString(bidder),
                resultSet.getString("current_bidder_name"), resultSet.getInt("bid_count"));
        return new AuctionListing(
                UUID.fromString(resultSet.getString("auction_id")),
                ItemStackSerializer.deserialize(resultSet.getString("item_data")),
                UUID.fromString(resultSet.getString("seller_uuid")),
                resultSet.getDouble("price"),
                resultSet.getLong("listing_time"),
                resultSet.getLong("expiration_time"),
                AuctionStatus.valueOf(resultSet.getString("status")),
                buyerId == null ? null : UUID.fromString(buyerId),
                resultSet.getLong("sold_time"),
                resultSet.getBoolean("seller_claimed"),
                resultSet.getLong("updated_at"),
                ListingType.parse(resultSet.getString("listing_type")),
                bid,
                resultSet.getString("seller_name"),
                resultSet.getDouble("tax"),
                resultSet.getBoolean("buyer_claimed"),
                resultSet.getDouble("tax_rate")
        );
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
