package io.nightbeam.donutauction.gui;

import io.nightbeam.donutauction.model.HistoryView;
import io.nightbeam.donutauction.model.TransactionPage;
import io.nightbeam.donutauction.model.TransactionRecord;
import io.nightbeam.donutauction.service.AuctionService;
import io.nightbeam.donutauction.storage.ItemStackSerializer;
import io.nightbeam.donutauction.util.ItemBuilder;
import io.nightbeam.donutauction.util.MessageUtil;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Read-only viewer. Each navigation creates a new holder, invalidating older async responses. */
public final class HistoryGui extends BaseGui {
    private final GuiManager manager;
    private final AuctionService service;
    private final UUID viewer;
    private final HistoryView view;
    private final int requestedPage;
    private TransactionPage page;
    private boolean failed;

    public HistoryGui(GuiManager manager, AuctionService service, UUID viewer, HistoryView view, int page) {
        this.manager = manager;
        this.service = service;
        this.viewer = viewer;
        this.view = view;
        this.requestedPage = page;
    }

    void load(Player player) {
        service.history(player, view, requestedPage).whenComplete((result, failure) ->
                manager.plugin().schedulerAdapter().runEntity(player, () -> {
                    if (!viewer.equals(player.getUniqueId()) || !player.isOnline()
                            || player.getOpenInventory().getTopInventory().getHolder(false) != this) return;
                    if (!player.hasPermission("donutauction.use") && !player.hasPermission("donutcore.auction.use")) {
                        player.closeInventory();
                        return;
                    }
                    failed = failure != null || result == null || !viewer.equals(result.viewer()) || result.view() != view;
                    if (!failed) page = result;
                    manager.refreshHistory(player, this);
                }));
    }

    private MessageUtil messages() { return manager.plugin().messages(); }

    @Override
    public Inventory render(Player player) {
        if (!viewer.equals(player.getUniqueId())) throw new SecurityException("History owner mismatch");
        Inventory inventory = attach(Bukkit.createInventory(this, 54,
                messages().component("gui.history.title", "&6Transaction History")));
        if (page == null || page.records().isEmpty()) {
            String key = failed ? "error" : page == null ? "loading" : "empty";
            String fallback = failed ? "Unable to load history. Click Refresh to retry." : page == null ? "Loading history..." : "No completed transactions";
            inventory.setItem(22, ItemBuilder.of(Material.PAPER).name(messages().component("gui.history." + key, fallback)).build());
        } else {
            for (int i = 0; i < page.records().size(); i++) inventory.setItem(i, recordItem(page.records().get(i)));
        }
        Component indicator = messages().component("gui.common.page-indicator", "Page: %page%/%pages%",
                "page", String.valueOf(page == null ? 1 : page.currentPage()), "pages", String.valueOf(page == null ? 1 : page.totalPages()));
        inventory.setItem(45, ItemBuilder.of(Material.ARROW).name(messages().component("gui.common.previous-page", "Previous Page")).lore(indicator).build());
        inventory.setItem(46, tab(HistoryView.BOUGHT));
        inventory.setItem(47, tab(HistoryView.SOLD));
        inventory.setItem(49, ItemBuilder.of(Material.CLOCK).name(messages().component("gui.history.refresh", "Refresh History"))
                .lore(messages().component("gui.history.count", "%count% completed transactions", "count", String.valueOf(page == null ? 0 : page.totalResults()))).build());
        inventory.setItem(51, ItemBuilder.of(Material.BARRIER).name(messages().component("gui.common.back-to-auction", "Back to Auction")).build());
        inventory.setItem(53, ItemBuilder.of(Material.ARROW).name(messages().component("gui.common.next-page", "Next Page")).lore(indicator).build());
        return inventory;
    }

    private ItemStack tab(HistoryView tab) {
        return ItemBuilder.of(view == tab ? Material.ENCHANTED_BOOK : Material.BOOK)
                .name(messages().component("gui.history." + tab.name().toLowerCase(java.util.Locale.ROOT), tab == HistoryView.BOUGHT ? "Bought" : "Sold"))
                .lore(messages().component(view == tab ? "gui.common.selected" : "gui.common.click-to-select", view == tab ? "Selected" : "Click to select")).build();
    }

    private ItemStack recordItem(TransactionRecord record) {
        ItemStack item;
        try {
            item = ItemStackSerializer.deserialize(record.itemData()).clone();
            if (item.getType().isAir()) throw new IllegalArgumentException("Unavailable item");
            // Trigger metadata decoding here too; old/new server metadata can be incompatible.
            item.getItemMeta();
        } catch (RuntimeException | LinkageError exception) {
            item = ItemBuilder.of(Material.PAPER).name(messages().component("gui.history.unavailable-item", "Item details unavailable")).build();
        }
        String other = record.counterparty() == null ? messages().unknownSeller() : record.counterparty().toString();
        if (record.counterparty() != null) {
            String name = Bukkit.getOfflinePlayer(record.counterparty()).getName();
            if (name != null && !name.isBlank()) other = name;
        }
        List<Component> lore = new ArrayList<>();
        String otherLabel = view == HistoryView.BOUGHT ? "Seller" : "Buyer";
        lore.add(messages().component("gui.history.price", "&7Price: %price%", "price", service.formatPrecisePrice(record.price())));
        lore.add(messages().component("gui.history.date", "&7Date: %date%", "date", date(record.soldTime())));
        lore.add(messages().component("gui.history." + otherLabel.toLowerCase(java.util.Locale.ROOT), "&7" + otherLabel + ": %name%", "name", other));
        lore.add(messages().component("gui.history.id", "&8Transaction: %id%", "id", record.auctionId().toString()));
        lore.add(messages().component("gui.history.read-only", "&8Completed transaction - read only"));
        ItemStack display = item;
        display.editMeta(meta -> {
            List<Component> all = new ArrayList<>();
            if (meta.hasLore() && meta.lore() != null) { all.addAll(meta.lore()); all.add(Component.empty()); }
            all.addAll(lore);
            meta.lore(all);
        });
        return display;
    }

    private String date(long timestamp) {
        try {
            return DateTimeFormatter.ofPattern(manager.plugin().getConfig().getString("history.date-format", "yyyy-MM-dd HH:mm z"))
                    .withZone(ZoneId.of(manager.plugin().getConfig().getString("history.timezone", "UTC"))).format(Instant.ofEpochMilli(timestamp));
        } catch (IllegalArgumentException | java.time.DateTimeException exception) {
            return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(timestamp));
        }
    }

    @Override
    public void handleClick(Player player, InventoryClickEvent event) {
        if (!viewer.equals(player.getUniqueId()) || event.getClickedInventory() != getInventory()) return;
        int slot = event.getSlot();
        if (slot == 51) { manager.refreshAuctionHouse(player); return; }
        if (slot == 46 || slot == 47) { manager.openHistory(player, slot == 46 ? HistoryView.BOUGHT : HistoryView.SOLD, 1); return; }
        if (slot == 49) { manager.openHistory(player, view, page == null ? requestedPage : page.currentPage()); return; }
        if (page == null) return;
        if (slot == 45 && page.currentPage() > 1) manager.openHistory(player, view, page.currentPage() - 1);
        if (slot == 53 && page.currentPage() < page.totalPages()) manager.openHistory(player, view, page.currentPage() + 1);
    }
}
