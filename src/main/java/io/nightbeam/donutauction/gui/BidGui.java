package io.nightbeam.donutauction.gui;

import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.service.ActionResult;
import io.nightbeam.donutauction.service.AuctionService;
import io.nightbeam.donutauction.util.ItemBuilder;
import io.nightbeam.donutauction.util.MessageUtil;
import io.nightbeam.donutauction.util.PriceParser;
import io.nightbeam.donutauction.util.TimeUtil;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Bidding on an auction: the minimum next bid, two quick raises or a typed amount. */
public final class BidGui extends BaseGui {

    static final int ITEM_SLOT = 13;
    static final int MIN_SLOT = 29;
    static final int RAISE_SLOT = 30;
    static final int BIG_RAISE_SLOT = 32;
    static final int CUSTOM_SLOT = 33;
    static final int BACK_SLOT = 40;

    private final GuiManager guiManager;
    private final AuctionService auctionService;
    private final AuctionListing listing;
    private final Map<Integer, Double> amounts = new HashMap<>();

    public BidGui(GuiManager guiManager, AuctionService auctionService, AuctionListing listing) {
        this.guiManager = guiManager;
        this.auctionService = auctionService;
        this.listing = listing;
    }

    private MessageUtil messages() {
        return guiManager.plugin().messages();
    }

    private AuctionListing current() {
        return auctionService.findListing(listing.auctionId()).orElse(listing);
    }

    @Override
    public Inventory render(Player player) {
        AuctionListing live = current();
        Inventory inventory = attach(Bukkit.createInventory(this, 45, messages().component("gui.titles.bid", "&6ᴘʟᴀᴄᴇ ᴀ ʙɪᴅ")));
        amounts.clear();
        double minimum = auctionService.minimumBid(live);
        double balance = auctionService.getBalance(player);

        ItemStack display = live.item().clone();
        display.editMeta(meta -> {
            List<Component> lore = new ArrayList<>();
            if (meta.hasLore() && meta.lore() != null) {
                lore.addAll(meta.lore());
                lore.add(Component.empty());
            }
            if (live.bid().present()) {
                lore.add(MessageUtil.loreLine(messages().component("gui.listing-lore.current-bid", "&7Current bid: &6%price% &8(%bids% bids)",
                        "price", auctionService.formatPrecisePrice(live.bid().amount()), "bids", String.valueOf(live.bid().count()))));
                lore.add(MessageUtil.loreLine(messages().component("gui.listing-lore.top-bidder", "&7Top bidder: &f%bidder%",
                        "bidder", live.bid().bidderName() == null ? "?" : live.bid().bidderName())));
            } else {
                lore.add(MessageUtil.loreLine(messages().component("gui.listing-lore.starting-bid", "&7Starting bid: &6%price%",
                        "price", auctionService.formatPrecisePrice(live.price()))));
            }
            lore.add(MessageUtil.loreLine(messages().component("gui.listing-lore.seller", "&7Seller: &f%seller%",
                    "seller", guiManager.sellerName(live))));
            lore.add(MessageUtil.loreLine(messages().component("gui.listing-lore.ends-in", "&7Ends in: &f%time%",
                    "time", TimeUtil.formatDuration(live.expirationTime() - System.currentTimeMillis()))));
            lore.add(MessageUtil.loreLine(messages().component("gui.listing-lore.balance", "&bYour balance: %balance%",
                    "balance", auctionService.formatPrice(balance))));
            meta.lore(lore);
        });
        inventory.setItem(ITEM_SLOT, display);

        bidButton(inventory, MIN_SLOT, Material.GOLD_NUGGET, "gui.bid.minimum", "&eBid %price%", minimum, balance);
        bidButton(inventory, RAISE_SLOT, Material.GOLD_INGOT, "gui.bid.raise", "&eBid %price% &7(+10%)", round(minimum * 1.10D), balance);
        bidButton(inventory, BIG_RAISE_SLOT, Material.GOLD_BLOCK, "gui.bid.big-raise", "&eBid %price% &7(+25%)", round(minimum * 1.25D), balance);
        inventory.setItem(CUSTOM_SLOT, ItemBuilder.of(Material.OAK_SIGN)
                .name(messages().component("gui.bid.custom", "&eCustom Amount"))
                .lore(messages().component("gui.bid.custom-lore", "Type any amount of at least %price%",
                        "price", auctionService.formatPrice(minimum)))
                .build());
        inventory.setItem(BACK_SLOT, ItemBuilder.of(Material.ARROW)
                .name(messages().component("gui.common.back-to-auction", "&eBack to Auction"))
                .build());
        inventory.setItem(31, ItemBuilder.of(Material.BOOK)
                .name(messages().component("gui.bid.how", "&bHow bidding works"))
                .lore(
                        messages().component("gui.bid.how-1", "Your bid is held until you are outbid"),
                        messages().component("gui.bid.how-2", "If someone outbids you, it is refunded at once"),
                        messages().component("gui.bid.how-3", "Late bids extend the auction by %seconds%s",
                                "seconds", String.valueOf(guiManager.plugin().getConfig().getLong("bidding.anti-snipe-seconds", 30))),
                        messages().component("gui.bid.how-4", "The winner collects the item in /ah collect"))
                .build());
        fill(inventory);
        return inventory;
    }

    private void bidButton(Inventory inventory, int slot, Material material, String path, String fallback, double amount, double balance) {
        amounts.put(slot, amount);
        boolean affordable = balance + 1e-9 >= amount;
        inventory.setItem(slot, ItemBuilder.of(material)
                .name(messages().component(path, fallback, "price", auctionService.formatPrice(amount)))
                .lore(affordable
                        ? messages().component("gui.bid.click", "&aClick to bid")
                        : messages().component("gui.confirm-purchase.cannot-afford", "&cYou cannot afford this"))
                .build());
    }

    private static double round(double amount) {
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    @Override
    public void handleClick(Player player, InventoryClickEvent event) {
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        int slot = event.getSlot();
        if (slot == BACK_SLOT) {
            guiManager.click(player);
            guiManager.refreshAuctionHouse(player);
            return;
        }
        if (slot == CUSTOM_SLOT) {
            guiManager.click(player);
            guiManager.beginInput(player, "gui.bid.prompt", "&eType your bid in chat (for example &61500 &eor &62.5k&e). Type &6cancel &eto go back.",
                    (who, text) -> {
                        OptionalDouble amount = PriceParser.parse(text, true);
                        if (amount.isEmpty()) {
                            messages().sendRaw(who, "command.invalid-price", "&cInvalid price.");
                            guiManager.openBid(who, current());
                            return;
                        }
                        bid(who, amount.getAsDouble());
                    },
                    () -> guiManager.openBid(player, current()));
            return;
        }
        Double amount = amounts.get(slot);
        if (amount != null) {
            bid(player, amount);
        }
    }

    private void bid(Player player, double amount) {
        auctionService.placeBid(player, listing.auctionId(), amount).thenAccept(result -> guiManager.plugin().schedulerAdapter()
                .runEntity(player, () -> afterBid(player, result)));
    }

    private void afterBid(Player player, ActionResult result) {
        messages().send(player, result.message());
        guiManager.feedback(player, result);
        AuctionListing live = current();
        if (live.isActive(System.currentTimeMillis())) {
            guiManager.openBid(player, live);
        } else {
            guiManager.refreshAuctionHouse(player);
        }
    }
}
