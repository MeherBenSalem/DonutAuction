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

public final class SqlAuctionRepository implements AuctionRepository {

    private final DatabaseManager databaseManager;
    private final Executor asyncExecutor;

    public SqlAuctionRepository(DatabaseManager databaseManager, Executor asyncExecutor) {
        this.databaseManager = databaseManager;
        this.asyncExecutor = asyncExecutor;
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = databaseManager.dataSource().getConnection();
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
                            seller_claimed BOOLEAN NOT NULL DEFAULT FALSE
                        )
                        """);
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_auctions_status_expiration ON auctions(status, expiration_time)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_auctions_seller_status ON auctions(seller_uuid, status)");
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<List<AuctionListing>> loadAll() {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = databaseManager.dataSource().getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT * FROM auctions");
                 ResultSet resultSet = statement.executeQuery()) {
                List<AuctionListing> listings = new ArrayList<>();
                while (resultSet.next()) {
                    listings.add(mapListing(resultSet));
                }
                return listings;
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<Void> save(AuctionListing listing) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = databaseManager.dataSource().getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         INSERT INTO auctions(auction_id, seller_uuid, buyer_uuid, item_data, price, listing_time, expiration_time, sold_time, status, seller_claimed)
                         VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            try (Connection connection = databaseManager.dataSource().getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         UPDATE auctions SET seller_uuid = ?, buyer_uuid = ?, item_data = ?, price = ?, listing_time = ?, expiration_time = ?, sold_time = ?, status = ?, seller_claimed = ?
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
                statement.setString(10, listing.auctionId().toString());
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<Optional<AuctionListing>> findById(UUID auctionId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = databaseManager.dataSource().getConnection();
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
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = databaseManager.dataSource().getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT * FROM auctions WHERE seller_uuid = ? ORDER BY listing_time DESC")) {
                statement.setString(1, sellerId.toString());
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
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<List<AuctionListing>> findExpiredActive(long cutoffTime) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = databaseManager.dataSource().getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT * FROM auctions WHERE status = ? AND expiration_time <= ?")) {
                statement.setString(1, AuctionStatus.ACTIVE.name());
                statement.setLong(2, cutoffTime);
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
        }, asyncExecutor);
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
    }

    private AuctionListing mapListing(ResultSet resultSet) throws SQLException {
        String buyerId = resultSet.getString("buyer_uuid");
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
                resultSet.getBoolean("seller_claimed")
        );
    }
}
