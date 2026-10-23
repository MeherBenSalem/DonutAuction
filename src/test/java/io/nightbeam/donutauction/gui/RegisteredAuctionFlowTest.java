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
class RegisteredAuctionFlowTest extends FlowTestBase {
    @ParameterizedTest @ValueSource(strings = {"ah", "auction", "auctionhouse", "donutauctionhouse:ah"})
    void registeredAliasesOpenSellConfirmation(String alias) {
        assertTrue(server.dispatchCommand(seller, alias + " sell 1234.567"));
        assertInstanceOf(SellGui.class, seller.getOpenInventory().getTopInventory().getHolder());
        assertTrue(lore(seller, SellGui.CONFIRM_SLOT).contains("1234.567"));
        assertEquals(Material.AIR, seller.getInventory().getItemInMainHand().getType());
    }

    @ParameterizedTest @ValueSource(strings = {"NaN", "Infinity", "-Infinity", "-1", "0", "9.99", "1000000001", "garbage"})
    void invalidPriceKeepsHeldItemAndDoesNotOpenBrowser(String value) throws Exception {
        server.dispatchCommand(seller, "ah sell " + value);
        assertFalse(seller.getOpenInventory().getTopInventory().getHolder() instanceof BaseGui);
        assertEquals(3, seller.getInventory().getItemInMainHand().getAmount());
        assertTrue(repository.loadAll().get().isEmpty());
        assertEquals(0, service.pendingSaleRegistry().size());
    }

    @Test void missingPriceEmptyHandPermissionsAndLimitsDoNotEnterSale() throws Exception {
        server.dispatchCommand(seller, "ah sell");
        assertEquals(3, seller.getInventory().getItemInMainHand().getAmount());
        seller.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        server.dispatchCommand(seller, "ah sell 100");
        assertFalse(seller.getOpenInventory().getTopInventory().getHolder() instanceof BaseGui);
        seller.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND));
        permit(seller, false);
        server.dispatchCommand(seller, "ah sell 100");
        assertEquals(Material.DIAMOND, seller.getInventory().getItemInMainHand().getType());
        assertThrows(ExecutionException.class, () -> service.history(seller, HistoryView.BOUGHT, 1).get());
        permit(seller, true);
        config.set("auction-limits.default-limit", 0);
        gui = new GuiManager(plugin, service, cache, preferences, new AuctionLimitService(plugin), new NoopDonutCoreHook());
        gui.startSellFromHeldItem(seller, 100);
        assertEquals(Material.DIAMOND, seller.getInventory().getItemInMainHand().getType());
    }

    @Test void repeatedConfirmationPersistsOneListingWithUnchangedDecimalPrice() throws Exception {
        config.set("price-display.compact.enabled", true);
        server.dispatchCommand(seller, "ah sell 1234.567");
        click(seller, SellGui.CONFIRM_SLOT);
        click(seller, SellGui.CONFIRM_SLOT);
        await(() -> cache.sellerListings(seller.getUniqueId()).size() == 1);
        assertEquals(1, repository.loadAll().get().size());
        assertEquals(1234.567, repository.loadAll().get().get(0).price());
        assertEquals(0, service.pendingSaleRegistry().size());
    }

    @Test void closingConfirmationReturnsItemExactlyOnce() {
        server.dispatchCommand(seller, "ah sell 100");
        seller.closeInventory(); pump(); service.cancelPendingSaleForPlayer(seller); pump();
        assertEquals(3, seller.getInventory().all(Material.DIAMOND).values().stream().mapToInt(ItemStack::getAmount).sum());
    }

    @Test void fastSellUsesExactPriceAndOnePersistence() throws Exception {
        var pref = new PlayerPreference(seller.getUniqueId()); pref.fastSellEnabled(true); preferences.put(pref);
        server.dispatchCommand(seller, "ah sell 1000.123");
        await(() -> cache.sellerListings(seller.getUniqueId()).size() == 1);
        assertEquals(1000.123, repository.loadAll().get().get(0).price());
        assertEquals(1, repository.loadAll().get().size());
    }

    @Test void purchaseChargesAndDeliversExactlyOnceAndHistoryDoesNotSettleAgain() throws Exception {
        var listing = listing(1234.567); repository.save(listing).get(); cache.upsert(listing);
        var buyer = server.addPlayer("Buyer"); permit(buyer, true);
        config.set("price-display.compact.enabled", true);
        gui.openConfirmPurchase(buyer, listing);
        assertTrue(lore(buyer, 30).contains("1234.567"));
        click(buyer, 30); click(buyer, 30);
        await(() -> { try { return repository.findById(listing.auctionId()).get().orElseThrow().status() == AuctionStatus.SOLD
                && cache.findCached(listing.auctionId()) == null; } catch (Exception e) { return false; } });
        await(() -> { try { return service.history(buyer, HistoryView.BOUGHT, 1).get().totalResults() == 1; } catch (Exception e) { return false; } });
        verify(economy, times(1)).withdraw(any(), eq(1234.567));
        verify(economy, times(1)).deposit(any(), eq(1234.567));
        assertEquals(2, buyer.getInventory().all(Material.EMERALD).values().stream().mapToInt(ItemStack::getAmount).sum());
        gui.openHistory(buyer, HistoryView.BOUGHT, 1);
        await(() -> buyer.getOpenInventory().getTopInventory().getItem(0) != null);
        assertTrue(lore(buyer, 0).contains("Seller"));
        click(buyer, 0); click(buyer, 0); pump();
        verify(economy, times(1)).withdraw(any(), anyDouble());
        var unrelated = server.addPlayer("Unrelated"); permit(unrelated, true);
        assertEquals(0, service.history(unrelated, HistoryView.BOUGHT, 1).get().totalResults());
    }

    @Test void failedWithdrawalHasNoHistoryOrItemDelivery() throws Exception {
        var listing = listing(100); repository.save(listing).get(); cache.upsert(listing);
        var buyer = server.addPlayer("Buyer"); permit(buyer, true);
        when(economy.withdraw(any(), anyDouble())).thenReturn(new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "no"));
        var result = service.purchaseAuction(buyer, listing.auctionId());
        await(result::isDone);
        assertFalse(result.get().success());
        assertEquals(0, service.history(buyer, HistoryView.BOUGHT, 1).get().totalResults());
        verify(economy, never()).deposit(any(), anyDouble());
        assertTrue(buyer.getInventory().all(Material.EMERALD).isEmpty());
    }

    @Test void historyWriteFailureDoesNotUndoCompletedPurchase() throws Exception {
        doReturn(CompletableFuture.failedFuture(new IllegalStateException("database unavailable"))).when(repository).markPurchaseCompleted(any(), any(), any());
        var listing = listing(100); repository.save(listing).get(); cache.upsert(listing);
        var buyer = server.addPlayer("Buyer"); permit(buyer, true);
        var result = service.purchaseAuction(buyer, listing.auctionId()); await(result::isDone);
        assertTrue(result.get().success());
        assertEquals(AuctionStatus.SOLD, repository.findById(listing.auctionId()).get().orElseThrow().status());
        assertEquals(2, buyer.getInventory().all(Material.EMERALD).values().stream().mapToInt(ItemStack::getAmount).sum());
        verify(repository, never()).releaseClaim(any(), any(), anyLong());
        verify(economy, times(1)).withdraw(any(), anyDouble());
    }

    @Test void browserBookEmptyHistoryBackAndStaleUpdatesAreSafe() throws Exception {
        server.dispatchCommand(seller, "ah"); click(seller, AuctionGui.HISTORY_SLOT);
        assertInstanceOf(HistoryGui.class, seller.getOpenInventory().getTopInventory().getHolder());
        await(() -> "No completed transactions".equals(name(seller, 22)));
        click(seller, 51);
        assertInstanceOf(AuctionGui.class, seller.getOpenInventory().getTopInventory().getHolder());
        var first = new CompletableFuture<TransactionPage>(); var second = new CompletableFuture<TransactionPage>();
        doReturn(first, second).when(repository).findHistory(eq(seller.getUniqueId()), any(), anyInt());
        gui.openHistory(seller, HistoryView.BOUGHT, 1);
        gui.openHistory(seller, HistoryView.SOLD, 1);
        var current = seller.getOpenInventory().getTopInventory().getHolder();
        first.complete(new TransactionPage(seller.getUniqueId(), HistoryView.BOUGHT, List.of(), 1, 1, 0)); pump();
        assertSame(current, seller.getOpenInventory().getTopInventory().getHolder());
        click(seller, 51);
        second.complete(new TransactionPage(seller.getUniqueId(), HistoryView.SOLD, List.of(), 1, 1, 0)); pump();
        assertInstanceOf(AuctionGui.class, seller.getOpenInventory().getTopInventory().getHolder());
    }

    @Test void foreignAliasCollisionReproducesBrowserOnlyForForeignCommand() throws Exception {
        server.getCommandMap().clearCommands();
        server.getCommandMap().register("foreign", new Command("ah") {
            public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) { gui.openAuctionHouse((PlayerMock) sender); return true; }
        });
        registerCommands();
        server.dispatchCommand(seller, "ah sell 100");
        assertInstanceOf(AuctionGui.class, seller.getOpenInventory().getTopInventory().getHolder());
        server.dispatchCommand(seller, "donutauctionhouse:ah sell 100");
        assertInstanceOf(SellGui.class, seller.getOpenInventory().getTopInventory().getHolder());
    }

    @Test void historyUnavailableMetadataMissingNameAndInvalidDateConfigRemainReadable() throws Exception {
        var unknown = UUID.randomUUID();
        missingNameId = unknown;
        var listing = listing(1234.567).asSold(unknown, System.currentTimeMillis());
        repository.save(listing).get();
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE auctions SET item_data = 'invalid item data'");
        }
        config.set("history.timezone", "Not/AZone");
        config.set("history.date-format", "invalid pattern [");
        gui.openHistory(seller, HistoryView.SOLD, 1);
        await(() -> seller.getOpenInventory().getTopInventory().getItem(0) != null);
        assertEquals(Material.PAPER, seller.getOpenInventory().getTopInventory().getItem(0).getType());
        assertTrue(lore(seller, 0).contains(unknown.toString()));
        assertTrue(lore(seller, 0).contains("1234.567"));
        assertTrue(lore(seller, 0).contains("Date:"));
        click(seller, 0); verify(economy, never()).withdraw(any(), anyDouble());
    }

    @Test void historyPaginationTabsRefreshAndRepeatedRecordClicksAreReadOnly() throws Exception {
        UUID other = UUID.randomUUID();
        for (int i = 0; i < 46; i++) repository.save(listing(100 + i).asSold(other, System.currentTimeMillis() + i)).get();
        gui.openHistory(seller, HistoryView.SOLD, 1);
        await(() -> seller.getOpenInventory().getTopInventory().getItem(0) != null);
        assertTrue(lore(seller, 53).contains("1/2"));
        click(seller, 53);
        await(() -> seller.getOpenInventory().getTopInventory().getItem(0) != null);
        assertTrue(lore(seller, 53).contains("2/2"));
        assertNull(seller.getOpenInventory().getTopInventory().getItem(1));
        click(seller, 53); click(seller, 0); click(seller, 0);
        click(seller, 45);
        await(() -> seller.getOpenInventory().getTopInventory().getItem(0) != null);
        assertTrue(lore(seller, 45).contains("1/2"));
        click(seller, 46);
        await(() -> "No completed transactions".equals(name(seller, 22)));
        click(seller, 49); pump();
        verify(economy, never()).withdraw(any(), anyDouble());
        verify(economy, never()).deposit(any(), anyDouble());
    }

    @Test void historyPermissionRevocationAndWrongOwnerResponseCannotExposeRecords() throws Exception {
        var response = new CompletableFuture<TransactionPage>();
        doReturn(response).when(repository).findHistory(any(), any(), anyInt());
        gui.openHistory(seller, HistoryView.BOUGHT, 1);
        response.complete(new TransactionPage(UUID.randomUUID(), HistoryView.BOUGHT, List.of(), 1, 1, 0)); pump();
        assertEquals("Unable to load history. Click Refresh to retry.", name(seller, 22));
        var delayed = new CompletableFuture<TransactionPage>();
        doReturn(delayed).when(repository).findHistory(any(), any(), anyInt());
        gui.openHistory(seller, HistoryView.BOUGHT, 1); permit(seller, false);
        delayed.complete(new TransactionPage(seller.getUniqueId(), HistoryView.BOUGHT, List.of(), 1, 1, 0)); pump();
        assertTrue(seller.getOpenInventory().getTopInventory() == null || !(seller.getOpenInventory().getTopInventory().getHolder() instanceof HistoryGui));
    }

    @Test void disabledCompactDisplayNeverCallsCurrencyMetadata() {
        when(economy.currencyName()).thenThrow(new UnsupportedOperationException("unsupported currency names"));
        assertEquals("$1000000.125", service.formatDisplayPrice(1000000.125));
        verify(economy, never()).currencyName();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void browseAndYourItemsUseDisplayPriceWhileConfirmationRetainsAllDigits(boolean compact) throws Exception {
        var listing = listing(1234.567); repository.save(listing).get(); cache.upsert(listing);
        config.set("price-display.compact.enabled", compact);
        when(economy.format(anyDouble())).thenReturn("$1234.57");
        String expectedDisplay = compact ? "1.2k coins" : "$1234.57";
        gui.openAuctionHouse(seller);
        assertTrue(lore(seller, 0).contains(expectedDisplay));
        gui.openPlayerItems(seller);
        assertTrue(lore(seller, 0).contains(expectedDisplay));
        var buyer = server.addPlayer("Buyer"); permit(buyer, true);
        gui.openConfirmPurchase(buyer, listing);
        assertTrue(lore(buyer, 30).contains("$1234.57 (1234.567)"));
        assertEquals(1234.567, repository.findById(listing.auctionId()).get().orElseThrow().price());
        verify(economy, never()).withdraw(any(), anyDouble());
        verify(economy, never()).deposit(any(), anyDouble());
    }

    @Test void failedSellerPaymentRefundsBuyerAndDoesNotCreateHistoryOrDeliverItem() throws Exception {
        var listing = listing(100.125); repository.save(listing).get(); cache.upsert(listing);
        var buyer = server.addPlayer("Buyer"); permit(buyer, true);
        when(economy.deposit(any(), anyDouble())).thenReturn(
                new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "seller unavailable"),
                new EconomyResponse(100.125, 100.125, EconomyResponse.ResponseType.SUCCESS, ""));
        var result = service.purchaseAuction(buyer, listing.auctionId()); await(result::isDone);
        assertFalse(result.get().success());
        assertEquals(AuctionStatus.ACTIVE, repository.findById(listing.auctionId()).get().orElseThrow().status());
        assertEquals(0, service.history(buyer, HistoryView.BOUGHT, 1).get().totalResults());
        assertEquals(0, service.history(seller, HistoryView.SOLD, 1).get().totalResults());
        assertTrue(buyer.getInventory().all(Material.EMERALD).isEmpty());
        verify(economy, times(1)).withdraw(argThat(player -> player.getUniqueId().equals(buyer.getUniqueId())), eq(100.125));
        verify(economy, times(1)).deposit(argThat(player -> player.getUniqueId().equals(seller.getUniqueId())), eq(100.125));
        verify(economy, times(1)).deposit(argThat(player -> player.getUniqueId().equals(buyer.getUniqueId())), eq(100.125));
        verify(repository, never()).markPurchaseCompleted(any(), any(), any());
    }

    @Test void synchronouslyRejectedHistoryBookkeepingCannotUndoSettlement() throws Exception {
        doThrow(new RejectedExecutionException("executor stopped")).when(repository).markPurchaseCompleted(any(), any(), any());
        var listing = listing(100); repository.save(listing).get(); cache.upsert(listing);
        var buyer = server.addPlayer("Buyer"); permit(buyer, true);
        var result = service.purchaseAuction(buyer, listing.auctionId()); await(result::isDone);
        assertTrue(result.get().success());
        assertEquals(AuctionStatus.SOLD, repository.findById(listing.auctionId()).get().orElseThrow().status());
        assertEquals(2, buyer.getInventory().all(Material.EMERALD).values().stream().mapToInt(ItemStack::getAmount).sum());
        assertEquals(0, service.history(buyer, HistoryView.BOUGHT, 1).get().totalResults());
        verify(repository, never()).releaseClaim(any(), any(), anyLong());
        verify(economy, times(1)).withdraw(any(), eq(100.0));
        verify(economy, times(1)).deposit(any(), eq(100.0));
    }

    @Test void confirmationFormattingFailureCannotReopenACompletedPurchase() throws Exception {
        when(economy.format(anyDouble())).thenThrow(new IllegalStateException("provider formatting unavailable"));
        var listing = listing(100.125); repository.save(listing).get(); cache.upsert(listing);
        var buyer = server.addPlayer("Buyer"); permit(buyer, true);
        var result = service.purchaseAuction(buyer, listing.auctionId()); await(result::isDone);
        assertEquals(AuctionStatus.SOLD, repository.findById(listing.auctionId()).get().orElseThrow().status());
        assertTrue(result.get().success());
        assertEquals(1, service.history(buyer, HistoryView.BOUGHT, 1).get().totalResults());
        assertEquals(1, service.history(seller, HistoryView.SOLD, 1).get().totalResults());
        assertEquals(2, buyer.getInventory().all(Material.EMERALD).values().stream().mapToInt(ItemStack::getAmount).sum());
        verify(repository, never()).releaseClaim(any(), any(), anyLong());
        verify(economy, times(1)).withdraw(any(), eq(100.125));
        verify(economy, times(1)).deposit(any(), eq(100.125));
        assertFalse(service.purchaseAuction(buyer, listing.auctionId()).get().success());
        verify(economy, times(1)).withdraw(any(), anyDouble());
    }

    @Test void delayedHistoryResponseAfterCloseCannotReopenTheMenu() {
        var response = new CompletableFuture<TransactionPage>();
        doReturn(response).when(repository).findHistory(any(), any(), anyInt());
        gui.openHistory(seller, HistoryView.BOUGHT, 1);
        seller.closeInventory();
        response.complete(new TransactionPage(seller.getUniqueId(), HistoryView.BOUGHT, List.of(), 1, 1, 0));
        pump();
        assertFalse(seller.getOpenInventory().getTopInventory().getHolder() instanceof HistoryGui);
    }

    @Test void delayedHistoryResponseForAnOfflineViewerDoesNotRefreshTheMenu() {
        var response = new CompletableFuture<TransactionPage>();
        doReturn(response).when(repository).findHistory(any(), any(), anyInt());
        gui.openHistory(seller, HistoryView.BOUGHT, 1);
        var loading = seller.getOpenInventory().getTopInventory();
        assertTrue(seller.disconnect());
        response.complete(new TransactionPage(seller.getUniqueId(), HistoryView.BOUGHT, List.of(), 1, 1, 0));
        pump();
        assertFalse(seller.isOnline());
        var current = seller.getOpenInventory().getTopInventory();
        if (current.getHolder() instanceof HistoryGui) {
            assertSame(loading, current);
            assertEquals("Loading history...", PlainTextComponentSerializer.plainText().serialize(current.getItem(22).getItemMeta().displayName()));
        }
    }

    @Test void deniedHistoryAccessDoesNotQueryStorageOrReplaceTheCurrentInventory() throws Exception {
        permit(seller, false);
        var initial = seller.getOpenInventory().getTopInventory();
        gui.openHistory(seller, HistoryView.BOUGHT, 1);
        assertSame(initial, seller.getOpenInventory().getTopInventory());
        assertThrows(ExecutionException.class, () -> service.history(seller, HistoryView.SOLD, 1).get());
        verify(repository, never()).findHistory(any(), any(), anyInt());
    }

    @Test void wrongHistoryTabResponseDoesNotRenderAnyRecords() {
        var response = new CompletableFuture<TransactionPage>();
        doReturn(response).when(repository).findHistory(any(), any(), anyInt());
        gui.openHistory(seller, HistoryView.BOUGHT, 1);
        response.complete(new TransactionPage(seller.getUniqueId(), HistoryView.SOLD,
                List.of(new TransactionRecord(UUID.randomUUID(), "unavailable-metadata", 100, 1000, UUID.randomUUID())), 1, 1, 1));
        pump();
        assertNull(seller.getOpenInventory().getTopInventory().getItem(0));
        assertEquals("Unable to load history. Click Refresh to retry.", name(seller, 22));
    }

    @Test void historyRefreshIncludesANewCompletedPurchaseWithoutRepeatingSettlement() throws Exception {
        var buyer = server.addPlayer("Buyer"); permit(buyer, true);
        gui.openHistory(buyer, HistoryView.BOUGHT, 1);
        await(() -> "No completed transactions".equals(name(buyer, 22)));
        var listing = listing(100.125); repository.save(listing).get(); cache.upsert(listing);
        var result = service.purchaseAuction(buyer, listing.auctionId()); await(result::isDone);
        repository.findHistory(buyer.getUniqueId(), HistoryView.BOUGHT, 1).get();
        assertNull(buyer.getOpenInventory().getTopInventory().getItem(0));
        click(buyer, 49);
        await(() -> buyer.getOpenInventory().getTopInventory().getItem(0) != null);
        assertTrue(lore(buyer, 0).contains(listing.auctionId().toString()));
        assertTrue(lore(buyer, 0).contains("100.125"));
        click(buyer, 0); click(buyer, 0); pump();
        verify(economy, times(1)).withdraw(any(), eq(100.125));
        verify(economy, times(1)).deposit(any(), eq(100.125));
        assertEquals(2, buyer.getInventory().all(Material.EMERALD).values().stream().mapToInt(ItemStack::getAmount).sum());
    }
}
