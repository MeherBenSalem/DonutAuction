package io.nightbeam.donutauction.command;

import io.nightbeam.donutauction.AuctionHousePlugin;
import io.nightbeam.donutauction.gui.GuiManager;
import io.nightbeam.donutauction.model.PlayerPreference;
import io.nightbeam.donutauction.service.AuctionLimitService;
import io.nightbeam.donutauction.service.AuctionService;
import io.nightbeam.donutauction.service.PlayerPreferenceManager;
import io.nightbeam.donutauction.util.MessageUtil;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

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
        MessageUtil messages = plugin.messages();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.component("player-only", "Only players can use this command."));
            return true;
        }

        if (!player.hasPermission("donutcore.auction.use") && !player.hasPermission("donutauction.use")) {
            messages.sendRaw(player, "command.no-permission-use", "&cYou do not have permission to use the auction house.");
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
        MessageUtil messages = plugin.messages();
        if (!player.hasPermission("donutcore.auction.sell") && !player.hasPermission("donutauction.sell")) {
            messages.sendRaw(player, "command.no-permission-sell", "&cYou do not have permission to sell items.");
            return;
        }
        if (args.length < 2) {
            messages.sendRaw(player, "command.usage-sell", "&cUsage: /ah sell <price>");
            return;
        }
        double price;
        try {
            price = Double.parseDouble(args[1]);
        } catch (NumberFormatException exception) {
            messages.sendRaw(player, "command.invalid-price", "&cInvalid price.");
            return;
        }

        guiManager.startSellFromHeldItem(player, price);
    }

    private void handleReload(Player player) {
        MessageUtil messages = plugin.messages();
        if (!player.hasPermission("donutcore.auction.admin") && !player.hasPermission("donutauction.admin")) {
            messages.sendRaw(player, "command.no-permission-reload", "&cYou do not have permission to reload this plugin.");
            return;
        }

        plugin.reloadPluginConfig();
        limitService.reload();
        messages.sendRaw(player, "command.config-reloaded", "&aDonutAuctionHouse configuration reloaded.");
    }

    private void handleLimit(Player player) {
        MessageUtil messages = plugin.messages();
        if (!limitService.isEnabled()) {
            messages.sendRaw(player, "command.limits-disabled", "&eAuction limits are not enabled.");
            return;
        }

        int limit = limitService.getEffectiveLimit(player);
        int currentActive = auctionService.getPlayerActiveAuctionCount(player.getUniqueId());
        String limitDisplay = limit == -1
                ? messages.raw("command.unlimited", "Unlimited")
                : String.valueOf(limit);

        player.sendMessage(Component.empty());
        player.sendMessage(messages.component(
                "command.limit-current",
                "&6Your current auction limit: %limit%",
                "limit", limitDisplay
        ));
        player.sendMessage(messages.component(
                "command.limit-listings",
                "&7Current listings: %count%",
                "count", String.valueOf(currentActive)
        ));
        if (limit != -1) {
            player.sendMessage(messages.component(
                    "command.limit-remaining",
                    "&7Remaining listings: %remaining%",
                    "remaining", String.valueOf(Math.max(0, limit - currentActive))
            ));
        }
        player.sendMessage(Component.empty());
    }

    private void handleFastBuyToggle(Player player) {
        MessageUtil messages = plugin.messages();
        if (!player.hasPermission("donutauction.fastbuy")) {
            messages.sendRaw(player, "command.no-permission-fastbuy", "&cYou do not have permission to use fast buy.");
            return;
        }

        plugin.schedulerAdapter().runAsync(() -> {
            PlayerPreference pref = preferenceManager.get(player.getUniqueId()).join();
            pref.fastBuyEnabled(!pref.fastBuyEnabled());
            preferenceManager.save(pref);

            String state = pref.fastBuyEnabled()
                    ? messages.raw("command.state-enabled", "&aenabled")
                    : messages.raw("command.state-disabled", "&cdisabled");
            plugin.schedulerAdapter().runEntity(player, () ->
                    messages.sendFormatted(player, "command.fast-buy-toggle", "&eFast buy %state%&e.", "state", state));
        });
    }

    private void handleFastSellToggle(Player player) {
        MessageUtil messages = plugin.messages();
        if (!player.hasPermission("donutauction.fastsell")) {
            messages.sendRaw(player, "command.no-permission-fastsell", "&cYou do not have permission to use fast sell.");
            return;
        }

        plugin.schedulerAdapter().runAsync(() -> {
            PlayerPreference pref = preferenceManager.get(player.getUniqueId()).join();
            pref.fastSellEnabled(!pref.fastSellEnabled());
            preferenceManager.save(pref);

            String state = pref.fastSellEnabled()
                    ? messages.raw("command.state-enabled", "&aenabled")
                    : messages.raw("command.state-disabled", "&cdisabled");
            plugin.schedulerAdapter().runEntity(player, () ->
                    messages.sendFormatted(player, "command.fast-sell-toggle", "&eFast sell %state%&e.", "state", state));
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
