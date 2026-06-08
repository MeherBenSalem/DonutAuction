package io.nightbeam.donutauction.gui;

import io.nightbeam.donutauction.model.AuctionFilterCategory;
import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.PlayerPreference;
import io.nightbeam.donutauction.service.ActionResult;
import io.nightbeam.donutauction.service.AuctionService;
import io.nightbeam.donutauction.service.PlayerPreferenceManager;
import io.nightbeam.donutauction.util.ItemBuilder;
import java.util.List;
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

    private final GuiManager guiManager;
    private final AuctionService auctionService;
    private final PlayerPreferenceManager preferenceManager;
    private final ItemStack itemToSell;
    private final double price;
    private int selectedDurationIndex = 3;
    private AuctionFilterCategory selectedCategory = AuctionFilterCategory.ALL;
    private volatile boolean confirmed = false;

    public SellGui(GuiManager guiManager, AuctionService auctionService, PlayerPreferenceManager preferenceManager,
                   ItemStack itemToSell, double price, PlayerPreference preference) {
        this.guiManager = guiManager;
        this.auctionService = auctionService;
        this.preferenceManager = preferenceManager;
        this.itemToSell = itemToSell.clone();
        this.price = price;

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
        Inventory inventory = attach(Bukkit.createInventory(this, 36, TITLE));

        inventory.setItem(4, itemToSell.clone());

        for (int i = 0; i < DURATION_OPTIONS.length; i++) {
            boolean selected = i == selectedDurationIndex;
            Material material = selected ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;
            inventory.setItem(18 + i, ItemBuilder.of(material)
                    .name(Component.text(DURATION_LABELS[i], selected ? NamedTextColor.GREEN : NamedTextColor.WHITE))
                    .lore(selected ? Component.text("Selected", NamedTextColor.GREEN) : Component.text("Click to select", NamedTextColor.GRAY))
                    .build());
        }

        AuctionFilterCategory[] categories = {
                AuctionFilterCategory.ALL, AuctionFilterCategory.BLOCKS, AuctionFilterCategory.TOOLS,
                AuctionFilterCategory.FOOD, AuctionFilterCategory.COMBAT, AuctionFilterCategory.POTIONS,
                AuctionFilterCategory.BOOKS, AuctionFilterCategory.INGREDIENTS, AuctionFilterCategory.UTILITIES
        };

        for (int i = 0; i < categories.length; i++) {
            AuctionFilterCategory cat = categories[i];
            boolean selected = cat == selectedCategory;
            inventory.setItem(27 + i, ItemBuilder.of(selected ? Material.LIME_STAINED_GLASS_PANE : cat.icon())
                    .name(Component.text(cat.displayName(), selected ? NamedTextColor.GREEN : NamedTextColor.WHITE))
                    .lore(selected ? Component.text("Selected", NamedTextColor.GREEN) : Component.text("Click to select", NamedTextColor.GRAY))
                    .build());
        }

        inventory.setItem(13, ItemBuilder.of(Material.GOLD_INGOT)
                .name(Component.text("Price: " + auctionService.formatPrice(price), NamedTextColor.GOLD))
                .lore(Component.text("Duration: " + DURATION_LABELS[selectedDurationIndex], NamedTextColor.GRAY))
                .build());

        inventory.setItem(31, ItemBuilder.of(Material.LIME_STAINED_GLASS_PANE)
                .name(Component.text("Confirm Listing", NamedTextColor.GREEN))
                .lore(
                        Component.text("List this item for " + auctionService.formatPrice(price), NamedTextColor.GRAY),
                        Component.text("Duration: " + DURATION_LABELS[selectedDurationIndex], NamedTextColor.GRAY)
                )
                .build());

        inventory.setItem(35, ItemBuilder.of(Material.RED_STAINED_GLASS_PANE)
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

        int slot = event.getSlot();

        if (slot >= 18 && slot < 18 + DURATION_OPTIONS.length) {
            selectedDurationIndex = slot - 18;
            guiManager.plugin().schedulerAdapter().runEntity(player, () -> guiManager.openSellGui(player, this));
            return;
        }

        AuctionFilterCategory[] categories = {
                AuctionFilterCategory.ALL, AuctionFilterCategory.BLOCKS, AuctionFilterCategory.TOOLS,
                AuctionFilterCategory.FOOD, AuctionFilterCategory.COMBAT, AuctionFilterCategory.POTIONS,
                AuctionFilterCategory.BOOKS, AuctionFilterCategory.INGREDIENTS, AuctionFilterCategory.UTILITIES
        };

        if (slot >= 27 && slot < 27 + categories.length) {
            selectedCategory = categories[slot - 27];
            guiManager.plugin().schedulerAdapter().runEntity(player, () -> guiManager.openSellGui(player, this));
            return;
        }

        if (slot == 31) {
            confirmed = true;
            int durationHours = DURATION_OPTIONS[selectedDurationIndex];
            AuctionFilterCategory category = selectedCategory;

            PlayerPreference pref = preferenceManager.getCached(player.getUniqueId());
            if (pref != null) {
                pref.lastDurationHours(durationHours);
                pref.lastCategory(category.name());
                pref.lastPrice(price);
                preferenceManager.save(pref);
            }

            auctionService.createAuctionFromItem(player, itemToSell, price, durationHours, category).thenAccept(result ->
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

        if (slot == 35) {
            confirmed = true;
            guiManager.plugin().schedulerAdapter().runEntity(player, () -> {
                ItemStack returnItem = itemToSell.clone();
                player.getInventory().addItem(returnItem);
                guiManager.openAuctionHouse(player);
            });
        }
    }

    public int getSelectedDurationHours() {
        return DURATION_OPTIONS[selectedDurationIndex];
    }

    public AuctionFilterCategory getSelectedCategory() {
        return selectedCategory;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public ItemStack getItemToSell() {
        return itemToSell.clone();
    }
}
