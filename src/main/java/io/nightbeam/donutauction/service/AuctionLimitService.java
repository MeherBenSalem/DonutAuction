package io.nightbeam.donutauction.service;

import io.nightbeam.donutauction.AuctionHousePlugin;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.entity.Player;

public final class AuctionLimitService {

    private final AuctionHousePlugin plugin;
    private int defaultLimit;
    private Map<String, Integer> permissionLimits;

    public AuctionLimitService(AuctionHousePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.defaultLimit = plugin.getConfig().getInt("auction-limits.default-limit", 5);
        this.permissionLimits = new HashMap<>();

        if (plugin.getConfig().isConfigurationSection("auction-limits.permissions")) {
            var section = plugin.getConfig().getConfigurationSection("auction-limits.permissions");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    permissionLimits.put(key, section.getInt(key, 0));
                }
            }
        }

        if (plugin.getConfig().isConfigurationSection("auction-slots.permissions")) {
            var section = plugin.getConfig().getConfigurationSection("auction-slots.permissions");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    permissionLimits.putIfAbsent(key, section.getInt(key, 0));
                }
            }
        }
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("auction-limits.enabled", true);
    }

    public int getEffectiveLimit(Player player) {
        if (!isEnabled()) {
            return -1;
        }

        int highest = defaultLimit;
        for (Map.Entry<String, Integer> entry : permissionLimits.entrySet()) {
            if (player.hasPermission(entry.getKey())) {
                int value = entry.getValue();
                if (value == -1) {
                    return -1;
                }
                highest = Math.max(highest, value);
            }
        }
        return highest;
    }

    public boolean isUnlimited(Player player) {
        return getEffectiveLimit(player) == -1;
    }

    public boolean canCreateListing(Player player, int currentActiveCount) {
        if (!isEnabled()) {
            return true;
        }
        int limit = getEffectiveLimit(player);
        if (limit == -1) {
            return true;
        }
        return currentActiveCount < limit;
    }

    public int getRemainingSlots(Player player, int currentActiveCount) {
        int limit = getEffectiveLimit(player);
        if (limit == -1) {
            return -1;
        }
        return Math.max(0, limit - currentActiveCount);
    }
}
