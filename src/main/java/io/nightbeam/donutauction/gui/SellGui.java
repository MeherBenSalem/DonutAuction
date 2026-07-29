package io.nightbeam.donutauction.gui;

import io.nightbeam.donutauction.model.AuctionFilterCategory;
import io.nightbeam.donutauction.model.PendingSaleTransaction;
import io.nightbeam.donutauction.model.PlayerPreference;
import io.nightbeam.donutauction.service.AuctionService;
import io.nightbeam.donutauction.service.PlayerPreferenceManager;
import io.nightbeam.donutauction.util.ItemBuilder;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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

    private static final Component TITLE = Component.text("ꜱᴇʟʟ ɪᴛᴇᴍ", NamedTextColor.GOLD);

    private static final int[] DURATION_OPTIONS = {6, 12, 24, 48, 72, 168};
    private static final String[] DURATION_LABELS = {"6 Hours", "12 Hours", "1 Day", "2 Days", "3 Days", "1 Week"};

    private final GuiManager guiManager;
    private final AuctionService auctionService;
    private final PlayerPreferenceManager preferenceManager;
    private final UUID transactionId;
    private final ItemStack displayItem;
    private double price;
    private int selectedDurationIndex = 3;
    private AuctionFilterCategory selectedCategory = AuctionFilterCategory.ALL;
    /** Prevents concurrent click handlers from racing before the atomic claim completes. */
    private final AtomicBoolean settlementStarted = new AtomicBoolean(false);
    /** Set while re-opening this GUI (duration/category/price refresh) to avoid cancelling the pending sale. */
    private final AtomicBoolean reopening = new AtomicBoolean(false);

    public SellGui(GuiManager guiManager, AuctionService auctionService, PlayerPreferenceManager preferenceManager,
                   PendingSaleTransaction transaction, PlayerPreference preference) {
        this.guiManager = guiManager;
        this.auctionService = auctionService;
        this.preferenceManager = preferenceManager;
        this.transactionId = transaction.transactionId();
        this.displayItem = transaction.item();
        this.price = auctionService.clampListingPrice(transaction.price());

        if (preference != null) {
            int storedDuration = preference.lastDurationHours();
            for (int i = 0; i < DURATION_OPTIONS.length; i++) {
                if (DURATION_OPTIONS[i] == storedDuration) {
                    selectedDurationIndex = i;
                    break;
                }
            }
            try {
                selectedCategory = AuctionFilterCategory.valueOf(preference.lastCategory());
            } catch (IllegalArgumentException ignored) {
                selectedCategory = AuctionFilterCategory.ALL;
            }
        }
    }

    @Override
    public Inventory render(Player player) {
        Inventory inventory = attach(Bukkit.createInventory(this, 45, TITLE));

        inventory.setItem(4, displayItem.clone());

        for (int i = 0; i < DURATION_OPTIONS.length; i++) {
            boolean selected = i == selectedDurationIndex;
            Material material = selected ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;
            inventory.setItem(18 + i, ItemBuilder.of(material)
                    .name(Component.text(DURATION_LABELS[i], selected ? NamedTextColor.GREEN : NamedTextColor.WHITE))
                    .lore(selected ? Component.text("Selected", NamedTextColor.GREEN) : Component.text("Click to select", NamedTextColor.GRAY))
                    .build());
        }

        AuctionFilterCategory[] categories = categoryOptions();

        for (int i = 0; i < categories.length; i++) {
            AuctionFilterCategory cat = categories[i];
            boolean selected = cat == selectedCategory;
            inventory.setItem(27 + i, ItemBuilder.of(selected ? Material.LIME_STAINED_GLASS_PANE : cat.icon())
                    .name(Component.text(cat.displayName(), selected ? NamedTextColor.GREEN : NamedTextColor.WHITE))
                    .lore(selected ? Component.text("Selected", NamedTextColor.GREEN) : Component.text("Click to select", NamedTextColor.GRAY))
                    .build());
        }

        inventory.setItem(PRICE_DECREASE_SLOT, ItemBuilder.of(Material.RED_STAINED_GLASS_PANE)
                .name(Component.text("Decrease Price", NamedTextColor.RED))
                .lore(Component.text("Step: " + auctionService.formatPrice(priceStep(price)), NamedTextColor.GRAY))
                .build());

        inventory.setItem(PRICE_DISPLAY_SLOT, ItemBuilder.of(Material.GOLD_INGOT)
                .name(Component.text("Price: " + auctionService.formatPrice(price), NamedTextColor.GOLD))
                .lore(
                        Component.text("Duration: " + DURATION_LABELS[selectedDurationIndex], NamedTextColor.GRAY),
                        Component.text("Click +/- to adjust", NamedTextColor.DARK_GRAY)
                )
                .build());

        inventory.setItem(PRICE_INCREASE_SLOT, ItemBuilder.of(Material.LIME_STAINED_GLASS_PANE)
                .name(Component.text("Increase Price", NamedTextColor.GREEN))
                .lore(Component.text("Step: " + auctionService.formatPrice(priceStep(price)), NamedTextColor.GRAY))
                .build());

        inventory.setItem(CONFIRM_SLOT, ItemBuilder.of(Material.LIME_STAINED_GLASS_PANE)
                .name(Component.text("Confirm Listing", NamedTextColor.GREEN))
                .lore(
                        Component.text("List this item for " + auctionService.formatPrice(price), NamedTextColor.GRAY),
                        Component.text("Duration: " + DURATION_LABELS[selectedDurationIndex], NamedTextColor.GRAY)
                )
                .build());

        inventory.setItem(CANCEL_SLOT, ItemBuilder.of(Material.RED_STAINED_GLASS_PANE)
                .name(Component.text("Cancel", NamedTextColor.RED))
                .lore(Component.text("Go back without listing", NamedTextColor.GRAY))
                .build());

        return inventory;
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

            int durationHours = DURATION_OPTIONS[selectedDurationIndex];
            AuctionFilterCategory category = selectedCategory;
            double listingPrice = price;

            PlayerPreference pref = preferenceManager.getCached(player.getUniqueId());
            if (pref != null) {
                pref.lastDurationHours(durationHours);
                pref.lastCategory(category.name());
                pref.lastPrice(listingPrice);
                preferenceManager.save(pref);
            }

            auctionService.confirmPendingSale(player, transactionId, durationHours, category, listingPrice).thenAccept(result ->
                    guiManager.plugin().schedulerAdapter().runEntity(player, () -> {
                        guiManager.plugin().messages().send(player, result.message());
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
            guiManager.plugin().schedulerAdapter().runEntity(player, () -> {
                auctionService.cancelPendingSale(player, transactionId);
                guiManager.openAuctionHouse(player);
            });
            return;
        }

        if (slot == PRICE_DECREASE_SLOT) {
            price = auctionService.clampListingPrice(price - priceStep(price));
            reopen();
            guiManager.plugin().schedulerAdapter().runEntity(player, () -> guiManager.openSellGui(player, this));
            return;
        }

        if (slot == PRICE_INCREASE_SLOT) {
            price = auctionService.clampListingPrice(price + priceStep(price));
            reopen();
            guiManager.plugin().schedulerAdapter().runEntity(player, () -> guiManager.openSellGui(player, this));
            return;
        }

        if (slot >= 18 && slot < 18 + DURATION_OPTIONS.length) {
            selectedDurationIndex = slot - 18;
            reopen();
            guiManager.plugin().schedulerAdapter().runEntity(player, () -> guiManager.openSellGui(player, this));
            return;
        }

        AuctionFilterCategory[] categories = categoryOptions();

        if (slot >= 27 && slot < 27 + categories.length) {
            selectedCategory = categories[slot - 27];
            reopen();
            guiManager.plugin().schedulerAdapter().runEntity(player, () -> guiManager.openSellGui(player, this));
        }
    }

    static double priceStep(double price) {
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

    private static AuctionFilterCategory[] categoryOptions() {
        return new AuctionFilterCategory[]{
                AuctionFilterCategory.ALL, AuctionFilterCategory.BLOCKS, AuctionFilterCategory.TOOLS,
                AuctionFilterCategory.FOOD, AuctionFilterCategory.COMBAT, AuctionFilterCategory.POTIONS,
                AuctionFilterCategory.BOOKS, AuctionFilterCategory.INGREDIENTS, AuctionFilterCategory.UTILITIES
        };
    }
}
