package io.nightbeam.donutauction;

import io.nightbeam.donutauction.command.AuctionCommand;
import io.nightbeam.donutauction.economy.VaultEconomyProvider;
import io.nightbeam.donutauction.gui.GuiManager;
import io.nightbeam.donutauction.hook.DonutCoreHook;
import io.nightbeam.donutauction.hook.HookManager;
import io.nightbeam.donutauction.listener.AuctionChatListener;
import io.nightbeam.donutauction.listener.AuctionInventoryListener;
import io.nightbeam.donutauction.listener.PlayerQuitListener;
import io.nightbeam.donutauction.service.AuctionLimitService;
import io.nightbeam.donutauction.service.AuctionManager;
import io.nightbeam.donutauction.service.AuctionService;
import io.nightbeam.donutauction.service.PlayerPreferenceManager;
import io.nightbeam.donutauction.storage.AuctionRepository;
import io.nightbeam.donutauction.storage.DatabaseManager;
import io.nightbeam.donutauction.storage.PlayerPreferenceRepository;
import io.nightbeam.donutauction.storage.SqlAuctionRepository;
import io.nightbeam.donutauction.storage.SqlPlayerPreferenceRepository;
import io.nightbeam.donutauction.util.MessageUtil;
import io.nightbeam.donutauction.util.SchedulerAdapter;
import io.nightbeam.donutauction.util.UpdateChecker;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class AuctionHousePlugin extends JavaPlugin {

    private SchedulerAdapter schedulerAdapter;
    private VaultEconomyProvider economyProvider;
    private DatabaseManager databaseManager;
    private AuctionRepository auctionRepository;
    private PlayerPreferenceRepository playerPreferenceRepository;
    private AuctionManager auctionManager;
    private AuctionService auctionService;
    private PlayerPreferenceManager preferenceManager;
    private AuctionLimitService limitService;
    private GuiManager guiManager;
    private DonutCoreHook donutCoreHook;
    private UpdateChecker updateChecker;
    private FileConfiguration messagesConfig;
    private MessageUtil messageUtil;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfAbsent("messages.yml");
        reloadMessagesConfig();
        applyConfigDefaults();

        this.schedulerAdapter = new SchedulerAdapter(this);
        Optional<VaultEconomyProvider> economy = VaultEconomyProvider.create(this);
        if (economy.isEmpty()) {
            getLogger().severe(
                    "DonutAuctionHouse requires a Vault-compatible Economy service "
                            + "(Vault or VaultUnlocked + an economy plugin). Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.economyProvider = economy.get();
        this.databaseManager = DatabaseManager.fromConfig(this);
        this.databaseManager.start();

        this.auctionRepository = new SqlAuctionRepository(databaseManager, schedulerAdapter.asyncExecutor());
        this.playerPreferenceRepository = new SqlPlayerPreferenceRepository(databaseManager, schedulerAdapter.asyncExecutor());

        this.auctionRepository.initialize().thenCompose(ignored -> playerPreferenceRepository.initialize())
                .exceptionally(throwable -> {
                    getLogger().severe("Failed to initialize database tables: " + throwable.getMessage());
                    throwable.printStackTrace();
                    return null;
                });

        this.auctionManager = new AuctionManager(getConfig().getInt("auction.browse-page-size", 45));
        this.donutCoreHook = HookManager.create(this);
        this.preferenceManager = new PlayerPreferenceManager(playerPreferenceRepository);
        this.limitService = new AuctionLimitService(this);

        this.messageUtil = new MessageUtil(this);
        this.auctionService = new AuctionService(this, schedulerAdapter, economyProvider, auctionRepository, auctionManager, donutCoreHook);
        this.guiManager = new GuiManager(this, auctionService, auctionManager, preferenceManager, limitService, donutCoreHook);

        this.auctionService.initialize();

        this.updateChecker = new UpdateChecker(this);
        getServer().getPluginManager().registerEvents(updateChecker, this);
        updateChecker.checkNow();

        registerCommands();
        registerListeners();
        startMetrics();

        getLogger().info("DonutAuctionHouse enabled using " + databaseManager.getDatabaseType().name() + " storage.");
    }

    @Override
    public void onDisable() {
        if (preferenceManager != null) {
            preferenceManager.saveAll();
        }
        if (auctionService != null) {
            auctionService.shutdown();
        }
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
    }

    private void registerCommands() {
        AuctionCommand auctionCommand = new AuctionCommand(this, auctionService, guiManager, limitService, preferenceManager);
        registerCommand("ah", auctionCommand);
        registerCommand("auction", auctionCommand);
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new AuctionInventoryListener(guiManager, auctionService), this);
        getServer().getPluginManager().registerEvents(new AuctionChatListener(guiManager), this);
        getServer().getPluginManager().registerEvents(new PlayerQuitListener(guiManager, auctionService, preferenceManager), this);
    }

    private void registerCommand(String name, AuctionCommand command) {
        PluginCommand pluginCommand = getCommand(name);
        if (pluginCommand == null) {
            throw new IllegalStateException("Missing command in plugin.yml: " + name);
        }

        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);
    }

    private void startMetrics() {
        if (!getConfig().getBoolean("metrics.enabled", true)) {
            return;
        }
        try {
            Metrics metrics = new Metrics(this, 33523);
            metrics.addCustomChart(new SimplePie("server_software", () -> Bukkit.getServer().getName()));
        } catch (Throwable throwable) {
            getLogger().warning("bStats metrics failed to load (plugin will continue): " + throwable.getMessage());
        }
    }

    public SchedulerAdapter schedulerAdapter() {
        return schedulerAdapter;
    }

    public MessageUtil messages() {
        return messageUtil;
    }

    public FileConfiguration getMessagesConfig() {
        return messagesConfig;
    }

    public void reloadMessagesConfig() {
        saveResourceIfAbsent("messages.yml");
        File file = new File(getDataFolder(), "messages.yml");
        messagesConfig = YamlConfiguration.loadConfiguration(file);
        InputStream defStream = getResource("messages.yml");
        if (defStream != null) {
            YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defStream, StandardCharsets.UTF_8));
            messagesConfig.setDefaults(defConfig);
            messagesConfig.options().copyDefaults(true);
        }
    }

    public void reloadPluginConfig() {
        reloadConfig();
        applyConfigDefaults();
        reloadMessagesConfig();
        if (messageUtil != null) {
            messageUtil.reload();
        }
    }

    private void saveResourceIfAbsent(String resourcePath) {
        File file = new File(getDataFolder(), resourcePath);
        if (!file.exists()) {
            saveResource(resourcePath, false);
        }
    }

    public void applyConfigDefaults() {
        getConfig().addDefault("auction.min-price", 10.0D);
        getConfig().addDefault("messages.price-below-min", "&cMinimum auction price is &6%min_price%&c.");
        getConfig().addDefault("auction-lore.mode", "APPEND");
        getConfig().addDefault("auction-lore.separator", true);
        getConfig().addDefault("auction-limits.enabled", true);
        getConfig().addDefault("auction-limits.default-limit", 5);
        getConfig().addDefault("auction-limits.permissions.donutauction.limit.10", 10);
        getConfig().addDefault("auction-limits.permissions.donutauction.limit.25", 25);
        getConfig().addDefault("auction-limits.permissions.donutauction.limit.50", 50);
        getConfig().addDefault("auction-limits.permissions.donutauction.limit.100", 100);
        getConfig().addDefault("auction-limits.permissions.donutauction.limit.unlimited", -1);
        getConfig().addDefault("auction-slots.enabled", true);
        getConfig().addDefault("auction-slots.permissions.donutauction.slots.10", 10);
        getConfig().addDefault("auction-slots.permissions.donutauction.slots.25", 25);
        getConfig().addDefault("auction-slots.permissions.donutauction.slots.50", 50);
        getConfig().addDefault("auction-slots.permissions.donutauction.slots.100", 100);
        getConfig().addDefault("fast-buy.enabled", true);
        getConfig().addDefault("fast-buy.default-state", false);
        getConfig().addDefault("fast-buy.require-permission", true);
        getConfig().addDefault("fast-sell.enabled", true);
        getConfig().addDefault("fast-sell.default-state", false);
        getConfig().addDefault("fast-sell.require-permission", true);
        getConfig().addDefault("shulker-support.enabled", true);
        getConfig().addDefault("shulker-support.preview-contents", true);
        getConfig().addDefault("update-checker.enabled", true);
        getConfig().addDefault("update-checker.notify-console", true);
        getConfig().addDefault("update-checker.notify-admins", true);
        getConfig().addDefault("update-checker.check-interval-hours", 12);
        getConfig().addDefault("metrics.enabled", true);
        getConfig().addDefault("debug.pending-sales", false);
        getConfig().options().copyDefaults(true);
        saveConfig();
    }
}
