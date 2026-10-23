package io.nightbeam.donutauction.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * What may be listed, where and how often: the blacklist (materials, names, lore, custom model data and
 * plugin data keys), disabled worlds and game modes, per-material price limits and the listing cooldown.
 */
public final class ItemRules {

    /** Why a listing was refused: a messages.yml key and its placeholders. */
    public record Refusal(String messageKey, String fallback, String... placeholders) {
    }

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final Supplier<FileConfiguration> config;
    private final Map<UUID, Long> lastListing = new ConcurrentHashMap<>();

    public ItemRules(Supplier<FileConfiguration> config) {
        this.config = config;
    }

    /** Where the player is and how they play, checked before the item leaves their hand. */
    public Optional<Refusal> checkSeller(Player player) {
        FileConfiguration cfg = config.get();
        if (player.hasPermission("donutauction.restrictions.bypass")) return Optional.empty();
        List<String> worlds = cfg.getStringList("restrictions.disabled-worlds");
        if (player.getWorld() != null && worlds.stream().anyMatch(world -> world.equalsIgnoreCase(player.getWorld().getName()))) {
            return Optional.of(new Refusal("restrictions.world", "&cThe auction house cannot be used in this world."));
        }
        GameMode mode = player.getGameMode();
        if (mode != null && cfg.getStringList("restrictions.blocked-gamemodes").stream().anyMatch(name -> name.equalsIgnoreCase(mode.name()))) {
            return Optional.of(new Refusal("restrictions.gamemode", "&cYou cannot list items in %gamemode% mode.",
                    "gamemode", mode.name().toLowerCase(Locale.ROOT)));
        }
        long cooldown = Math.max(0L, cfg.getLong("auction.cooldown-seconds", 0L)) * 1000L;
        Long last = lastListing.get(player.getUniqueId());
        if (cooldown > 0 && last != null && !player.hasPermission("donutauction.cooldown.bypass")) {
            long left = last + cooldown - System.currentTimeMillis();
            if (left > 0) {
                return Optional.of(new Refusal("restrictions.cooldown", "&cWait %seconds%s before listing again.",
                        "seconds", String.valueOf((left + 999) / 1000)));
            }
        }
        return Optional.empty();
    }

    public void recordListing(UUID player) {
        lastListing.put(player, System.currentTimeMillis());
    }

    public void forget(UUID player) {
        lastListing.remove(player);
    }

    /** The blacklist. Players with donutauction.blacklist.bypass may list anything. */
    public Optional<Refusal> checkItem(Player player, ItemStack item) {
        if (item == null || item.getType().isAir()) return Optional.of(new Refusal("service.hold-item", "&cHold the item you want to list."));
        if (player != null && player.hasPermission("donutauction.blacklist.bypass")) return Optional.empty();
        FileConfiguration cfg = config.get();
        String material = item.getType().name();
        if (cfg.getStringList("blacklist.materials").stream().anyMatch(name -> name.equalsIgnoreCase(material))) {
            return Optional.of(blocked());
        }
        ItemMeta meta;
        try {
            meta = item.hasItemMeta() ? item.getItemMeta() : null;
        } catch (RuntimeException | LinkageError unreadable) {
            return Optional.empty();
        }
        if (meta == null) return Optional.empty();
        if (meta.hasDisplayName() && contains(PLAIN.serialize(meta.displayName()), cfg.getStringList("blacklist.name-contains"))) {
            return Optional.of(blocked());
        }
        List<Component> lore = meta.hasLore() ? meta.lore() : null;
        if (lore != null && !lore.isEmpty()) {
            List<String> needles = cfg.getStringList("blacklist.lore-contains");
            for (Component line : lore) if (contains(PLAIN.serialize(line), needles)) return Optional.of(blocked());
        }
        List<Integer> models = cfg.getIntegerList("blacklist.custom-model-data");
        try {
            if (!models.isEmpty() && meta.hasCustomModelData() && models.contains(meta.getCustomModelData())) return Optional.of(blocked());
        } catch (RuntimeException | LinkageError ignored) {
            // Custom model data became a component in 1.21.4; older accessors may not exist on forks.
        }
        for (String key : cfg.getStringList("blacklist.pdc-keys")) {
            NamespacedKey namespaced = NamespacedKey.fromString(key.toLowerCase(Locale.ROOT));
            if (namespaced != null && meta.getPersistentDataContainer().getKeys().contains(namespaced)) return Optional.of(blocked());
        }
        return Optional.empty();
    }

    /** The price window for this material: per-material limits override the global ones. */
    public double minPrice(ItemStack item) {
        double global = config.get().getDouble("auction.min-price", 10.0D);
        return limit(item, "min", global);
    }

    public double maxPrice(ItemStack item) {
        double global = config.get().getDouble("auction.max-price", 1.0E9);
        return limit(item, "max", global);
    }

    private double limit(ItemStack item, String bound, double global) {
        if (item == null) return global;
        String base = "auction.price-limits." + item.getType().name();
        FileConfiguration cfg = config.get();
        if (!cfg.isConfigurationSection(base)) {
            base = "auction.price-limits." + item.getType().name().toLowerCase(Locale.ROOT);
            if (!cfg.isConfigurationSection(base)) return global;
        }
        if (!cfg.contains(base + "." + bound)) return global;
        double value = cfg.getDouble(base + "." + bound, global);
        // Per-item limits are per single item, so a stack of 64 can cost 64 times as much.
        return Double.isFinite(value) ? value * Math.max(1, item.getAmount()) : global;
    }

    private static Refusal blocked() {
        return new Refusal("restrictions.blacklisted", "&cThat item cannot be sold on the auction house.");
    }

    private static boolean contains(String text, List<String> needles) {
        if (text == null || needles.isEmpty()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && lower.contains(needle.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}
