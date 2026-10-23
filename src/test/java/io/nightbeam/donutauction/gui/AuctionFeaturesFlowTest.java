package io.nightbeam.donutauction.gui;

import be.seeseemelk.mockbukkit.entity.PlayerMock;
import io.nightbeam.donutauction.model.AuctionBrowseRequest;
import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.AuctionStatus;
import io.nightbeam.donutauction.model.HistoryView;
import io.nightbeam.donutauction.model.ListingFilter;
import io.nightbeam.donutauction.model.ListingType;
import io.nightbeam.donutauction.service.ActionResult;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 2.0 trading flows through the real service, row claims and SQLite: tax, collection, bidding, rules. */
class AuctionFeaturesFlowTest extends FlowTestBase {

    private ActionResult done(CompletableFuture<ActionResult> future) throws Exception {
        await(future::isDone);
        return future.get(5, TimeUnit.SECONDS);
    }

    private AuctionListing saved(AuctionListing listing) throws Exception {
        repository.save(listing).get();
        cache.upsert(listing);
        return listing;
    }

    private AuctionListing auction(double startingBid, long endsIn) throws Exception {
        AuctionListing listing = service.createServerListing(seller.getUniqueId(), "Seller", new ItemStack(Material.NETHERITE_INGOT, 2),
                startingBid, endsIn, ListingType.BID).get(5, TimeUnit.SECONDS);
        assertEquals(ListingType.BID, cache.findCached(listing.auctionId()).type());
        return listing;
    }

    private PlayerMock player(String name) {
        PlayerMock player = server.addPlayer(name);
        permit(player, true);
        return player;
    }

    private static org.bukkit.OfflinePlayer is(PlayerMock player) {
        return argThat(offline -> offline != null && offline.getUniqueId().equals(player.getUniqueId()));
    }

    private int count(PlayerMock player, Material material) {
        return player.getInventory().all(material).values().stream().mapToInt(ItemStack::getAmount).sum();
    }

    private void endNow(AuctionListing listing) throws Exception {
        try (var connection = source.getConnection(); var statement = connection.prepareStatement(
                "UPDATE auctions SET expiration_time = ? WHERE auction_id = ?")) {
            statement.setLong(1, System.currentTimeMillis() - 1);
            statement.setString(2, listing.auctionId().toString());
            statement.executeUpdate();
        }
    }

    @Test void shorthandPricesListTheExactAmount() throws Exception {
        assertTrue(server.dispatchCommand(seller, "ah sell 1.5k"));
        assertInstanceOf(SellGui.class, seller.getOpenInventory().getTopInventory().getHolder());
        assertTrue(lore(seller, SellGui.CONFIRM_SLOT).contains("1500"));
        click(seller, SellGui.CONFIRM_SLOT);
        await(() -> cache.sellerListings(seller.getUniqueId()).size() == 1);
        assertEquals(1500.0, repository.loadAll().get().get(0).price());
        assertEquals("Seller", repository.loadAll().get().get(0).sellerName());
    }

    @Test void salesTaxPaysTheSellerNetAndTheTaxAccount() throws Exception {
        config.set("economy.sales-tax.enabled", true);
        config.set("economy.sales-tax.percent", 10.0);
        config.set("economy.sales-tax.deposit-to", "ServerBank");
        var listing = saved(listing(1000));
        var buyer = player("Buyer");
        assertTrue(done(service.purchaseAuction(buyer, listing.auctionId())).success());
        verify(economy).withdraw(is(buyer), eq(1000.0));
        verify(economy).deposit(is(seller), eq(900.0));
        verify(economy).deposit(argThat(offline -> "ServerBank".equals(offline.getName())), eq(100.0));
        await(() -> { try { return service.history(seller, HistoryView.SOLD, 1).get().totalResults() == 1; } catch (Exception e) { return false; } });
        assertEquals(100.0, service.history(seller, HistoryView.SOLD, 1).get().records().get(0).tax());
    }

    @Test void taxTierIsStoredWhenListingSoOfflineSellersKeepTheirRate() throws Exception {
        config.set("economy.sales-tax.enabled", true);
        config.set("economy.sales-tax.percent", 10.0);
        config.set("economy.sales-tax.permissions.donutauction.tax.vip", 2.0);
        seller.addAttachment(owner, "donutauction.tax.vip", true);
        server.dispatchCommand(seller, "ah sell 500");
        click(seller, SellGui.CONFIRM_SLOT);
        await(() -> cache.sellerListings(seller.getUniqueId()).size() == 1);
        AuctionListing listing = cache.sellerListings(seller.getUniqueId()).get(0);
        assertEquals(0.02, listing.taxRate(), 1e-12);
        var buyer = player("Buyer");
        assertTrue(done(service.purchaseAuction(buyer, listing.auctionId())).success());
        verify(economy).deposit(is(seller), eq(490.0));
    }

    @Test void fullInventoryPurchasesWaitInTheCollectionAndAreCollectedOnce() throws Exception {
        var listing = saved(listing(100));
        var buyer = player("Buyer");
        for (int slot = 0; slot < 36; slot++) buyer.getInventory().setItem(slot, new ItemStack(Material.DIRT, 64));
        ActionResult result = done(service.purchaseAuction(buyer, listing.auctionId()));
        assertTrue(result.success());
        assertTrue(result.message().contains("collect"));
        assertEquals(0, count(buyer, Material.EMERALD));
        assertEquals(1, service.getCollection(buyer.getUniqueId()).size());
        await(() -> { try { return !repository.findById(listing.auctionId()).get().orElseThrow().buyerClaimed(); } catch (Exception e) { return false; } });
        // Nothing is dropped on the ground and nothing is handed over while it does not fit.
        assertFalse(done(service.claimDelivery(buyer, listing.auctionId())).success());
        buyer.getInventory().setItem(0, null);
        assertTrue(done(service.claimDelivery(buyer, listing.auctionId())).success());
        pump();
        assertEquals(2, count(buyer, Material.EMERALD));
        assertFalse(done(service.claimDelivery(buyer, listing.auctionId())).success());
        assertEquals(2, count(buyer, Material.EMERALD));
        assertTrue(service.getCollection(buyer.getUniqueId()).isEmpty());
        // History still shows the purchase.
        assertEquals(1, service.history(buyer, HistoryView.BOUGHT, 1).get().totalResults());
    }

    @Test void yourItemsSummaryCatchesUpWithTradesMadeThisSession() throws Exception {
        var listing = saved(listing(100));
        var buyer = player("Buyer");
        assertTrue(done(service.purchaseAuction(buyer, listing.auctionId())).success());
        await(() -> { try { return service.history(buyer, HistoryView.BOUGHT, 1).get().totalResults() == 1; } catch (Exception e) { return false; } });
        // First open: the figures load in the background and the open menu is updated in place.
        assertTrue(server.dispatchCommand(buyer, "ah collect"));
        assertInstanceOf(PlayerAuctionGui.class, buyer.getOpenInventory().getTopInventory().getHolder());
        await(() -> lore(buyer, PlayerAuctionGui.SUMMARY_SLOT).contains("Items bought: 1"));
        assertTrue(server.dispatchCommand(seller, "ah collect"));
        await(() -> lore(seller, PlayerAuctionGui.SUMMARY_SLOT).contains("Items sold: 1"));
    }

    @Test void biddingHoldsMoneyRefundsTheOutbidPlayerAndTheWinnerCollects() throws Exception {
        var listing = auction(100, 3_600_000L);
        var alice = player("Alice");
        var bob = player("Bob");
        assertFalse(done(service.placeBid(seller, listing.auctionId(), 500)).success());
        assertFalse(done(service.placeBid(alice, listing.auctionId(), 99)).success());
        assertTrue(done(service.placeBid(alice, listing.auctionId(), 100)).success());
        pump();
        verify(economy).withdraw(is(alice), eq(100.0));
        assertFalse(done(service.placeBid(alice, listing.auctionId(), 200)).success(), "leader cannot outbid themselves");
        // The next bid must beat 100 by at least 5% or 1.
        assertEquals(105.0, service.minimumBid(cache.findCached(listing.auctionId())));
        assertFalse(done(service.placeBid(bob, listing.auctionId(), 104)).success());
        assertTrue(done(service.placeBid(bob, listing.auctionId(), 150)).success());
        pump();
        verify(economy).withdraw(is(bob), eq(150.0));
        verify(economy).deposit(is(alice), eq(100.0));
        assertEquals(bob.getUniqueId(), cache.findCached(listing.auctionId()).bid().bidder());
        // Buy It Now purchase of an auction is refused; the seller cannot cancel once there are bids.
        assertFalse(done(service.purchaseAuction(alice, listing.auctionId())).success());
        assertFalse(done(service.cancelAuction(seller, listing.auctionId())).success());

        endNow(listing);
        service.scanAndExpireAuctions();
        await(() -> { try { return repository.findById(listing.auctionId()).get().orElseThrow().status() == AuctionStatus.SOLD; }
                catch (Exception e) { return false; } });
        await(() -> mockingDetails(economy).getInvocations().stream().anyMatch(call -> call.getMethod().getName().equals("deposit")
                && ((org.bukkit.OfflinePlayer) call.getArgument(0)).getUniqueId().equals(seller.getUniqueId())));
        verify(economy, times(1)).deposit(is(seller), eq(150.0));
        // A second scan never pays twice.
        service.scanAndExpireAuctions();
        Thread.sleep(200);
        pump();
        verify(economy, times(1)).deposit(is(seller), eq(150.0));
        // Bob is online, so the item he won was handed to him straight away, exactly once.
        await(() -> count(bob, Material.NETHERITE_INGOT) == 2 && service.getCollection(bob.getUniqueId()).isEmpty());
        assertFalse(done(service.claimDelivery(bob, listing.auctionId())).success());
        assertEquals(2, count(bob, Material.NETHERITE_INGOT));
        await(() -> { try { return service.history(bob, HistoryView.BOUGHT, 1).get().totalResults() == 1; } catch (Exception e) { return false; } });
    }

    @Test void lateBidsExtendTheAuction() throws Exception {
        config.set("bidding.anti-snipe-seconds", 30);
        var listing = auction(100, 61_000L);
        try (var connection = source.getConnection(); var statement = connection.prepareStatement(
                "UPDATE auctions SET expiration_time = ? WHERE auction_id = ?")) {
            statement.setLong(1, System.currentTimeMillis() + 5_000);
            statement.setString(2, listing.auctionId().toString());
            statement.executeUpdate();
        }
        cache.upsert(listing.withExpiration(System.currentTimeMillis() + 5_000, System.currentTimeMillis()));
        var alice = player("Alice");
        assertTrue(done(service.placeBid(alice, listing.auctionId(), 100)).success());
        assertTrue(cache.findCached(listing.auctionId()).expirationTime() >= System.currentTimeMillis() + 25_000);
    }

    @Test void staffRemovalRefundsTheLeaderAndReturnsTheItemToTheSeller() throws Exception {
        var listing = auction(100, 3_600_000L);
        var alice = player("Alice");
        assertTrue(done(service.placeBid(alice, listing.auctionId(), 120)).success());
        assertTrue(done(service.adminRemove(server.getConsoleSender(), listing.auctionId())).success());
        pump();
        verify(economy).deposit(is(alice), eq(120.0));
        AuctionListing removed = cache.findCached(listing.auctionId());
        assertEquals(AuctionStatus.CANCELLED, removed.status());
        assertTrue(removed.isReclaimable());
        assertTrue(done(service.reclaim(seller, listing.auctionId())).success());
        pump();
        assertEquals(2, count(seller, Material.NETHERITE_INGOT));
        assertFalse(done(service.reclaim(seller, listing.auctionId())).success());
    }

    @Test void expiredItemsAreReclaimedOnceOrRelisted() throws Exception {
        long past = System.currentTimeMillis() - 1;
        var first = saved(new AuctionListing(UUID.randomUUID(), new ItemStack(Material.GOLD_INGOT, 5), seller.getUniqueId(), 50,
                past - 1000, past, AuctionStatus.ACTIVE, null, 0, false, past));
        var second = saved(new AuctionListing(UUID.randomUUID(), new ItemStack(Material.IRON_INGOT, 7), seller.getUniqueId(), 70,
                past - 1000, past, AuctionStatus.ACTIVE, null, 0, false, past));
        service.scanAndExpireAuctions();
        await(() -> cache.findCached(first.auctionId()).status() == AuctionStatus.EXPIRED
                && cache.findCached(second.auctionId()).status() == AuctionStatus.EXPIRED);
        assertTrue(done(service.reclaim(seller, first.auctionId())).success());
        pump();
        assertEquals(5, count(seller, Material.GOLD_INGOT));
        assertFalse(done(service.reclaim(seller, first.auctionId())).success());
        assertTrue(done(service.relist(seller, second.auctionId(), 24, gui.limitService())).success());
        AuctionListing relisted = cache.findCached(second.auctionId());
        assertEquals(AuctionStatus.ACTIVE, relisted.status());
        assertEquals(70.0, relisted.price());
        assertTrue(relisted.expirationTime() > System.currentTimeMillis() + 23 * 3_600_000L);
        assertEquals(0, count(seller, Material.IRON_INGOT));
    }

    @Test void blacklistedItemsAndCreativeModeNeverLeaveTheHand() {
        config.set("blacklist.materials", List.of("DIAMOND"));
        server.dispatchCommand(seller, "ah sell 100");
        assertFalse(seller.getOpenInventory().getTopInventory().getHolder() instanceof SellGui);
        assertEquals(3, seller.getInventory().getItemInMainHand().getAmount());

        config.set("blacklist.materials", List.of());
        seller.setGameMode(GameMode.CREATIVE);
        server.dispatchCommand(seller, "ah sell 100");
        assertFalse(seller.getOpenInventory().getTopInventory().getHolder() instanceof SellGui);
        assertEquals(3, seller.getInventory().getItemInMainHand().getAmount());
    }

    @Test void perItemPriceLimitsScaleWithTheStack() {
        config.set("auction.price-limits.DIAMOND.min", 50);
        server.dispatchCommand(seller, "ah sell 149");
        assertFalse(seller.getOpenInventory().getTopInventory().getHolder() instanceof SellGui);
        assertEquals(3, seller.getInventory().getItemInMainHand().getAmount());
        server.dispatchCommand(seller, "ah sell 150");
        assertInstanceOf(SellGui.class, seller.getOpenInventory().getTopInventory().getHolder());
    }

    @Test void searchMatchesLoreEnchantmentsAndSellerAndTypeFilterWorks() throws Exception {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        sword.editMeta(meta -> {
            meta.lore(List.of(Component.text("Legendary blade")));
            meta.addEnchant(Enchantment.DAMAGE_ALL, 5, true);
        });
        long now = System.currentTimeMillis();
        // Search runs in memory; MockBukkit cannot serialize enchanted items, so this one skips the database.
        cache.upsert(new AuctionListing(UUID.randomUUID(), sword, seller.getUniqueId(), 900, now, now + 3_600_000, AuctionStatus.ACTIVE,
                null, 0, false, now, ListingType.BIN, null, "Seller", 0, true, -1));
        saved(listing(10));
        auction(100, 3_600_000L);
        AuctionBrowseRequest all = new AuctionBrowseRequest(1, null, null, "");
        assertEquals(1, service.browse(all.withSearch("legendary")).totalResults());
        assertEquals(1, service.browse(all.withSearch("sharpness")).totalResults());
        assertEquals(1, service.browse(all.withSearch("diamond sword")).totalResults());
        assertEquals(3, service.browse(all.withSearch("seller")).totalResults());
        assertEquals(1, service.browse(all.withListingFilter(ListingFilter.AUCTIONS)).totalResults());
        assertEquals(2, service.browse(all.withListingFilter(ListingFilter.BUY_NOW)).totalResults());
    }

    @Test void sellerViewAndHelpCommandsWork() throws Exception {
        saved(listing(10));
        var buyer = player("Buyer");
        server.dispatchCommand(buyer, "ah view Seller");
        assertInstanceOf(AuctionGui.class, buyer.getOpenInventory().getTopInventory().getHolder());
        assertEquals(seller.getUniqueId(), gui.session(buyer).request().seller());
        server.dispatchCommand(buyer, "ah help");
        server.dispatchCommand(buyer, "ah collect");
        assertInstanceOf(PlayerAuctionGui.class, buyer.getOpenInventory().getTopInventory().getHolder());
    }

    @Test void apiListingsAppearForEveryoneAndPlayersGetTheirSaleTold() throws Exception {
        AuctionListing listing = service.createServerListing(UUID.randomUUID(), "Crates", new ItemStack(Material.ELYTRA), 5000,
                Duration.ofHours(1).toMillis(), ListingType.BIN).get(5, TimeUnit.SECONDS);
        assertEquals(1, service.browse(new AuctionBrowseRequest(1, null, null, "elytra")).totalResults());
        var buyer = player("Buyer");
        assertTrue(done(service.purchaseAuction(buyer, listing.auctionId())).success());
        assertEquals(1, count(buyer, Material.ELYTRA));
        assertNull(cache.findCached(listing.auctionId()));
    }

    @Test void failedWithdrawOfABidKeepsTheAuctionUntouched() throws Exception {
        var listing = auction(100, 3_600_000L);
        var alice = player("Alice");
        when(economy.withdraw(is(alice), anyDouble())).thenReturn(new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "no"));
        assertFalse(done(service.placeBid(alice, listing.auctionId(), 100)).success());
        assertFalse(cache.findCached(listing.auctionId()).bid().present());
        assertEquals(0, Integer.parseInt(String.valueOf(repository.findById(listing.auctionId()).get().orElseThrow().bid().count())));
    }
}
