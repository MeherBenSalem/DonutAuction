package io.nightbeam.donutauction.service;

import io.nightbeam.donutauction.AuctionHousePlugin;
import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.TransactionRecord;
import io.nightbeam.donutauction.storage.AuctionRepository;
import io.nightbeam.donutauction.storage.ItemStackSerializer;
import io.nightbeam.donutauction.util.ItemNames;
import io.nightbeam.donutauction.util.MessageUtil;
import io.nightbeam.donutauction.util.Sounds;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.DoubleFunction;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Tells players what happened to their trades: right away when they are online on this server, and as
 * a "while you were away" summary when they join. Sales are only reported once the database says they
 * completed, and each sale is marked as reported so it is never announced twice (or on two servers).
 */
public final class Notifier {

    private static final int SUMMARY_LINES = 5;

    private final AuctionHousePlugin plugin;
    private final AuctionRepository repository;
    private final AuctionManager auctionManager;
    private final DoubleFunction<String> format;
    private volatile Consumer<Player> collector = player -> { };

    public Notifier(AuctionHousePlugin plugin, AuctionRepository repository, AuctionManager auctionManager, DoubleFunction<String> format) {
        this.plugin = plugin;
        this.repository = repository;
        this.auctionManager = auctionManager;
        this.format = format;
    }

    /** Called to hand waiting items to a player who just won or joined (whole stacks that fit). */
    public void setCollector(Consumer<Player> collector) {
        this.collector = collector;
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }

    private MessageUtil messages() {
        return plugin.messages();
    }

    private boolean on(String key) {
        return config().getBoolean("notifications." + key, true);
    }

    private Player online(UUID id) {
        if (id == null) return null;
        Player player = Bukkit.getPlayer(id);
        return player != null && player.isOnline() ? player : null;
    }

    private void onPlayer(Player player, Runnable task) {
        plugin.schedulerAdapter().runEntity(player, task);
    }

    /** A sale completed on this server. */
    public void sold(AuctionListing sold, String buyerName, double net) {
        Player seller = online(sold.seller());
        if (seller == null || !on("sold")) return;
        String tax = sold.tax() > 0.0D
                ? messages().format("notify.tax-note", " &7(tax %tax%)", "tax", format.apply(sold.tax())) : "";
        String text = messages().format("notify.sold", "&aYour %item% &7x%amount% &asold to &e%buyer% &afor &6%price%&a.%tax_note%",
                "item", ItemNames.fragment(sold.item()), "amount", String.valueOf(sold.item().getAmount()),
                "buyer", buyerName == null ? messages().unknownSeller() : buyerName, "price", format.apply(net),
                "tax_note", MessageUtil.trusted(tax));
        onPlayer(seller, () -> {
            messages().send(seller, text);
            Sounds.play(seller, config(), "sold");
            if (on("titles")) {
                try {
                    seller.showTitle(Title.title(messages().component("notify.sold-title", "&6Item sold!"),
                            messages().component("notify.sold-subtitle", "%item% &7for &6%price%",
                                    "item", ItemNames.fragment(sold.item()), "price", format.apply(net)),
                            Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(1800), Duration.ofMillis(500))));
                } catch (RuntimeException unsupported) {
                    // Titles are decoration; the chat message already went out.
                }
            }
        });
        markReported(sold.seller(), List.of(sold.auctionId()));
    }

    public void outbid(UUID previousBidder, AuctionListing listing, double newBid, double refunded) {
        Player player = online(previousBidder);
        if (player == null || !on("outbid")) return;
        String text = messages().format("notify.outbid",
                "&eYou were outbid on %item%&e: the bid is now &6%bid%&e. Your &6%refund% &ewas refunded.",
                "item", ItemNames.fragment(listing.item()), "bid", format.apply(newBid), "refund", format.apply(refunded));
        onPlayer(player, () -> {
            messages().send(player, text);
            Sounds.play(player, config(), "outbid");
        });
    }

    /** Staff removed an auction this player was leading; their held bid went back. */
    public void bidReturned(UUID bidder, AuctionListing listing, double amount) {
        Player player = online(bidder);
        if (player == null) return;
        String text = messages().format("notify.bid-returned", "&eThe auction for %item% &ewas removed by staff. Your bid of &6%refund% &ewas returned.",
                "item", ItemNames.fragment(listing.item()), "refund", format.apply(amount));
        onPlayer(player, () -> messages().send(player, text));
    }

    public void newBid(AuctionListing listing, String bidderName, double amount) {
        Player seller = online(listing.seller());
        if (seller == null || !on("bids-on-my-items")) return;
        String text = messages().format("notify.new-bid", "&a%bidder% &7bid &6%bid% &7on your %item%&7.",
                "bidder", bidderName, "bid", format.apply(amount), "item", ItemNames.fragment(listing.item()));
        onPlayer(seller, () -> messages().send(seller, text));
    }

    public void won(AuctionListing won) {
        Player winner = online(won.buyer());
        if (winner == null || !on("won")) return;
        String text = messages().format("notify.won", "&aYou won %item% &afor &6%price%&a! It waits in &e/ah collect&a.",
                "item", ItemNames.fragment(won.item()), "price", format.apply(won.price()));
        onPlayer(winner, () -> {
            messages().send(winner, text);
            Sounds.play(winner, config(), "won");
            collector.accept(winner);
        });
    }

    public void expired(AuctionListing listing) {
        Player seller = online(listing.seller());
        if (seller == null || !on("expired")) return;
        String text = messages().format("notify.expired", "&7Your %item% &7did not sell. Reclaim it with &e/ah collect&7.",
                "item", ItemNames.fragment(listing.item()));
        onPlayer(seller, () -> messages().send(seller, text));
    }

    public void removedByAdmin(AuctionListing listing) {
        Player seller = online(listing.seller());
        if (seller == null) return;
        String text = messages().format("notify.removed", "&cA staff member removed your %item%&c. Reclaim it with &e/ah collect&c.",
                "item", ItemNames.fragment(listing.item()));
        onPlayer(seller, () -> messages().send(seller, text));
    }

    /** Another server sold something of a player online here: report it once the sale is complete. */
    public void remoteSale(UUID sellerId) {
        Player seller = online(sellerId);
        if (seller == null || !on("sold")) return;
        // The selling server finishes settlement right after the claim; give it a moment.
        plugin.schedulerAdapter().runEntityLater(seller, () -> reportUnnotified(seller, false), 40L);
    }

    /** On join: sales while away, items waiting to be collected, and delivery of won items. */
    public void join(Player player) {
        plugin.schedulerAdapter().runEntityLater(player, () -> {
            if (!player.isOnline()) return;
            if (on("join-summary")) reportUnnotified(player, true);
            int waiting = auctionManager.sellerListings(player.getUniqueId()).stream().filter(AuctionListing::isReclaimable).toList().size()
                    + auctionManager.buyerCollection(player.getUniqueId()).size();
            if (config().getBoolean("collection.deliver-on-join", true) && !auctionManager.buyerCollection(player.getUniqueId()).isEmpty()) {
                collector.accept(player);
            } else if (waiting > 0 && on("join-summary")) {
                messages().sendFormatted(player, "notify.waiting", "&eYou have &6%count% &eitem(s) waiting in &6/ah collect&e.",
                        "count", String.valueOf(waiting));
            }
        }, Math.max(1L, config().getLong("notifications.join-delay-seconds", 3L) * 20L));
    }

    private void reportUnnotified(Player player, boolean summary) {
        UUID id = player.getUniqueId();
        repository.findUnnotifiedSales(id, 200).thenAccept(records -> {
            if (records.isEmpty()) return;
            onPlayer(player, () -> {
                if (!player.isOnline()) return;
                if (!summary && records.size() == 1) {
                    TransactionRecord record = records.get(0);
                    ItemStack item = decode(record.itemData());
                    messages().sendFormatted(player, "notify.sold", "&aYour %item% &7x%amount% &asold to &e%buyer% &afor &6%price%&a.%tax_note%",
                            "item", item == null ? "?" : ItemNames.fragment(item), "amount", item == null ? "1" : String.valueOf(item.getAmount()),
                            "buyer", name(record), "price", format.apply(record.price() - record.tax()), "tax_note", "");
                } else {
                    double total = records.stream().mapToDouble(record -> record.price() - record.tax()).sum();
                    messages().sendFormatted(player, "notify.summary-header", "&6While you were away, &e%count% &6of your items sold for &a%total%&6:",
                            "count", String.valueOf(records.size()), "total", format.apply(total));
                    for (TransactionRecord record : records.subList(0, Math.min(SUMMARY_LINES, records.size()))) {
                        ItemStack item = decode(record.itemData());
                        player.sendMessage(messages().component(messages().format("notify.summary-line", " &8- &f%item% &7x%amount% &8> &e%buyer% &7for &6%price%",
                                "item", item == null ? "?" : ItemNames.fragment(item), "amount", item == null ? "1" : String.valueOf(item.getAmount()),
                                "buyer", name(record), "price", format.apply(record.price() - record.tax()))));
                    }
                    if (records.size() > SUMMARY_LINES) {
                        player.sendMessage(messages().component("notify.summary-more", " &8- &7and %count% more in &e/ah history",
                                "count", String.valueOf(records.size() - SUMMARY_LINES)));
                    }
                }
                Sounds.play(player, config(), "sold");
            });
            List<UUID> ids = new ArrayList<>();
            records.forEach(record -> ids.add(record.auctionId()));
            markReported(id, ids);
        }).exceptionally(failure -> {
            plugin.getLogger().warning("Could not load sales to report to " + player.getName() + ": " + failure.getMessage());
            return null;
        });
    }

    private String name(TransactionRecord record) {
        if (record.counterpartyName() != null && !record.counterpartyName().isBlank()) return record.counterpartyName();
        if (record.counterparty() == null) return messages().unknownSeller();
        String name = Bukkit.getOfflinePlayer(record.counterparty()).getName();
        return name == null ? messages().unknownSeller() : name;
    }

    private static ItemStack decode(String data) {
        try {
            return ItemStackSerializer.deserialize(data);
        } catch (RuntimeException | LinkageError unreadable) {
            return null;
        }
    }

    private void markReported(UUID seller, List<UUID> ids) {
        repository.markSellerNotified(seller, ids).exceptionally(failure -> {
            plugin.getLogger().fine("Could not mark sales as reported: " + failure.getMessage());
            return 0;
        });
    }
}
