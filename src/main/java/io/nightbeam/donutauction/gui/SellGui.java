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

    private static final Component TITLE = Component.text("ꜱᴇʟʟ ɪᴛᴇᴍ", NamedTextColor.GOLD);

    private static final int[] DURATION_OPTIONS = {6, 12, 24, 48, 72, 168};
    private static final String[] DURATION_LABELS = {"6 Hours", "12 Hours", "1 Day", "2 Days", "3 Days", "1 Week"};

    private static final int INVENTORY_SIZE = 45;
    private static final int DISPLAY_SLOT = 4;
    private static final int PRICE_SLOT = 13;
    private static final int DURATION_SLOT_START = 18;
    private static final int CATEGORY_SLOT_START = 27;
    // Buttons live in the bottom row (36-44) so they never overlap the category row (27-35).
    private static final int CONFIRM_SLOT = 40;
    private static final int CANCEL_SLOT = 44;

    private static final AuctionFilterCategory[] CATEGORIES = {
            AuctionFilterCategory.ALL, AuctionFilterCategory.BLOCKS, AuctionFilterCategory.TOOLS,
            AuctionFilterCategory.FOOD, AuctionFilterCategory.COMBAT, AuctionFilterCategory.POTIONS,
            AuctionFilterCategory.BOOKS, AuctionFilterCategory.INGREDIENTS, AuctionFilterCategory.UTILITIES
    };

    private final GuiManager guiManager;
    private final AuctionService auctionService;
    private final PlayerPreferenceManager preferenceManager;
    private final UUID transactionId;
    private final ItemStack displayItem;
    private final double price;
    private int selectedDurationIndex = 3;
    private AuctionFilterCategory selectedCategory = AuctionFilterCategory.ALL;
    /** Prevents concurrent click handlers from racing before the atomic claim completes. */
    private final AtomicBoolean settlementStarted = new AtomicBoolean(false);
    /** Set right before a programmatic re-open (duration/category change) so the close handler does not settle. */
    private final AtomicBoolean reopening = new AtomicBoolean(false);

    public SellGui(GuiManager guiManager, AuctionService auctionService, PlayerPreferenceManager preferenceManager,
                   PendingSaleTransaction transaction, PlayerPreference preference) {
        this.guiManager = guiManager;
        this.auctionService = auctionService;
        this.preferenceManager = preferenceManager;
        this.transactionId = transaction.transactionId();
        this.displayItem = transaction.item();
        this.price = transaction.price();

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
        Inventory inventory = attach(Bukkit.createInventory(this, INVENTORY_SIZE, TITLE));

        inventory.setItem(DISPLAY_SLOT, displayItem.clone());

        for (int i = 0; i < DURATION_OPTIONS.length; i++) {
            boolean selected = i == selectedDurationIndex;
            Material material = selected ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;
            inventory.setItem(DURATION_SLOT_START + i, ItemBuilder.of(material)
                    .name(Component.text(DURATION_LABELS[i], selected ? NamedTextColor.GREEN : NamedTextColor.WHITE))
                    .lore(selected ? Component.text("Selected", NamedTextColor.GREEN) : Component.text("Click to select", NamedTextColor.GRAY))
                    .build());
        }

        for (int i = 0; i < CATEGORIES.length; i++) {
            AuctionFilterCategory cat = CATEGORIES[i];
            boolean selected = cat == selectedCategory;
            inventory.setItem(CATEGORY_SLOT_START + i, ItemBuilder.of(selected ? Material.LIME_STAINED_GLASS_PANE : cat.icon())
                    .name(Component.text(cat.displayName(), selected ? NamedTextColor.GREEN : NamedTextColor.WHITE))
                    .lore(selected ? Component.text("Selected", NamedTextColor.GREEN) : Component.text("Click to select", NamedTextColor.GRAY))
                    .build());
        }

        inventory.setItem(PRICE_SLOT, ItemBuilder.of(Material.GOLD_INGOT)
                .name(Component.text("Price: " + auctionService.formatPrice(price), NamedTextColor.GOLD))
                .lore(Component.text("Duration: " + DURATION_LABELS[selectedDurationIndex], NamedTextColor.GRAY))
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

            PlayerPreference pref = preferenceManager.getCached(player.getUniqueId());
            if (pref != null) {
                pref.lastDurationHours(durationHours);
                pref.lastCategory(category.name());
                pref.lastPrice(price);
                preferenceManager.save(pref);
            }

            auctionService.confirmPendingSale(player, transactionId, durationHours, category).thenAccept(result ->
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

        if (slot >= DURATION_SLOT_START && slot < DURATION_SLOT_START + DURATION_OPTIONS.length) {
            selectedDurationIndex = slot - DURATION_SLOT_START;
            reopen(player);
            return;
        }

        if (slot >= CATEGORY_SLOT_START && slot < CATEGORY_SLOT_START + CATEGORIES.length) {
            selectedCategory = CATEGORIES[slot - CATEGORY_SLOT_START];
            reopen(player);
        }
    }

    /** Re-renders the GUI in place, flagging the close as a navigation so the item is not returned. */
    private void reopen(Player player) {
        guiManager.plugin().schedulerAdapter().runEntity(player, () -> {
            reopening.set(true);
            guiManager.openSellGui(player, this);
        });
    }

    /** True (once) if the pending close is a programmatic re-open rather than a genuine dismissal. */
    public boolean consumeReopening() {
        return reopening.getAndSet(false);
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    /** True once confirm or cancel has claimed the transaction; the close handler must not settle again. */
    public boolean isSettlementStarted() {
        return settlementStarted.get();
    }

    public ItemStack getDisplayItem() {
        return displayItem.clone();
    }
}
