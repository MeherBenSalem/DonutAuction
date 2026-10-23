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

        // Permission nodes contain dots, which YAML stores as nested sections: read the full paths.
        readTiers("auction-limits.permissions", false);
        readTiers("auction-slots.permissions", true);
    }

    private void readTiers(String path, boolean keepExisting) {
        var section = plugin.getConfig().getConfigurationSection(path);
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(true)) {
            if (section.isConfigurationSection(key)) {
                continue;
            }
            int value = section.getInt(key, 0);
            if (keepExisting) {
                permissionLimits.putIfAbsent(key, value);
            } else {
                permissionLimits.put(key, value);
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
