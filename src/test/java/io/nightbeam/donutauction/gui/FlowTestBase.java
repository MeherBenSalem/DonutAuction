package io.nightbeam.donutauction.gui;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import be.seeseemelk.mockbukkit.ServerMock;
import io.nightbeam.donutauction.AuctionHousePlugin;
import io.nightbeam.donutauction.command.AuctionCommand;
import io.nightbeam.donutauction.economy.VaultEconomyProvider;
import io.nightbeam.donutauction.hook.NoopDonutCoreHook;
import io.nightbeam.donutauction.listener.AuctionInventoryListener;
import io.nightbeam.donutauction.model.*;
import io.nightbeam.donutauction.service.*;
import io.nightbeam.donutauction.storage.*;
import io.nightbeam.donutauction.sync.NoopListingSyncBus;
import io.nightbeam.donutauction.util.MessageUtil;
import io.nightbeam.donutauction.util.SchedulerAdapter;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.SQLiteDataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real Bukkit command registration, inventory events and native SQLite; no live server mutation. */
abstract class FlowTestBase {
    public static final class TestMeta extends be.seeseemelk.mockbukkit.inventory.meta.ItemMetaMock {
        public TestMeta(org.bukkit.inventory.meta.ItemMeta meta) { super(meta); }
        @Override public void setVersion(int version) { }
        @Override public TestMeta clone() { return new TestMeta(this); }
        @Override public List<net.kyori.adventure.text.Component> lore() { return hasLore() ? super.lore() : null; }
        public static TestMeta deserialize(java.util.Map<String, Object> values) {
            return new TestMeta(be.seeseemelk.mockbukkit.inventory.meta.ItemMetaMock.deserialize(values));
        }
    }
    @TempDir protected Path directory;
    protected ServerMock server;
    protected Plugin owner;
    protected PlayerMock seller;
    protected AuctionHousePlugin plugin;
    protected YamlConfiguration config;
    protected AuctionService service;
    protected GuiManager gui;
    protected AuctionManager cache;
    protected PlayerPreferenceManager preferences;
    protected VaultEconomyProvider economy;
    protected SqlAuctionRepository repository;
    protected SQLiteDataSource source;
    protected ExecutorService database;
    protected UUID missingNameId;
    protected final ConcurrentLinkedQueue<Runnable> entityTasks = new ConcurrentLinkedQueue<>();

    @BeforeEach protected void setup() throws Exception {
        // This Paper 1.20.1 harness needs adapters for Component titles/holder overloads
        // and nullable lore. Gameplay, command dispatch and event handlers remain real.
        server = MockBukkit.mock(new ServerMock() {
            @Override public org.bukkit.OfflinePlayer getOfflinePlayer(UUID id) {
                if (id.equals(missingNameId)) {
                    var offline = mock(org.bukkit.OfflinePlayer.class);
                    when(offline.getUniqueId()).thenReturn(id);
                    return offline;
                }
                return super.getOfflinePlayer(id);
            }
            @Override public be.seeseemelk.mockbukkit.inventory.InventoryMock createInventory(
                    org.bukkit.inventory.InventoryHolder holder, int size, net.kyori.adventure.text.Component title) {
                return createInventory(holder, size, PlainTextComponentSerializer.plainText().serialize(title));
            }
            @Override public be.seeseemelk.mockbukkit.inventory.InventoryMock createInventory(
                    org.bukkit.inventory.InventoryHolder holder, int size, String title) {
                return new be.seeseemelk.mockbukkit.inventory.ChestInventoryMock(holder, size) {
                    @Override public org.bukkit.inventory.InventoryHolder getHolder(boolean snapshot) { return getHolder(); }
                };
            }
            @Override public be.seeseemelk.mockbukkit.inventory.ItemFactoryMock getItemFactory() {
                return new be.seeseemelk.mockbukkit.inventory.ItemFactoryMock() {
                    @Override public ItemStack ensureServerConversions(ItemStack item) { return item; }
                    @Override public org.bukkit.inventory.meta.ItemMeta getItemMeta(Material material) {
                        var meta = super.getItemMeta(material);
                        return meta == null ? null : new TestMeta(meta);
                    }
                };
            }
            @Override public be.seeseemelk.mockbukkit.MockUnsafeValues getUnsafe() {
                return new be.seeseemelk.mockbukkit.MockUnsafeValues() {
                    @Override public Material getMaterial(String name, int version) { return Material.matchMaterial(name); }
                    @Override public int getDataVersion() { return 3465; }
                };
            }
        });
        org.bukkit.configuration.serialization.ConfigurationSerialization.registerClass(TestMeta.class);
        owner = MockBukkit.createMockPlugin("DonutAuctionHouse");
        seller = server.addPlayer("Seller");
        seller.openInventory(server.createInventory(null, 9, "Initial inventory"));
        permit(seller, true);
        seller.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 3));
        plugin = mock(AuctionHousePlugin.class);
        config = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(getClass().getResourceAsStream("/config.yml"), java.nio.charset.StandardCharsets.UTF_8));
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getMessagesConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("AuctionFlowTest"));
        MessageUtil messages = new MessageUtil(plugin);
        when(plugin.messages()).thenReturn(messages);
        SchedulerAdapter scheduler = mock(SchedulerAdapter.class);
        database = Executors.newSingleThreadExecutor();
        when(scheduler.asyncExecutor()).thenReturn(database);
        doAnswer(call -> { entityTasks.add(call.getArgument(1)); return null; }).when(scheduler).runEntity(any(), any(Runnable.class));
        doAnswer(call -> { entityTasks.add(call.getArgument(1)); return null; }).when(scheduler).runEntity(any(), any(Runnable.class), nullable(Runnable.class));
        doAnswer(call -> { entityTasks.add(call.getArgument(1)); return null; }).when(scheduler).runEntityLater(any(), any(Runnable.class), anyLong());
        doAnswer(call -> { entityTasks.add(call.getArgument(0)); return null; }).when(scheduler).runGlobal(any(Runnable.class));
        doAnswer(call -> { database.execute(call.getArgument(0)); return null; }).when(scheduler).runAsync(any(Runnable.class));
        when(plugin.schedulerAdapter()).thenReturn(scheduler);
        source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + directory.resolve("flow.db"));
        repository = spy(new SqlAuctionRepository(source, database));
        repository.initialize().get(5, TimeUnit.SECONDS);
        cache = new AuctionManager(45);
        economy = mock(VaultEconomyProvider.class);
        when(economy.format(anyDouble())).thenAnswer(call -> "$" + call.getArgument(0));
        when(economy.currencyName()).thenReturn("coins");
        when(economy.has(any(), anyDouble())).thenReturn(true);
        when(economy.withdraw(any(), anyDouble())).thenReturn(new EconomyResponse(1, 1, EconomyResponse.ResponseType.SUCCESS, ""));
        when(economy.deposit(any(), anyDouble())).thenReturn(new EconomyResponse(1, 1, EconomyResponse.ResponseType.SUCCESS, ""));
        var hook = new NoopDonutCoreHook();
        service = new AuctionService(plugin, scheduler, economy, repository, cache, hook, new NoopListingSyncBus(), DatabaseType.SQLITE);
        PlayerPreferenceRepository prefRepo = mock(PlayerPreferenceRepository.class);
        when(prefRepo.save(any())).thenReturn(CompletableFuture.completedFuture(null));
        preferences = new PlayerPreferenceManager(prefRepo);
        gui = new GuiManager(plugin, service, cache, preferences, new AuctionLimitService(plugin), hook);
        server.getPluginManager().registerEvents(new AuctionInventoryListener(gui, service), owner);
        registerCommands();
    }

    protected void registerCommands() throws Exception {
        AuctionCommand executor = new AuctionCommand(plugin, service, gui, new AuctionLimitService(plugin), preferences);
        var description = new PluginDescriptionFile(getClass().getResourceAsStream("/plugin.yml"));
        var constructor = PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
        constructor.setAccessible(true);
        for (var entry : description.getCommands().entrySet()) {
            PluginCommand command = constructor.newInstance(entry.getKey(), owner);
            if (entry.getValue().get("aliases") instanceof List<?> aliases) command.setAliases(aliases.stream().map(Object::toString).toList());
            command.setExecutor(executor);
            server.getCommandMap().register("donutauctionhouse", command);
        }
    }
    protected void permit(PlayerMock player, boolean allowed) {
        player.addAttachment(owner, "donutauction.use", allowed);
        player.addAttachment(owner, "donutcore.auction.use", allowed);
        player.addAttachment(owner, "donutauction.sell", allowed);
        player.addAttachment(owner, "donutcore.auction.sell", allowed);
        player.addAttachment(owner, "donutauction.fastsell", allowed);
    }
    @AfterEach protected void cleanup() throws Exception {
        if (database != null) { database.shutdown(); database.awaitTermination(5, TimeUnit.SECONDS); }
        MockBukkit.unmock();
    }
    protected void pump() {
        Runnable task;
        while ((task = entityTasks.poll()) != null) {
            // Bukkit keeps a crafting inventory after close; old MockBukkit returns null.
            if (seller.getOpenInventory().getTopInventory() == null) seller.openInventory(server.createInventory(null, 9, "Crafting"));
            task.run();
        }
    }
    protected void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) { pump(); Thread.sleep(5); }
        pump(); assertTrue(condition.getAsBoolean(), "Async operation timed out");
    }
    protected void click(PlayerMock player, int slot) {
        InventoryClickEvent event = new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER, slot, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled());
    }
    protected String name(PlayerMock player, int slot) {
        var item = player.getOpenInventory().getTopInventory().getItem(slot);
        return item == null ? null : PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName());
    }
    protected String lore(PlayerMock player, int slot) {
        var item = player.getOpenInventory().getTopInventory().getItem(slot);
        return item.getItemMeta().lore().stream().map(component -> PlainTextComponentSerializer.plainText().serialize(component)).collect(java.util.stream.Collectors.joining("\n"));
    }
    protected AuctionListing listing(double price) {
        long now = System.currentTimeMillis();
        return new AuctionListing(UUID.randomUUID(), new ItemStack(Material.EMERALD, 2), seller.getUniqueId(), price, now, now + 3600000, AuctionStatus.ACTIVE, null, 0, false, now);
    }
}
