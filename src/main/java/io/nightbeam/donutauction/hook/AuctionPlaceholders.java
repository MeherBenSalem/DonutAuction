package io.nightbeam.donutauction.hook;

import io.nightbeam.donutauction.AuctionHousePlugin;
import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.MarketStats;
import io.nightbeam.donutauction.model.PlayerStats;
import io.nightbeam.donutauction.service.AuctionLimitService;
import io.nightbeam.donutauction.service.AuctionService;
import java.util.Locale;
import java.util.OptionalDouble;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * PlaceholderAPI expansion "donutauction". Everything is answered from memory or a background-refreshed
 * cache, because PlaceholderAPI asks on the main thread. Prices ending in {@code _raw} are plain numbers.
 *
 * <pre>
 * %donutauction_active%            your active listings        %donutauction_limit%        your listing limit
 * %donutauction_slots_left%        listings you can still add  %donutauction_collect%      items waiting in /ah collect
 * %donutauction_sold%              items you sold              %donutauction_earned%       money earned (after tax)
 * %donutauction_bought%            items you bought            %donutauction_spent%        money spent
 * %donutauction_total_listings%    everything for sale         %donutauction_total_auctions% auctions running
 * %donutauction_average_<MATERIAL>% recent average price each  %donutauction_sales_<MATERIAL>% recent sales count
 * %donutauction_lowest_<MATERIAL>%  cheapest Buy It Now each   %donutauction_listed_<MATERIAL>% how many are listed
 * </pre>
 */
public final class AuctionPlaceholders extends PlaceholderExpansion {

    private final AuctionHousePlugin plugin;
    private final AuctionService service;
    private final AuctionLimitService limits;

    public AuctionPlaceholders(AuctionHousePlugin plugin, AuctionService service, AuctionLimitService limits) {
        this.plugin = plugin;
        this.service = service;
        this.limits = limits;
    }

    @Override
    public String getIdentifier() {
        return "donutauction";
    }

    @Override
    public String getAuthor() {
        return "NightBeam Studio";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String key = params.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        switch (key) {
            case "total_listings":
                return String.valueOf(service.browse(new io.nightbeam.donutauction.model.AuctionBrowseRequest(1, null, null, "")).totalResults());
            case "total_auctions":
                return String.valueOf(plugin.auctionManager().activeListings(now).stream().filter(AuctionListing::isBid).count());
            default:
                break;
        }
        if (key.startsWith("average_") || key.startsWith("sales_") || key.startsWith("lowest_") || key.startsWith("listed_")) {
            return market(key);
        }
        if (player == null) return "";
        switch (key) {
            case "active":
                return String.valueOf(service.getPlayerActiveAuctionCount(player.getUniqueId()));
            case "limit": {
                Player online = player.getPlayer();
                if (online == null) return "";
                int limit = limits.getEffectiveLimit(online);
                return limit < 0 ? plugin.messages().raw("command.unlimited", "Unlimited") : String.valueOf(limit);
            }
            case "slots_left": {
                Player online = player.getPlayer();
                if (online == null) return "";
                int left = limits.getRemainingSlots(online, service.getPlayerActiveAuctionCount(player.getUniqueId()));
                return left < 0 ? plugin.messages().raw("command.unlimited", "Unlimited") : String.valueOf(left);
            }
            case "collect":
                return String.valueOf(service.getCollection(player.getUniqueId()).size()
                        + service.getPlayerAuctions(player.getUniqueId()).stream().filter(AuctionListing::isReclaimable).count());
            default:
                break;
        }
        PlayerStats stats = service.market().cachedPlayerStats(player.getUniqueId());
        return switch (key) {
            case "sold" -> String.valueOf(stats.sold());
            case "earned" -> service.formatPrice(stats.earned());
            case "earned_raw" -> raw(stats.earned());
            case "bought" -> String.valueOf(stats.bought());
            case "spent" -> service.formatPrice(stats.spent());
            case "spent_raw" -> raw(stats.spent());
            default -> null;
        };
    }

    private String market(String key) {
        int split = key.indexOf('_');
        boolean rawValue = key.endsWith("_raw");
        String material = key.substring(split + 1, rawValue ? key.length() - 4 : key.length()).toUpperCase(Locale.ROOT);
        String kind = key.substring(0, split);
        if (kind.equals("lowest")) {
            OptionalDouble lowest = service.market().lowestActiveUnitPrice(material);
            if (lowest.isEmpty()) return rawValue ? "0" : "-";
            return rawValue ? raw(lowest.getAsDouble()) : service.formatPrice(lowest.getAsDouble());
        }
        if (kind.equals("listed")) return String.valueOf(service.market().activeCount(material));
        MarketStats stats = service.market().cachedStats(material);
        if (kind.equals("sales")) return String.valueOf(stats.sales());
        if (!stats.hasSales()) return rawValue ? "0" : "-";
        return rawValue ? raw(stats.averageUnit()) : service.formatPrice(stats.averageUnit());
    }

    private static String raw(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
