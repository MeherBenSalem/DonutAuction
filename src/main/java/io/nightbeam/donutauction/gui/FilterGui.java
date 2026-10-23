package io.nightbeam.donutauction.gui;

import io.nightbeam.donutauction.model.AuctionFilterCategory;
import io.nightbeam.donutauction.model.ListingFilter;
import io.nightbeam.donutauction.model.PlayerAuctionSession;
import io.nightbeam.donutauction.util.ItemBuilder;
import io.nightbeam.donutauction.util.MessageUtil;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

public final class FilterGui extends BaseGui {

    static final int TYPE_SLOT = 20;
    static final int CLEAR_SLOT = 22;
    static final int BACK_SLOT = 24;

    private static final int[] CATEGORY_SLOTS = {9, 10, 11, 12, 14, 15, 16, 17};

    private final GuiManager guiManager;
    private final PlayerAuctionSession session;
    private final Map<Integer, AuctionFilterCategory> categories = new HashMap<>();

    public FilterGui(GuiManager guiManager, PlayerAuctionSession session) {
        this.guiManager = guiManager;
        this.session = session;
    }

    private MessageUtil messages() {
        return guiManager.plugin().messages();
    }

    @Override
    public Inventory render(Player player) {
        Inventory inventory = attach(Bukkit.createInventory(this, 27,
                messages().component("gui.titles.filter", "&6ᴀᴜᴄᴛɪᴏɴ • ғɪʟᴛᴇʀ")));
        categories.clear();

        AuctionFilterCategory[] values = {
                AuctionFilterCategory.BLOCKS,
                AuctionFilterCategory.TOOLS,
                AuctionFilterCategory.FOOD,
                AuctionFilterCategory.COMBAT,
                AuctionFilterCategory.POTIONS,
                AuctionFilterCategory.BOOKS,
                AuctionFilterCategory.INGREDIENTS,
                AuctionFilterCategory.UTILITIES
        };

        AuctionFilterCategory current = session.request().filterCategory();
        for (int index = 0; index < values.length && index < CATEGORY_SLOTS.length; index++) {
            AuctionFilterCategory category = values[index];
            boolean selected = category == current;
            int slot = CATEGORY_SLOTS[index];
            inventory.setItem(slot, ItemBuilder.of(category.icon())
                    .name(messages().component((selected ? "&a" : "&e") + messages().filterCategory(category)))
                    .lore(messages().component(selected ? "gui.common.selected" : "filter.filter-lore",
                            selected ? "&aSelected" : "Filter auction listings"))
                    .glow(selected)
                    .hideAll()
                    .build());
            categories.put(slot, category);
        }

        ListingFilter type = session.request().listingFilter();
        inventory.setItem(TYPE_SLOT, ItemBuilder.of(type == ListingFilter.AUCTIONS ? Material.BELL
                        : type == ListingFilter.BUY_NOW ? Material.EMERALD : Material.COMPASS)
                .name(messages().component("filter.type-name", "&eListing Type"))
                .lore(
                        messages().component("gui.common.current-value", "Current: %value%", "value", typeLabel(type)),
                        messages().component("filter.type-lore", "&eClick to switch between everything, Buy It Now and auctions"))
                .glow(type != ListingFilter.ALL)
                .build());

        inventory.setItem(CLEAR_SLOT, ItemBuilder.of(Material.BARRIER)
                .name(messages().component("filter.clear-name", "&cClear Filter"))
                .lore(messages().component("filter.clear-lore", "Show all listings"))
                .build());
        inventory.setItem(BACK_SLOT, ItemBuilder.of(Material.ARROW)
                .name(messages().component("gui.common.back-to-auction", "&eBack to Auction"))
                .build());
        fill(inventory);
        return inventory;
    }

    private String typeLabel(ListingFilter filter) {
        return switch (filter) {
            case ALL -> messages().raw("filter.types.all", "Everything");
            case BUY_NOW -> messages().raw("filter.types.buy-now", "Buy It Now");
            case AUCTIONS -> messages().raw("filter.types.auctions", "Auctions");
        };
    }

    @Override
    public void handleClick(Player player, InventoryClickEvent event) {
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        int slot = event.getSlot();
        if (slot == CLEAR_SLOT) {
            guiManager.click(player);
            session.request(session.request().withFilter(AuctionFilterCategory.ALL).withListingFilter(ListingFilter.ALL));
            guiManager.openAuctionHouse(player, session);
            return;
        }
        if (slot == TYPE_SLOT) {
            guiManager.click(player);
            session.request(session.request().withListingFilter(session.request().listingFilter().next()));
            guiManager.openFilterMenu(player);
            return;
        }
        if (slot == BACK_SLOT) {
            guiManager.click(player);
            guiManager.openAuctionHouse(player, session);
            return;
        }

        AuctionFilterCategory category = categories.get(slot);
        if (category != null) {
            guiManager.click(player);
            session.request(session.request().withFilter(category));
            guiManager.openAuctionHouse(player, session);
        }
    }
}
