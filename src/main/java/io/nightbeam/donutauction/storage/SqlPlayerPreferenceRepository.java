package io.nightbeam.donutauction.storage;

import io.nightbeam.donutauction.model.PlayerPreference;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

public final class SqlPlayerPreferenceRepository implements PlayerPreferenceRepository {

    private final DatabaseManager databaseManager;
    private final Executor asyncExecutor;

    public SqlPlayerPreferenceRepository(DatabaseManager databaseManager, Executor asyncExecutor) {
        this.databaseManager = databaseManager;
        this.asyncExecutor = asyncExecutor;
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = databaseManager.dataSource().getConnection();
                 Statement statement = connection.createStatement()) {
                if (databaseManager.getDatabaseType() == DatabaseType.SQLITE) {
                    statement.executeUpdate("""
                            CREATE TABLE IF NOT EXISTS player_preferences (
                                player_uuid VARCHAR(36) PRIMARY KEY,
                                fast_buy_enabled BOOLEAN NOT NULL DEFAULT FALSE,
                                fast_sell_enabled BOOLEAN NOT NULL DEFAULT FALSE,
                                last_duration_hours INT NOT NULL DEFAULT 48,
                                last_category VARCHAR(32) NOT NULL DEFAULT 'ALL',
                                last_price DOUBLE NOT NULL DEFAULT 0.0
                            )
                            """);
                } else {
                    statement.executeUpdate("""
                            CREATE TABLE IF NOT EXISTS player_preferences (
                                player_uuid VARCHAR(36) PRIMARY KEY,
                                fast_buy_enabled BOOLEAN NOT NULL DEFAULT FALSE,
                                fast_sell_enabled BOOLEAN NOT NULL DEFAULT FALSE,
                                last_duration_hours INT NOT NULL DEFAULT 48,
                                last_category VARCHAR(32) NOT NULL DEFAULT 'ALL',
                                last_price DOUBLE NOT NULL DEFAULT 0.0
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                            """);
                }
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<PlayerPreference> load(UUID playerId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = databaseManager.dataSource().getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT * FROM player_preferences WHERE player_uuid = ?")) {
                statement.setString(1, playerId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return new PlayerPreference(
                                playerId,
                                resultSet.getBoolean("fast_buy_enabled"),
                                resultSet.getBoolean("fast_sell_enabled"),
                                resultSet.getInt("last_duration_hours"),
                                resultSet.getString("last_category"),
                                resultSet.getDouble("last_price")
                        );
                    }
                    return new PlayerPreference(playerId);
                }
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<Void> save(PlayerPreference preference) {
        return CompletableFuture.runAsync(() -> {
            String sql;
            if (databaseManager.getDatabaseType() == DatabaseType.SQLITE) {
                sql = """
                        INSERT INTO player_preferences(player_uuid, fast_buy_enabled, fast_sell_enabled, last_duration_hours, last_category, last_price)
                        VALUES(?, ?, ?, ?, ?, ?)
                        ON CONFLICT(player_uuid) DO UPDATE SET
                            fast_buy_enabled = excluded.fast_buy_enabled,
                            fast_sell_enabled = excluded.fast_sell_enabled,
                            last_duration_hours = excluded.last_duration_hours,
                            last_category = excluded.last_category,
                            last_price = excluded.last_price
                        """;
            } else {
                sql = """
                        INSERT INTO player_preferences(player_uuid, fast_buy_enabled, fast_sell_enabled, last_duration_hours, last_category, last_price)
                        VALUES(?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            fast_buy_enabled = VALUES(fast_buy_enabled),
                            fast_sell_enabled = VALUES(fast_sell_enabled),
                            last_duration_hours = VALUES(last_duration_hours),
                            last_category = VALUES(last_category),
                            last_price = VALUES(last_price)
                        """;
            }
            try (Connection connection = databaseManager.dataSource().getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, preference.playerId().toString());
                statement.setBoolean(2, preference.fastBuyEnabled());
                statement.setBoolean(3, preference.fastSellEnabled());
                statement.setInt(4, preference.lastDurationHours());
                statement.setString(5, preference.lastCategory());
                statement.setDouble(6, preference.lastPrice());
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        }, asyncExecutor);
    }
}
