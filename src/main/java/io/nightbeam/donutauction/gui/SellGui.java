package io.nightbeam.donutauction.gui;

import io.nightbeam.donutauction.model.ListingType;
import io.nightbeam.donutauction.model.MarketStats;
import io.nightbeam.donutauction.model.PendingSaleTransaction;
import io.nightbeam.donutauction.model.PlayerPreference;
import io.nightbeam.donutauction.service.AuctionService;
import io.nightbeam.donutauction.service.PlayerPreferenceManager;
import io.nightbeam.donutauction.util.ItemBuilder;
import io.nightbeam.donutauction.util.MessageUtil;
import io.nightbeam.donutauction.util.PriceParser;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class SellGui extends BaseGui {

    static final int CONFIRM_SLOT = 40;
    static final int CANCEL_SLOT = 44;
    static final int PRICE_DISPLAY_SLOT = 13;
    static final int PRICE_DECREASE_SLOT = 12;
    static final int PRICE_INCREASE_SLOT = 14;
    static final int TYPE_SLOT = 29;
    static final int INSIGHT_SLOT = 31;
    static final int FEES_SLOT = 33;
    static final int FIRST_DURATION_SLOT = 19;

    private final GuiManager guiManager;
    private final AuctionService auctionService;
    private final PlayerPreferenceManager preferenceManager;
    private final UUID transactionId;
    private final ItemStack displayItem;
    private final List<Integer> durations;
    private double price;
    private int selectedDurationIndex;
    private ListingType type;
    /** Prevents concurrent click handlers from racing before the atomic claim completes. */
    private final AtomicBoolean settlementStarted = new AtomicBoolean(false);
    /** Set while re-opening this GUI (duration/category/price refresh) to avoid cancelling the pending sale. */
    private final AtomicBoolean reopening = new AtomicBoolean(false);

    public SellGui(GuiManager guiManager, AuctionService auctionService, PlayerPreferenceManager preferenceManager,
                   PendingSaleTransaction transaction, PlayerPreference preference) {
        this(guiManager, auctionService, preferenceManager, transaction, preference, ListingType.BIN);
    }

    public SellGui(GuiManager guiManager, AuctionService auctionService, PlayerPreferenceManager preferenceManager,
                   PendingSaleTransaction transaction, PlayerPreference preference, ListingType type) {
        this.guiManager = guiManager;
        this.auctionService = auctionService;
        this.preferenceManager = preferenceManager;
        this.transactionId = transaction.transactionId();
        this.displayItem = transaction.item();
        this.type = type == null ? ListingType.BIN : type;
        this.durations = auctionService.durationOptions();
        this.price = clamp(transaction.price());

        int preferred = preference != null ? preference.lastDurationHours()
                : guiManager.plugin().getConfig().getInt("auction.listing-duration-hours", 48);
        int index = durations.indexOf(preferred);
        if (index < 0) index = durations.indexOf(guiManager.plugin().getConfig().getInt("auction.listing-duration-hours", 48));
        this.selectedDurationIndex = index < 0 ? durations.size() / 2 : index;
    }

    private MessageUtil messages() {
        return guiManager.plugin().messages();
    }

    private double clamp(double value) {
        double min = auctionService.rules().minPrice(displayItem);
        double max = auctionService.rules().maxPrice(displayItem);
        return Math.max(min, Math.min(max, value));
    }

    private int hours() {
        return durations.get(Math.max(0, Math.min(durations.size() - 1, selectedDurationIndex)));
    }

    @Override
    public Inventory render(Player player) {
        Inventory inventory = attach(Bukkit.createInventory(this, 45,
                messages().component(type == ListingType.BID ? "gui.titles.sell-auction" : "gui.titles.sell",
                        type == ListingType.BID ? "&6ꜱᴛᴀʀᴛ ᴀᴜᴄᴛɪᴏɴ" : "&6ꜱᴇʟʟ ɪᴛᴇᴍ")));

        inventory.setItem(4, displayItem.clone());

        for (int i = 0; i < durations.size(); i++) {
            boolean selected = i == selectedDurationIndex;
            int hours = durations.get(i);
            String durationLabel = messages().durationLabel(hours);
            inventory.setItem(FIRST_DURATION_SLOT + i, ItemBuilder.of(selected ? Material.LIME_STAINED_GLASS_PANE : Material.CLOCK)
                    .name(messages().component((selected ? "&a" : "&f") + durationLabel))
                    .lore(selected
                            ? messages().component("&a" + messages().raw("gui.common.selected", "Selected"))
                            : messages().component("&7" + messages().raw("gui.common.click-to-select", "Click to select")))
                    .amount(hours < 24 ? hours : hours / 24)
                    .glow(selected)
                    .build());
        }

        String step = auctionService.formatPrice(priceStep(price));
        String bigStep = auctionService.formatPrice(priceStep(price) * 10);
        String durationLabel = messages().durationLabel(hours());
        String formattedPrice = auctionService.formatPrecisePrice(price);

        inventory.setItem(PRICE_DECREASE_SLOT, ItemBuilder.of(Material.RED_STAINED_GLASS_PANE)
                .name(messages().component("gui.sell.decrease-price", "&cDecrease Price"))
                .lore(messages().component("gui.common.step", "Step: %step%", "step", step),
                        messages().component("gui.sell.shift-step", "&7Shift-click: %step%", "step", bigStep))
                .build());

        inventory.setItem(PRICE_DISPLAY_SLOT, ItemBuilder.of(Material.GOLD_INGOT)
                .name(messages().component(type == ListingType.BID ? "gui.sell.starting-bid-display" : "gui.sell.price-display",
                        type == ListingType.BID ? "&6Starting bid: %price%" : "&6Price: %price%", "price", formattedPrice))
                .lore(
                        messages().component("gui.common.duration-label", "Duration: %duration%", "duration", durationLabel),
                        messages().component("gui.common.click-adjust-price", "Click +/- to adjust"),
                        messages().component("gui.sell.type-price", "&eClick to type an exact price (1k, 2.5m...)")
                )
                .build());

        inventory.setItem(PRICE_INCREASE_SLOT, ItemBuilder.of(Material.LIME_STAINED_GLASS_PANE)
                .name(messages().component("gui.sell.increase-price", "&aIncrease Price"))
                .lore(messages().component("gui.common.step", "Step: %step%", "step", step),
                        messages().component("gui.sell.shift-step", "&7Shift-click: %step%", "step", bigStep))
                .build());

        if (auctionService.canStartAuctions(player)) {
            inventory.setItem(TYPE_SLOT, ItemBuilder.of(type == ListingType.BID ? Material.BELL : Material.EMERALD)
                    .name(messages().component(type == ListingType.BID ? "gui.sell.type-auction" : "gui.sell.type-bin",
                            type == ListingType.BID ? "&6Auction" : "&aBuy It Now"))
                    .lore(
                            messages().component(type == ListingType.BID ? "gui.sell.type-auction-lore" : "gui.sell.type-bin-lore",
                                    type == ListingType.BID ? "Starts at your price; the highest bid wins" : "Buyers pay your price instantly"),
                            messages().component("gui.sell.type-switch", "&eClick to switch"))
                    .glow(type == ListingType.BID)
                    .build());
        }

        inventory.setItem(INSIGHT_SLOT, insightItem(auctionService.market().cachedStats(displayItem.getType().name())));
        inventory.setItem(FEES_SLOT, feesItem(player));

        List<Component> confirmLore = new ArrayList<>();
        confirmLore.add(messages().component(type == ListingType.BID ? "gui.sell.start-at-price" : "gui.sell.list-for-price",
                type == ListingType.BID ? "Start the auction at %price%" : "List this item for %price%", "price", formattedPrice));
        confirmLore.add(messages().component("gui.common.duration-label", "Duration: %duration%", "duration", durationLabel));
        double fee = auctionService.fees().listingFee(player, price);
        if (fee > 0.0D) {
            confirmLore.add(messages().component("gui.sell.fee-line", "&7Listing fee: &c%fee%", "fee", auctionService.formatPrice(fee)));
        }
        inventory.setItem(CONFIRM_SLOT, ItemBuilder.of(Material.LIME_STAINED_GLASS_PANE)
                .name(messages().component("gui.sell.confirm-listing", "&aConfirm Listing"))
                .lore(confirmLore)
                .build());

        inventory.setItem(CANCEL_SLOT, ItemBuilder.of(Material.RED_STAINED_GLASS_PANE)
                .name(messages().component("gui.common.cancel", "&cCancel"))
                .lore(messages().component("gui.sell.cancel-lore", "Go back without listing"))
                .build());

        fill(inventory);
        loadInsight(player, inventory);
        return inventory;
    }

    private ItemStack insightItem(MarketStats stats) {
        String material = displayItem.getType().name();
        int amount = Math.max(1, displayItem.getAmount());
        List<Component> lore = new ArrayList<>();
        if (!auctionService.market().enabled()) {
            lore.add(messages().component("gui.sell.insight-disabled", "&7Price insight is turned off"));
        } else if (stats.hasSales()) {
            lore.add(messages().component("gui.sell.insight-sales", "&7Sold in the last %days% days: &f%sales%",
                    "days", String.valueOf(stats.since() == 0 ? guiManager.plugin().getConfig().getLong("market.window-days", 7)
                            : Math.max(1, (System.currentTimeMillis() - stats.since()) / 86_400_000L)),
                    "sales", String.valueOf(stats.sales())));
            lore.add(messages().component("gui.sell.insight-average", "&7Average: &6%price% &7each",
                    "price", auctionService.formatPrice(stats.averageUnit())));
            lore.add(messages().component("gui.sell.insight-range", "&7Range: &f%low% &7- &f%high%",
                    "low", auctionService.formatPrice(stats.lowestUnit()), "high", auctionService.formatPrice(stats.highestUnit())));
            lore.add(messages().component("gui.sell.insight-last", "&7Last sale: &f%price% &7each",
                    "price", auctionService.formatPrice(stats.lastUnit())));
        } else {
            lore.add(messages().component("gui.sell.insight-none", "&7No recent sales of this item"));
        }
        OptionalDouble lowest = auctionService.market().lowestActiveUnitPrice(material);
        if (lowest.isPresent()) {
            lore.add(messages().component("gui.sell.insight-lowest", "&7Cheapest on sale: &a%price% &7each",
                    "price", auctionService.formatPrice(lowest.getAsDouble())));
        }
        if (amount > 1) {
            lore.add(messages().component("gui.sell.insight-yours", "&7Your price: &e%price% &7each",
                    "price", auctionService.formatPrice(price / amount)));
        }
        return ItemBuilder.of(Material.SPYGLASS)
                .name(messages().component("gui.sell.insight", "&bPrice Insight"))
                .lore(lore)
                .build();
    }

    private void loadInsight(Player player, Inventory inventory) {
        if (!auctionService.market().enabled()) return;
        auctionService.market().stats(displayItem.getType().name()).thenAccept(stats ->
                guiManager.plugin().schedulerAdapter().runEntity(player, () -> {
                    if (player.getOpenInventory().getTopInventory().getHolder(false) == this && getInventory() == inventory) {
                        inventory.setItem(INSIGHT_SLOT, insightItem(stats));
                    }
                }));
    }

    private ItemStack feesItem(Player player) {
        double fee = auctionService.fees().listingFee(player, price);
        double rate = auctionService.fees().taxRate(player);
        double tax = auctionService.fees().tax(price, rate);
        List<Component> lore = new ArrayList<>();
        lore.add(messages().component("gui.sell.fees-listing", "&7Listing fee: &f%fee%",
                "fee", fee > 0.0D ? auctionService.formatPrice(fee) : messages().raw("gui.sell.free", "Free")));
        lore.add(messages().component("gui.sell.fees-tax", "&7Sales tax: &f%percent%%",
                "percent", trim(rate * 100.0D)));
        lore.add(messages().component(type == ListingType.BID ? "gui.sell.fees-receive-bid" : "gui.sell.fees-receive",
                type == ListingType.BID ? "&7You receive: &aat least %amount%" : "&7You receive: &a%amount%",
                "amount", auctionService.formatPrice(price - tax)));
        return ItemBuilder.of(Material.PAPER)
                .name(messages().component("gui.sell.fees", "&eFees & Earnings"))
                .lore(lore)
                .build();
    }

    private static String trim(double value) {
        String text = String.format(java.util.Locale.ROOT, "%.2f", value);
        return text.replaceAll("\\.?0+$", "");
    }

    @Override
    public void handleClick(Player player, InventoryClickEvent event) {
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        if (settlementStarted.get()) {
            return;
        }

        int slot = event.getSlot();

        if (slot == CONFIRM_SLOT) {
            if (!settlementStarted.compareAndSet(false, true)) {
                return;
            }

            int durationHours = hours();
            double listingPrice = price;
            ListingType listingType = type;

            PlayerPreference pref = preferenceManager.getCached(player.getUniqueId());
            if (pref != null) {
                pref.lastDurationHours(durationHours);
                pref.lastPrice(listingPrice);
                preferenceManager.save(pref);
            }

            auctionService.confirmPendingSale(player, transactionId, durationHours, listingPrice, listingType).thenAccept(result ->
                    guiManager.plugin().schedulerAdapter().runEntity(player, () -> {
                        guiManager.plugin().messages().send(player, result.message());
                        guiManager.feedback(player, result);
                        if (result.success()) {
                            guiManager.openPlayerItems(player);
                        } else {
                            guiManager.openAuctionHouse(player);
                        }
                    })
            );
            return;
        }

        if (slot == CANCEL_SLOT) {
            if (!settlementStarted.compareAndSet(false, true)) {
                return;
            }
            guiManager.click(player);
            guiManager.plugin().schedulerAdapter().runEntity(player, () -> {
                auctionService.cancelPendingSale(player, transactionId);
                guiManager.openAuctionHouse(player);
            });
            return;
        }

        if (slot == PRICE_DECREASE_SLOT || slot == PRICE_INCREASE_SLOT) {
            double change = priceStep(price) * (event.isShiftClick() ? 10 : 1);
            price = clamp(slot == PRICE_DECREASE_SLOT ? price - change : price + change);
            refresh(player);
            return;
        }

        if (slot == PRICE_DISPLAY_SLOT) {
            guiManager.click(player);
            reopen();
            guiManager.beginInput(player, "gui.sell.price-prompt", "&eType the price in chat (for example &6250&e, &61.5k &eor &62m&e). Type &6cancel &eto go back.",
                    (who, text) -> {
                        OptionalDouble parsed = PriceParser.parse(text,
                                guiManager.plugin().getConfig().getBoolean("auction.allow-shorthand-prices", true));
                        if (parsed.isEmpty()) {
                            guiManager.plugin().messages().sendRaw(who, "command.invalid-price", "&cInvalid price.");
                        } else if (clamp(parsed.getAsDouble()) != parsed.getAsDouble()) {
                            guiManager.plugin().messages().sendFormatted(who, "gui.sell.price-out-of-range",
                                    "&cThe price must be between &6%min% &cand &6%max%&c.",
                                    "min", auctionService.formatPrice(auctionService.rules().minPrice(displayItem)),
                                    "max", auctionService.formatPrice(auctionService.rules().maxPrice(displayItem)));
                        } else {
                            price = parsed.getAsDouble();
                        }
                        guiManager.openSellGui(who, this);
                    },
                    () -> guiManager.openSellGui(player, this));
            return;
        }

        if (slot == TYPE_SLOT && auctionService.canStartAuctions(player)) {
            type = type == ListingType.BID ? ListingType.BIN : ListingType.BID;
            refresh(player);
            return;
        }

        if (slot >= FIRST_DURATION_SLOT && slot < FIRST_DURATION_SLOT + durations.size()) {
            selectedDurationIndex = slot - FIRST_DURATION_SLOT;
            refresh(player);
        }
    }

    private void refresh(Player player) {
        guiManager.click(player);
        reopen();
        guiManager.plugin().schedulerAdapter().runEntity(player, () -> guiManager.openSellGui(player, this));
    }

    static double priceStep(double price) {
        if (price >= 1_000_000.0D) {
            return 100_000.0D;
        }
        if (price >= 100_000.0D) {
            return 10_000.0D;
        }
        if (price >= 10_000.0D) {
            return 1_000.0D;
        }
        if (price >= 1000.0D) {
            return 100.0D;
        }
        if (price >= 100.0D) {
            return 10.0D;
        }
        if (price >= 10.0D) {
            return 1.0D;
        }
        return 0.1D;
    }

    private void reopen() {
        reopening.set(true);
    }

    public boolean consumeReopening() {
        return reopening.getAndSet(false);
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public boolean isSettlementStarted() {
        return settlementStarted.get();
    }

    public ItemStack getDisplayItem() {
        return displayItem.clone();
    }
}
