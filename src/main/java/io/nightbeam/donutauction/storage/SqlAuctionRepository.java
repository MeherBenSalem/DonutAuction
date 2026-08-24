package io.nightbeam.donutauction.storage;

import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.AuctionStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import javax.sql.DataSource;

public final class SqlAuctionRepository implements AuctionRepository {

    private final DataSource dataSource;
    private final Executor asyncExecutor;

    public SqlAuctionRepository(DatabaseManager databaseManager, Executor asyncExecutor) {
        this(databaseManager.dataSource(), asyncExecutor);
    }

    public SqlAuctionRepository(DataSource dataSource, Executor asyncExecutor) {
        this.dataSource = dataSource;
        this.asyncExecutor = asyncExecutor;
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
                            updated_at BIGINT NOT NULL DEFAULT 0
                        )
                        """);
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_auctions_status_expiration ON auctions(status, expiration_time)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_auctions_seller_status ON auctions(seller_uuid, status)");
                addColumnIfMissing(statement, "updated_at");
                try {
                    statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_auctions_updated_at ON auctions(updated_at)");
                } catch (SQLException ignored) {
                    // Older MySQL without IF NOT EXISTS on indexes; table still works.
                }
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        }, asyncExecutor);
    }

    private void addColumnIfMissing(Statement statement, String column) {
        try {
            statement.executeUpdate("ALTER TABLE auctions ADD COLUMN " + column + " BIGINT NOT NULL DEFAULT 0");
        } catch (SQLException ignored) {
            // Column already exists.
        }
    }

    @Override
    public CompletableFuture<List<AuctionListing>> loadAll() {
        return CompletableFuture.supplyAsync(() -> queryList("SELECT * FROM auctions", statement -> {
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
                         INSERT INTO auctions(auction_id, seller_uuid, buyer_uuid, item_data, price, listing_time, expiration_time, sold_time, status, seller_claimed, updated_at)
                         VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                         """)) {
                bindListing(statement, listing);
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
                         UPDATE auctions SET seller_uuid = ?, buyer_uuid = ?, item_data = ?, price = ?, listing_time = ?, expiration_time = ?, sold_time = ?, status = ?, seller_claimed = ?, updated_at = ?
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
                statement.setString(11, listing.auctionId().toString());
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<Boolean> claimSold(UUID auctionId, UUID buyerId, long soldTime) {
        return CompletableFuture.supplyAsync(() -> executeUpdate("""
                        UPDATE auctions SET buyer_uuid = ?, sold_time = ?, status = ?, seller_claimed = 0, updated_at = ?
                        WHERE auction_id = ? AND status = ? AND expiration_time > ?
                        """,
                statement -> {
                    statement.setString(1, buyerId.toString());
                    statement.setLong(2, soldTime);
                    statement.setString(3, AuctionStatus.SOLD.name());
                    statement.setLong(4, soldTime);
                    statement.setString(5, auctionId.toString());
                    statement.setString(6, AuctionStatus.ACTIVE.name());
                    statement.setLong(7, soldTime);
                }
        ) == 1, asyncExecutor);
    }

    @Override
    public CompletableFuture<Boolean> releaseClaim(UUID auctionId, UUID buyerId, long updatedAt) {
        return CompletableFuture.supplyAsync(() -> executeUpdate("""
                        UPDATE auctions SET buyer_uuid = NULL, sold_time = 0, status = ?, updated_at = ?
                        WHERE auction_id = ? AND status = ? AND buyer_uuid = ?
                        """,
                statement -> {
                    statement.setString(1, AuctionStatus.ACTIVE.name());
                    statement.setLong(2, updatedAt);
                    statement.setString(3, auctionId.toString());
                    statement.setString(4, AuctionStatus.SOLD.name());
                    statement.setString(5, buyerId.toString());
                }
        ) == 1, asyncExecutor);
    }

    @Override
    public CompletableFuture<Boolean> claimCancelled(UUID auctionId, long updatedAt) {
        return CompletableFuture.supplyAsync(() -> executeUpdate("""
                        UPDATE auctions SET status = ?, seller_claimed = TRUE, updated_at = ?
                        WHERE auction_id = ? AND status = ?
                        """,
                statement -> {
                    statement.setString(1, AuctionStatus.CANCELLED.name());
                    statement.setLong(2, updatedAt);
                    statement.setString(3, auctionId.toString());
                    statement.setString(4, AuctionStatus.ACTIVE.name());
                }
        ) == 1, asyncExecutor);
    }

    @Override
    public CompletableFuture<Boolean> claimExpired(UUID auctionId, long cutoffTime, long updatedAt) {
        return CompletableFuture.supplyAsync(() -> executeUpdate("""
                        UPDATE auctions SET status = ?, updated_at = ?
                        WHERE auction_id = ? AND status = ? AND expiration_time <= ?
                        """,
                statement -> {
                    statement.setString(1, AuctionStatus.EXPIRED.name());
                    statement.setLong(2, updatedAt);
                    statement.setString(3, auctionId.toString());
                    statement.setString(4, AuctionStatus.ACTIVE.name());
                    statement.setLong(5, cutoffTime);
                }
        ) == 1, asyncExecutor);
    }

    @Override
    public CompletableFuture<Optional<AuctionListing>> findById(UUID auctionId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT * FROM auctions WHERE auction_id = ?")) {
                statement.setString(1, auctionId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return Optional.of(mapListing(resultSet));
                    }
                    return Optional.empty();
                }
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<List<AuctionListing>> findBySeller(UUID sellerId) {
        return CompletableFuture.supplyAsync(() -> queryList(
                "SELECT * FROM auctions WHERE seller_uuid = ? ORDER BY listing_time DESC",
                statement -> statement.setString(1, sellerId.toString())
        ), asyncExecutor);
    }

    @Override
    public CompletableFuture<List<AuctionListing>> findExpiredActive(long cutoffTime) {
        return CompletableFuture.supplyAsync(() -> queryList(
                "SELECT * FROM auctions WHERE status = ? AND expiration_time <= ?",
                statement -> {
                    statement.setString(1, AuctionStatus.ACTIVE.name());
                    statement.setLong(2, cutoffTime);
                }
        ), asyncExecutor);
    }

    private List<AuctionListing> queryList(String sql, SqlBinder binder) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AuctionListing> listings = new ArrayList<>();
                while (resultSet.next()) {
                    listings.add(mapListing(resultSet));
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

    private void bindListing(PreparedStatement statement, AuctionListing listing) throws SQLException {
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
    }

    private AuctionListing mapListing(ResultSet resultSet) throws SQLException {
        String buyerId = resultSet.getString("buyer_uuid");
        long updatedAt = 0L;
        try {
            updatedAt = resultSet.getLong("updated_at");
        } catch (SQLException ignored) {
            updatedAt = resultSet.getLong("listing_time");
        }
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
                updatedAt
        );
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
