package io.nightbeam.donutauction.command;

import io.nightbeam.donutauction.AuctionHousePlugin;
import io.nightbeam.donutauction.gui.GuiManager;
import io.nightbeam.donutauction.gui.SellGui;
import io.nightbeam.donutauction.model.AuctionFilterCategory;
import io.nightbeam.donutauction.model.PendingSaleTransaction;
import io.nightbeam.donutauction.model.PlayerPreference;
import io.nightbeam.donutauction.service.AuctionLimitService;
import io.nightbeam.donutauction.service.AuctionService;
import io.nightbeam.donutauction.service.PlayerPreferenceManager;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class AuctionCommand implements CommandExecutor, TabCompleter {

    private final AuctionHousePlugin plugin;
    private final AuctionService auctionService;
    private final GuiManager guiManager;
    private final AuctionLimitService limitService;
    private final PlayerPreferenceManager preferenceManager;

    public AuctionCommand(AuctionHousePlugin plugin, AuctionService auctionService, GuiManager guiManager,
                          AuctionLimitService limitService, PlayerPreferenceManager preferenceManager) {
        this.plugin = plugin;
        this.auctionService = auctionService;
        this.guiManager = guiManager;
        this.limitService = limitService;
        this.preferenceManager = preferenceManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (!player.hasPermission("donutcore.auction.use") && !player.hasPermission("donutauction.use")) {
            plugin.messages().send(player, "&cYou do not have permission to use the auction house.");
            return true;
        }

        if (args.length == 0) {
            guiManager.openAuctionHouse(player);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "sell" -> handleSell(player, args);
            case "reload" -> handleReload(player);
            case "cancel" -> guiManager.openPlayerItems(player);
            case "limit" -> handleLimit(player);
            case "fastbuy" -> handleFastBuyToggle(player);
            case "fastsell" -> handleFastSellToggle(player);
            default -> guiManager.openAuctionHouse(player);
        }

        return true;
    }

    private void handleSell(Player player, String[] args) {
        if (!player.hasPermission("donutcore.auction.sell") && !player.hasPermission("donutauction.sell")) {
            plugin.messages().send(player, "&cYou do not have permission to sell items.");
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(player, "&cUsage: /ah sell <price>");
            return;
        }
        double price;
        try {
            price = Double.parseDouble(args[1]);
        } catch (NumberFormatException exception) {
            plugin.messages().send(player, "&cInvalid price.");
            return;
        }

        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        if (itemInHand == null || itemInHand.getType() == Material.AIR) {
            plugin.messages().send(player, "&cHold the item you want to list.");
            return;
        }

        int activeCount = auctionService.getPlayerActiveAuctionCount(player.getUniqueId());
        if (!limitService.canCreateListing(player, activeCount)) {
            int limit = limitService.getEffectiveLimit(player);
            plugin.messages().send(player, "&cYou have reached your auction limit of " + limit + " listings.");
            return;
        }

        // Snapshot ownership immediately: clone then clear hand so the stack cannot remain in inventory.
        ItemStack ownedItem = itemInHand.clone();
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));

        PlayerPreference pref = preferenceManager.getCached(player.getUniqueId());
        boolean fastSell = pref != null && pref.fastSellEnabled() && player.hasPermission("donutauction.fastsell");

        if (fastSell) {
            int duration = pref != null ? pref.lastDurationHours() : plugin.getConfig().getInt("auction.listing-duration-hours", 48);
            AuctionFilterCategory category = AuctionFilterCategory.ALL;
            if (pref != null) {
                try {
                    category = AuctionFilterCategory.valueOf(pref.lastCategory());
                } catch (IllegalArgumentException ignored) {
                }
            }
            final int finalDuration = duration;
            final AuctionFilterCategory finalCategory = category;

            // createAuctionFromItem restores the item on any failure (including full inventory via drop).
            auctionService.createAuctionFromItem(player, ownedItem, price, finalDuration, finalCategory)
                    .thenAccept(result -> plugin.schedulerAdapter().runEntity(player, () -> {
                        plugin.messages().send(player, result.message());
                        if (result.success()) {
                            guiManager.openPlayerItems(player);
                        }
                    }));
        } else {
            PendingSaleTransaction transaction = auctionService.beginPendingSale(player, ownedItem, price);
            SellGui sellGui = new SellGui(guiManager, auctionService, preferenceManager, transaction, pref);
            guiManager.openSellGui(player, sellGui);
        }
    }

    private void handleReload(Player player) {
        if (!player.hasPermission("donutcore.auction.admin") && !player.hasPermission("donutauction.admin")) {
            plugin.messages().send(player, "&cYou do not have permission to reload this plugin.");
            return;
        }

        plugin.reloadConfig();
        plugin.applyConfigDefaults();
        limitService.reload();
        plugin.messages().send(player, "&aDonutAuctionHouse configuration reloaded.");
    }

    private void handleLimit(Player player) {
        if (!limitService.isEnabled()) {
            plugin.messages().send(player, "&eAuction limits are not enabled.");
            return;
        }

        int limit = limitService.getEffectiveLimit(player);
        int currentActive = auctionService.getPlayerActiveAuctionCount(player.getUniqueId());

        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("Your current auction limit: " + (limit == -1 ? "Unlimited" : limit), NamedTextColor.GOLD));
        player.sendMessage(Component.text("Current listings: " + currentActive, NamedTextColor.GRAY));
        if (limit != -1) {
            player.sendMessage(Component.text("Remaining listings: " + Math.max(0, limit - currentActive), NamedTextColor.GRAY));
        }
        player.sendMessage(Component.empty());
    }

    private void handleFastBuyToggle(Player player) {
        if (!player.hasPermission("donutauction.fastbuy")) {
            plugin.messages().send(player, "&cYou do not have permission to use fast buy.");
            return;
        }

        plugin.schedulerAdapter().runAsync(() -> {
            PlayerPreference pref = preferenceManager.get(player.getUniqueId()).join();
            pref.fastBuyEnabled(!pref.fastBuyEnabled());
            preferenceManager.save(pref);

            String state = pref.fastBuyEnabled() ? "&aenabled" : "&cdisabled";
            plugin.schedulerAdapter().runEntity(player, () ->
                    plugin.messages().send(player, "&eFast buy " + state + "&e."));
        });
    }

    private void handleFastSellToggle(Player player) {
        if (!player.hasPermission("donutauction.fastsell")) {
            plugin.messages().send(player, "&cYou do not have permission to use fast sell.");
            return;
        }

        plugin.schedulerAdapter().runAsync(() -> {
            PlayerPreference pref = preferenceManager.get(player.getUniqueId()).join();
            pref.fastSellEnabled(!pref.fastSellEnabled());
            preferenceManager.save(pref);

            String state = pref.fastSellEnabled() ? "&aenabled" : "&cdisabled";
            plugin.schedulerAdapter().runEntity(player, () ->
                    plugin.messages().send(player, "&eFast sell " + state + "&e."));
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>(List.of("sell", "cancel", "reload", "limit", "fastbuy", "fastsell"));
            String input = args[0].toLowerCase();
            return completions.stream().filter(value -> value.startsWith(input)).toList();
        }
        return List.of();
    }
}
