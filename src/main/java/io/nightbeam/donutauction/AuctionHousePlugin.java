package io.nightbeam.donutauction;

import io.nightbeam.donutauction.api.DonutAuctionApi;
import io.nightbeam.donutauction.command.AuctionCommand;
import io.nightbeam.donutauction.economy.VaultEconomyProvider;
import io.nightbeam.donutauction.gui.GuiManager;
import io.nightbeam.donutauction.hook.DonutCoreHook;
import io.nightbeam.donutauction.hook.HookManager;
import io.nightbeam.donutauction.listener.AuctionChatListener;
import io.nightbeam.donutauction.listener.AuctionInventoryListener;
import io.nightbeam.donutauction.listener.PlayerQuitListener;
import io.nightbeam.donutauction.service.ApiProvider;
import io.nightbeam.donutauction.service.AuctionLimitService;
import io.nightbeam.donutauction.service.AuctionManager;
import io.nightbeam.donutauction.service.AuctionService;
import io.nightbeam.donutauction.service.PlayerPreferenceManager;
import io.nightbeam.donutauction.storage.AuctionRepository;
import io.nightbeam.donutauction.storage.DatabaseManager;
import io.nightbeam.donutauction.storage.PlayerPreferenceRepository;
import io.nightbeam.donutauction.storage.SqlAuctionRepository;
import io.nightbeam.donutauction.storage.SqlPlayerPreferenceRepository;
import io.nightbeam.donutauction.sync.ListingSyncBus;
import io.nightbeam.donutauction.sync.ListingSyncBusFactory;
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
import org.bukkit.plugin.ServicePriority;
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
    private ListingSyncBus listingSyncBus;
    private UpdateChecker updateChecker;
    private FileConfiguration messagesConfig;
    private MessageUtil messageUtil;

    @Override
    public void onEnable() {
        if (!isPaperFamily()) {
            getLogger().severe("DonutAuctionHouse needs Paper or a Paper fork (Purpur, Folia, Pufferfish...). "
                    + "This server (" + Bukkit.getName() + ") is missing Paper's API, so the plugin is disabled. "
                    + "Paper is a drop-in replacement for Spigot: https://papermc.io/downloads");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
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

        this.auctionRepository = new SqlAuctionRepository(databaseManager, schedulerAdapter.asyncExecutor(), getLogger());
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
        ListingSyncBus listingSyncBus = ListingSyncBusFactory.create(this, auctionRepository);
        this.listingSyncBus = listingSyncBus;
        this.auctionService = new AuctionService(
                this, schedulerAdapter, economyProvider, auctionRepository, auctionManager, donutCoreHook,
                listingSyncBus, databaseManager.getDatabaseType());
        this.guiManager = new GuiManager(this, auctionService, auctionManager, preferenceManager, limitService, donutCoreHook);

        this.auctionService.initialize();

        this.updateChecker = new UpdateChecker(this);
        getServer().getPluginManager().registerEvents(updateChecker, this);
        updateChecker.checkNow();

        registerCommands();
        registerListeners();
        getServer().getServicesManager().register(DonutAuctionApi.class,
                new ApiProvider(auctionService, auctionManager, guiManager, schedulerAdapter), this, ServicePriority.Normal);
        registerPlaceholders();
        // Players already online after /reload or a plugin manager reload.
        Bukkit.getOnlinePlayers().forEach(player -> preferenceManager.get(player.getUniqueId()));
        startMetrics();

        getLogger().info("DonutAuctionHouse enabled using " + databaseManager.getDatabaseType().name() + " storage"
                + (getConfig().getBoolean("bidding.enabled", true) ? ", Buy It Now and auctions" : ", Buy It Now")
                + (getConfig().getBoolean("economy.sales-tax.enabled", false) ? ", sales tax on" : "") + ".");
    }

    /** Paper's API (Adventure components, region schedulers) is required; plain Spigot/Bukkit lacks it. */
    private static boolean isPaperFamily() {
        try {
            Class.forName("io.papermc.paper.event.player.AsyncChatEvent");
            Class.forName("net.kyori.adventure.text.minimessage.MiniMessage");
            return true;
        } catch (ClassNotFoundException | LinkageError missing) {
            return false;
        }
    }

    private void registerPlaceholders() {
        if (!getConfig().getBoolean("placeholderapi.enabled", true)
                || getServer().getPluginManager().getPlugin("PlaceholderAPI") == null
                || !getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return;
        }
        try {
            if (new io.nightbeam.donutauction.hook.AuctionPlaceholders(this, auctionService, limitService).register()) {
                getLogger().info("PlaceholderAPI found: %donutauction_...% placeholders registered.");
            }
        } catch (LinkageError | RuntimeException exception) {
            getLogger().warning("Could not register PlaceholderAPI placeholders: " + exception.getMessage());
        }
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (preferenceManager != null) {
            preferenceManager.saveAll();
        }
        if (auctionService != null) {
            auctionService.shutdown();
        }
        if (listingSyncBus != null) {
            listingSyncBus.shutdown();
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
            metrics.addCustomChart(new SimplePie("storage_type", () -> databaseManager.getDatabaseType().name()));
            metrics.addCustomChart(new SimplePie("bidding", () -> String.valueOf(getConfig().getBoolean("bidding.enabled", true))));
            metrics.addCustomChart(new SimplePie("sales_tax", () -> String.valueOf(getConfig().getBoolean("economy.sales-tax.enabled", false))));
            metrics.addCustomChart(new SimplePie("discord_webhook", () -> String.valueOf(getConfig().getBoolean("discord.enabled", false))));
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

    public AuctionManager auctionManager() {
        return auctionManager;
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

    /**
     * New settings from the bundled config.yml are added to the server's file; existing values are never
     * changed. JavaPlugin already uses the bundled file as defaults, so copying them is enough.
     */
    public void applyConfigDefaults() {
        getConfig().options().copyDefaults(true);
        saveConfig();
    }
}
