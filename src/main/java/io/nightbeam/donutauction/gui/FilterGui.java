package io.nightbeam.donutauction.gui;

import io.nightbeam.donutauction.model.AuctionFilterCategory;
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

        for (int index = 0; index < values.length; index++) {
            AuctionFilterCategory category = values[index];
            inventory.setItem(index, ItemBuilder.of(category.icon())
                    .name(messages().component(messages().filterCategory(category)))
                    .lore(messages().component("filter.filter-lore", "Filter auction listings"))
                    .build());
            categories.put(index, category);
        }

        inventory.setItem(22, ItemBuilder.of(Material.BARRIER)
                .name(messages().component("filter.clear-name", "Clear Filter"))
                .lore(messages().component("filter.clear-lore", "Show all listings"))
                .build());
        return inventory;
    }

    @Override
    public void handleClick(Player player, InventoryClickEvent event) {
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        int slot = event.getSlot();
        if (slot == 22) {
            session.request(session.request().withFilter(AuctionFilterCategory.ALL));
            guiManager.openAuctionHouse(player, session);
            return;
        }

        AuctionFilterCategory category = categories.get(slot);
        if (category != null) {
            session.request(session.request().withFilter(category));
            guiManager.openAuctionHouse(player, session);
        }
    }
}
