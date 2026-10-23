package io.nightbeam.donutauction.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class DatabaseManager {

    private final JavaPlugin plugin;
    private final DatabaseType databaseType;
    private final HikariDataSource dataSource;

    private DatabaseManager(JavaPlugin plugin, DatabaseType databaseType, HikariDataSource dataSource) {
        this.plugin = plugin;
        this.databaseType = databaseType;
        this.dataSource = dataSource;
    }

    public static DatabaseManager fromConfig(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        DatabaseType databaseType = DatabaseType.valueOf(config.getString("storage.type", "SQLITE").toUpperCase());
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPoolName("DonutAuctionHousePool");
        hikariConfig.setMaximumPoolSize(databaseType == DatabaseType.SQLITE ? 2 : 10);
        hikariConfig.setMinimumIdle(1);
        hikariConfig.setConnectionTimeout(10_000L);
        hikariConfig.setValidationTimeout(5_000L);
        hikariConfig.setLeakDetectionThreshold(30_000L);

        if (databaseType == DatabaseType.SQLITE) {
            Path databasePath = new File(plugin.getDataFolder(), config.getString("storage.sqlite.file", "auctions.db")).toPath();
            hikariConfig.setDriverClassName("org.sqlite.JDBC");
            hikariConfig.setJdbcUrl("jdbc:sqlite:" + databasePath);
            hikariConfig.addDataSourceProperty("foreign_keys", "on");
            hikariConfig.addDataSourceProperty("journal_mode", "WAL");
            hikariConfig.addDataSourceProperty("synchronous", "NORMAL");
            // Two pooled connections may write at once (a purchase and a bid): wait instead of failing.
            hikariConfig.addDataSourceProperty("busy_timeout", "5000");
        } else {
            String host = config.getString("storage.mysql.host", "localhost");
            int port = config.getInt("storage.mysql.port", 3306);
            String database = config.getString("storage.mysql.database", "donut_auction_house");
            String username = config.getString("storage.mysql.username", "root");
            String password = config.getString("storage.mysql.password", "password");
            String parameters = config.getString("storage.mysql.parameters", "useSSL=false&characterEncoding=utf8");
            hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
            hikariConfig.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?" + parameters);
            hikariConfig.setUsername(username);
            hikariConfig.setPassword(password);
        }

        return new DatabaseManager(plugin, databaseType, new HikariDataSource(hikariConfig));
    }

    public void start() {
        plugin.getDataFolder().mkdirs();
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public DatabaseType getDatabaseType() {
        return databaseType;
    }

    public void shutdown() {
        dataSource.close();
    }
}