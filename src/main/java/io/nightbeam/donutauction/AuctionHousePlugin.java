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
import org.bukkit.command.PluginCommand;
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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        applyConfigDefaults();

        this.schedulerAdapter = new SchedulerAdapter(this);
        this.economyProvider = VaultEconomyProvider.create(this)
                .orElseThrow(() -> new IllegalStateException("Vault economy provider was not found."));
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

        this.auctionService = new AuctionService(this, schedulerAdapter, economyProvider, auctionRepository, auctionManager, donutCoreHook);
        this.guiManager = new GuiManager(this, auctionService, auctionManager, preferenceManager, donutCoreHook);

        this.auctionService.initialize();

        this.updateChecker = new UpdateChecker(this);
        getServer().getPluginManager().registerEvents(updateChecker, this);
        updateChecker.checkNow();

        registerCommands();
        registerListeners();

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
        getServer().getPluginManager().registerEvents(new AuctionInventoryListener(guiManager), this);
        getServer().getPluginManager().registerEvents(new AuctionChatListener(guiManager), this);
        getServer().getPluginManager().registerEvents(new PlayerQuitListener(guiManager, preferenceManager), this);
    }

    private void registerCommand(String name, AuctionCommand command) {
        PluginCommand pluginCommand = getCommand(name);
        if (pluginCommand == null) {
            throw new IllegalStateException("Missing command in plugin.yml: " + name);
        }

        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);
    }

    public SchedulerAdapter schedulerAdapter() {
        return schedulerAdapter;
    }

    public MessageUtil messages() {
        return new MessageUtil(getConfig().getString("messages.prefix", "&6[DonutAuctionHouse]&r "));
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
        getConfig().options().copyDefaults(true);
        saveConfig();
    }
}
