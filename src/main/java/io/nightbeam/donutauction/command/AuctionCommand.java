package io.nightbeam.donutauction.command;

import io.nightbeam.donutauction.AuctionHousePlugin;
import io.nightbeam.donutauction.gui.GuiManager;
import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.HistoryView;
import io.nightbeam.donutauction.model.ListingType;
import io.nightbeam.donutauction.model.MarketStats;
import io.nightbeam.donutauction.model.PlayerPreference;
import io.nightbeam.donutauction.service.AuctionLimitService;
import io.nightbeam.donutauction.service.AuctionService;
import io.nightbeam.donutauction.service.PlayerPreferenceManager;
import io.nightbeam.donutauction.util.ItemNames;
import io.nightbeam.donutauction.util.MessageUtil;
import io.nightbeam.donutauction.util.PriceParser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class AuctionCommand implements CommandExecutor, TabCompleter {

    private static final List<String> PLAYER_COMMANDS = List.of(
            "sell", "auction", "search", "view", "price", "collect", "history", "limit", "fastbuy", "fastsell", "help");
    private static final List<String> ADMIN_COMMANDS = List.of("reload", "open", "admin");

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

    private static boolean admin(CommandSender sender) {
        return sender.hasPermission("donutcore.auction.admin") || sender.hasPermission("donutauction.admin");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        MessageUtil messages = plugin.messages();
        String subCommand = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

        // Console and staff commands first: they do not need a player.
        switch (subCommand) {
            case "reload" -> {
                handleReload(sender);
                return true;
            }
            case "open" -> {
                handleOpen(sender, args);
                return true;
            }
            case "admin" -> {
                handleAdmin(sender, args);
                return true;
            }
            default -> {
            }
        }

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

        switch (subCommand) {
            case "sell" -> handleSell(player, args, ListingType.BIN);
            case "auction", "bid" -> handleSell(player, args, ListingType.BID);
            case "search", "find" -> handleSearch(player, args);
            case "view", "player" -> handleView(player, args.length > 1 ? args[1] : "");
            case "price", "value", "worth" -> handlePrice(player);
            case "cancel", "collect", "claim", "items", "expired", "listings" -> guiManager.openPlayerItems(player);
            case "history", "log" -> guiManager.openHistory(player,
                    args.length > 1 && args[1].equalsIgnoreCase("sold") ? HistoryView.SOLD : HistoryView.BOUGHT, 1);
            case "limit" -> handleLimit(player);
            case "fastbuy" -> handleFastBuyToggle(player);
            case "fastsell" -> handleFastSellToggle(player);
            case "help", "?" -> sendHelp(player);
            default -> handleView(player, args[0]);
        }

        return true;
    }

    private void handleSell(Player player, String[] args, ListingType type) {
        MessageUtil messages = plugin.messages();
        if (!player.hasPermission("donutcore.auction.sell") && !player.hasPermission("donutauction.sell")) {
            messages.sendRaw(player, "command.no-permission-sell", "&cYou do not have permission to sell items.");
            return;
        }
        if (args.length < 2) {
            messages.sendRaw(player, type == ListingType.BID ? "command.usage-auction" : "command.usage-sell",
                    type == ListingType.BID ? "&cUsage: /ah auction <starting bid>" : "&cUsage: /ah sell <price> [auction]");
            return;
        }
        OptionalDouble price = PriceParser.parse(args[1], plugin.getConfig().getBoolean("auction.allow-shorthand-prices", true));
        if (price.isEmpty()) {
            messages.sendRaw(player, "command.invalid-price", "&cInvalid price.");
            return;
        }
        ListingType listingType = type;
        if (args.length > 2 && (args[2].equalsIgnoreCase("auction") || args[2].equalsIgnoreCase("bid"))) {
            listingType = ListingType.BID;
        }
        if (listingType == ListingType.BID && !auctionService.canStartAuctions(player)) {
            messages.sendRaw(player, "command.auctions-disabled", "&cYou cannot start auctions here; use /ah sell <price>.");
            return;
        }
        guiManager.startSellFromHeldItem(player, price.getAsDouble(), listingType);
    }

    private void handleSearch(Player player, String[] args) {
        if (args.length < 2) {
            guiManager.beginSearch(player);
            return;
        }
        String query = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        var session = guiManager.session(player);
        session.request(session.request().withSeller(null, null).withSearch(query));
        guiManager.openAuctionHouse(player, session);
    }

    private void handleView(Player player, String name) {
        if (name == null || name.isBlank()) {
            plugin.messages().sendRaw(player, "command.usage-view", "&cUsage: /ah view <player>");
            return;
        }
        Player online = Bukkit.getPlayerExact(name);
        UUID seller = online != null ? online.getUniqueId() : null;
        String sellerName = online != null ? online.getName() : name;
        if (seller == null) {
            // Offline sellers: match the name stored with their listings, never a remote profile lookup.
            AuctionListing match = guiManager.auctionManager().activeListings(System.currentTimeMillis()).stream()
                    .filter(listing -> name.equalsIgnoreCase(listing.sellerName()))
                    .findFirst().orElse(null);
            if (match != null) {
                seller = match.seller();
                sellerName = match.sellerName();
            }
        }
        if (seller == null) {
            plugin.messages().sendFormatted(player, "command.no-listings-from", "&c%player% has nothing for sale.", "player", name);
            guiManager.openAuctionHouse(player);
            return;
        }
        guiManager.openSellerListings(player, seller, sellerName);
    }

    private void handlePrice(Player player) {
        MessageUtil messages = plugin.messages();
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            messages.sendRaw(player, "service.hold-item", "&cHold the item you want to list.");
            return;
        }
        if (!auctionService.market().enabled()) {
            messages.sendRaw(player, "command.price-disabled", "&cPrice insight is turned off on this server.");
            return;
        }
        String material = held.getType().name();
        ItemStack snapshot = held.clone();
        auctionService.market().stats(material).thenAccept(stats -> plugin.schedulerAdapter().runEntity(player, () -> {
            sendPrice(player, snapshot, stats);
        })).exceptionally(failure -> {
            plugin.schedulerAdapter().runEntity(player, () -> messages.sendRaw(player, "command.price-failed", "&cCould not load prices right now."));
            return null;
        });
    }

    private void sendPrice(Player player, ItemStack item, MarketStats stats) {
        MessageUtil messages = plugin.messages();
        messages.sendFormatted(player, "command.price-header", "&6Market price of %item%&6:", "item", ItemNames.fragment(item));
        long days = Math.max(1, plugin.getConfig().getLong("market.window-days", 7));
        if (stats.hasSales()) {
            player.sendMessage(messages.component("command.price-average", " &7Average: &6%price% &7each &8(%sales% sales in %days% days)",
                    "price", auctionService.formatPrice(stats.averageUnit()), "sales", String.valueOf(stats.sales()), "days", String.valueOf(days)));
            player.sendMessage(messages.component("command.price-range", " &7Range: &f%low% &7- &f%high% &7each",
                    "low", auctionService.formatPrice(stats.lowestUnit()), "high", auctionService.formatPrice(stats.highestUnit())));
            if (item.getAmount() > 1) {
                player.sendMessage(messages.component("command.price-stack", " &7Your stack of %amount%: about &a%price%",
                        "amount", String.valueOf(item.getAmount()), "price", auctionService.formatPrice(stats.averageUnit() * item.getAmount())));
            }
        } else {
            player.sendMessage(messages.component("command.price-none", " &7No sales in the last %days% days.", "days", String.valueOf(days)));
        }
        OptionalDouble lowest = auctionService.market().lowestActiveUnitPrice(item.getType().name());
        lowest.ifPresent(value -> player.sendMessage(messages.component("command.price-lowest", " &7Cheapest on sale now: &a%price% &7each &8(%count% listed)",
                "price", auctionService.formatPrice(value), "count", String.valueOf(auctionService.market().activeCount(item.getType().name())))));
    }

    private void handleReload(CommandSender sender) {
        MessageUtil messages = plugin.messages();
        if (!admin(sender)) {
            messages.sendRaw(sender, "command.no-permission-reload", "&cYou do not have permission to reload this plugin.");
            return;
        }

        plugin.reloadPluginConfig();
        limitService.reload();
        messages.sendRaw(sender, "command.config-reloaded", "&aDonutAuctionHouse configuration reloaded.");
    }

    /** /ah open <player> [browse|sell|items|history|search <query>]: for NPCs, menus and the console. */
    private void handleOpen(CommandSender sender, String[] args) {
        MessageUtil messages = plugin.messages();
        if (!admin(sender) && !sender.hasPermission("donutauction.admin.open")) {
            messages.sendRaw(sender, "command.no-permission", "&cYou do not have permission to do that.");
            return;
        }
        if (args.length < 2) {
            messages.sendRaw(sender, "command.usage-open", "&cUsage: /ah open <player> [browse|items|history|search <text>]");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.sendFormatted(sender, "command.player-offline", "&c%player% is not online.", "player", args[1]);
            return;
        }
        String view = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "browse";
        String query = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : "";
        plugin.schedulerAdapter().runEntity(target, () -> {
            switch (view) {
                case "items", "collect" -> guiManager.openPlayerItems(target);
                case "history" -> guiManager.openHistory(target, HistoryView.BOUGHT, 1);
                case "sell" -> guiManager.startSellFromHeldItem(target);
                case "search" -> {
                    var session = guiManager.session(target);
                    session.request(session.request().withSeller(null, null).withSearch(query));
                    guiManager.openAuctionHouse(target, session);
                }
                default -> guiManager.openAuctionHouse(target);
            }
        });
    }

    private void handleAdmin(CommandSender sender, String[] args) {
        MessageUtil messages = plugin.messages();
        if (!admin(sender)) {
            messages.sendRaw(sender, "command.no-permission", "&cYou do not have permission to do that.");
            return;
        }
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "help";
        switch (action) {
            case "view" -> {
                if (!(sender instanceof Player player) || args.length < 3) {
                    messages.sendRaw(sender, "admin.usage-view", "&cUsage: /ah admin view <player> (in game)");
                    return;
                }
                Player online = Bukkit.getPlayerExact(args[2]);
                UUID target = online != null ? online.getUniqueId() : null;
                if (target == null) {
                    target = guiManager.auctionManager().allActive().stream()
                            .filter(listing -> args[2].equalsIgnoreCase(listing.sellerName()))
                            .map(AuctionListing::seller).findFirst().orElse(null);
                }
                if (target == null) {
                    @SuppressWarnings("deprecation")
                    OfflinePlayer offline = Bukkit.getOfflinePlayer(args[2]);
                    target = offline.getUniqueId();
                }
                guiManager.openAdminListings(player, target, args[2]);
            }
            case "remove" -> {
                if (args.length < 3) {
                    messages.sendRaw(sender, "admin.usage-remove", "&cUsage: /ah admin remove <auction id>");
                    return;
                }
                UUID id;
                try {
                    id = UUID.fromString(args[2]);
                } catch (IllegalArgumentException exception) {
                    messages.sendRaw(sender, "admin.bad-id", "&cThat is not an auction ID.");
                    return;
                }
                auctionService.adminRemove(sender, id).thenAccept(result -> messages.send(sender, result.message()));
            }
            case "info" -> {
                messages.sendFormatted(sender, "admin.info-header", "&6DonutAuctionHouse %version%", "version", plugin.getDescription().getVersion());
                sender.sendMessage(messages.component("admin.info-storage", " &7Storage: &f%storage% &8| &7Cached rows: &f%rows%",
                        "storage", auctionService.databaseType().name(), "rows", String.valueOf(auctionService.cachedListingCount())));
                sender.sendMessage(messages.component("admin.info-active", " &7Active listings: &f%count%",
                        "count", String.valueOf(guiManager.auctionManager().activeListings(System.currentTimeMillis()).size())));
                sender.sendMessage(messages.component("admin.info-unreadable", " &7Unreadable rows: &f%count% &8(items from a newer Minecraft version)",
                        "count", String.valueOf(auctionService.unreadableRows())));
                sender.sendMessage(messages.component("admin.info-server", " &7Server: &f%server%",
                        "server", Bukkit.getName() + " " + Bukkit.getBukkitVersion()));
            }
            case "purge" -> {
                int days;
                try {
                    days = Integer.parseInt(args.length > 2 ? args[2] : "");
                } catch (NumberFormatException exception) {
                    messages.sendRaw(sender, "admin.usage-purge", "&cUsage: /ah admin purge <days>  (deletes settled history older than that)");
                    return;
                }
                auctionService.purgeHistory(days).thenAccept(count -> messages.sendFormatted(sender, "admin.purged",
                        "&aDeleted %count% history row(s) older than %days% days.", "count", String.valueOf(count), "days", String.valueOf(days)));
            }
            default -> {
                for (String line : messages.rawList("admin.help", List.of(
                        "&6Auction staff commands:",
                        " &e/ah admin view <player> &7- see and remove a player's listings",
                        " &e/ah admin remove <id> &7- remove one listing (item goes to the seller's collection)",
                        " &e/ah admin info &7- storage and health",
                        " &e/ah admin purge <days> &7- delete settled history",
                        " &e/ah open <player> [browse|items|history|search <text>] &7- open a menu for a player",
                        " &e/ah reload &7- reload config.yml and messages.yml"))) {
                    sender.sendMessage(messages.component(line));
                }
            }
        }
    }

    private void sendHelp(Player player) {
        MessageUtil messages = plugin.messages();
        for (String line : messages.rawList("command.help", List.of(
                "&6Auction House commands:",
                " &e/ah &7- browse the auction house",
                " &e/ah sell <price> &7- sell the held item (1k, 2.5m and 1b work)",
                " &e/ah auction <starting bid> &7- auction the held item",
                " &e/ah search <text> &7- find items by name, enchantment or seller",
                " &e/ah view <player> &7- see a player's listings",
                " &e/ah price &7- recent prices of the held item",
                " &e/ah collect &7- your listings and items to collect",
                " &e/ah history &7- your purchases and sales"))) {
            player.sendMessage(messages.component(line));
        }
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

        preferenceManager.get(player.getUniqueId()).thenAccept(pref -> {
            pref.fastBuyEnabled(!pref.fastBuyEnabled());
            preferenceManager.save(pref);

            String state = pref.fastBuyEnabled()
                    ? messages.raw("command.state-enabled", "&aenabled")
                    : messages.raw("command.state-disabled", "&cdisabled");
            plugin.schedulerAdapter().runEntity(player, () ->
                    messages.sendFormatted(player, "command.fast-buy-toggle", "&eFast buy %state%&e.", "state", MessageUtil.trusted(state)));
        });
    }

    private void handleFastSellToggle(Player player) {
        MessageUtil messages = plugin.messages();
        if (!player.hasPermission("donutauction.fastsell")) {
            messages.sendRaw(player, "command.no-permission-fastsell", "&cYou do not have permission to use fast sell.");
            return;
        }

        preferenceManager.get(player.getUniqueId()).thenAccept(pref -> {
            pref.fastSellEnabled(!pref.fastSellEnabled());
            preferenceManager.save(pref);

            String state = pref.fastSellEnabled()
                    ? messages.raw("command.state-enabled", "&aenabled")
                    : messages.raw("command.state-disabled", "&cdisabled");
            plugin.schedulerAdapter().runEntity(player, () ->
                    messages.sendFormatted(player, "command.fast-sell-toggle", "&eFast sell %state%&e.", "state", MessageUtil.trusted(state)));
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>(PLAYER_COMMANDS);
            if (admin(sender)) completions.addAll(ADMIN_COMMANDS);
            else if (sender.hasPermission("donutauction.admin.open")) completions.add("open");
            String input = args[0].toLowerCase(Locale.ROOT);
            return completions.stream().filter(value -> value.startsWith(input)).toList();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (sub.equals("sell") || sub.equals("auction"))) {
            return List.of("100", "1k", "10k", "100k", "1m").stream().filter(value -> value.startsWith(last)).toList();
        }
        if (args.length == 3 && sub.equals("sell")) {
            return List.of("auction").stream().filter(value -> value.startsWith(last)).toList();
        }
        if (args.length == 2 && (sub.equals("view") || sub.equals("open"))) {
            return onlineNames(last);
        }
        if (args.length == 2 && sub.equals("history")) {
            return List.of("bought", "sold").stream().filter(value -> value.startsWith(last)).toList();
        }
        if (args.length == 3 && sub.equals("open")) {
            return List.of("browse", "items", "history", "sell", "search").stream().filter(value -> value.startsWith(last)).toList();
        }
        if (sub.equals("admin") && admin(sender)) {
            if (args.length == 2) return List.of("view", "remove", "info", "purge").stream().filter(value -> value.startsWith(last)).toList();
            if (args.length == 3 && args[1].equalsIgnoreCase("view")) return onlineNames(last);
            if (args.length == 3 && args[1].equalsIgnoreCase("purge")) return List.of("30", "90", "365");
        }
        return List.of();
    }

    private static List<String> onlineNames(String prefix) {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).limit(50).collect(Collectors.toList());
    }
}
