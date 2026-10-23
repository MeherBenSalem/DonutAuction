package io.nightbeam.donutauction.integration;

import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.util.ItemNames;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Posts listings, sales, bids and wins to a Discord channel webhook as embeds with the item's name,
 * amount, lore and enchantments, the price and the traders' heads. Requests run on one background thread
 * in order, and Discord's rate limit (HTTP 429) is respected.
 */
public final class DiscordWebhook {

    public enum Kind {
        LISTED(0xF2B33D, "New listing"),
        SOLD(0x4CC38A, "Sold"),
        BID(0x5B8DEF, "New bid"),
        WON(0x9B6DFF, "Auction won"),
        EXPIRED(0x8A8F98, "Listing ended");

        final int color;
        final String title;

        Kind(int color, String title) {
            this.color = color;
            this.title = title;
        }
    }

    /** Everything an embed shows; prices are already formatted with the server's economy. */
    public record Post(Kind kind, AuctionListing listing, String price, double rawPrice, String sellerName, String otherName,
                       String otherUuid) {
    }

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Pattern RETRY_AFTER = Pattern.compile("\"retry_after\"\\s*:\\s*([0-9.]+)");

    private final Supplier<FileConfiguration> config;
    private final Logger logger;
    private final HttpClient client;
    private final ExecutorService sender;
    private volatile long lastWarning;

    public DiscordWebhook(Supplier<FileConfiguration> config, Logger logger) {
        this.config = config;
        this.logger = logger;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.sender = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "DonutAuctionHouse-Discord");
            thread.setDaemon(true);
            return thread;
        });
    }

    public boolean wants(Kind kind, double price) {
        FileConfiguration cfg = config.get();
        if (!cfg.getBoolean("discord.enabled", false)) return false;
        String url = cfg.getString("discord.webhook-url", "");
        if (url == null || !url.startsWith("https://")) return false;
        if (price < cfg.getDouble("discord.min-price", 0.0D)) return false;
        return cfg.getStringList("discord.events").stream().anyMatch(name -> name.equalsIgnoreCase(kind.name()));
    }

    public void post(Post post) {
        if (!wants(post.kind(), post.rawPrice())) return;
        FileConfiguration cfg = config.get();
        String url = cfg.getString("discord.webhook-url", "");
        String body = json(post, cfg);
        try {
            sender.execute(() -> send(url, body, 0));
        } catch (RuntimeException rejected) {
            // Shutting down.
        }
    }

    private void send(String url, String body, int attempt) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "DonutAuctionHouse (https://github.com/MeherBenSalem/DonutAuction)")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429 && attempt < 3) {
                Matcher matcher = RETRY_AFTER.matcher(response.body());
                double seconds = matcher.find() ? Double.parseDouble(matcher.group(1)) : 2.0D;
                Thread.sleep((long) Math.min(30_000, Math.max(250, seconds * 1000)));
                send(url, body, attempt + 1);
            } else if (response.statusCode() >= 300) {
                warn("Discord webhook answered HTTP " + response.statusCode() + ". Check discord.webhook-url.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            warn("Discord webhook failed: " + exception.getMessage());
        }
    }

    private void warn(String message) {
        long now = System.currentTimeMillis();
        if (now - lastWarning > 60_000L) {
            lastWarning = now;
            logger.warning(message);
        }
    }

    static String json(Post post, FileConfiguration cfg) {
        AuctionListing listing = post.listing();
        ItemStack item = listing.item();
        String name = ItemNames.plain(item);
        StringBuilder description = new StringBuilder("**").append(markdown(name)).append("**");
        if (item.getAmount() > 1) description.append(" ×").append(item.getAmount());
        for (String line : details(item)) description.append("\n").append(markdown(line));

        List<String> fields = new ArrayList<>();
        fields.add(field(post.kind() == Kind.BID ? "Bid" : "Price", post.price(), true));
        if (post.sellerName() != null) fields.add(field("Seller", post.sellerName(), true));
        if (post.otherName() != null) {
            String label = switch (post.kind()) {
                case BID -> "Bidder";
                case WON -> "Winner";
                default -> "Buyer";
            };
            fields.add(field(label, post.otherName(), true));
        }
        fields.add(field("Type", listing.isBid() ? "Auction" : "Buy It Now", true));
        if (post.kind() == Kind.LISTED || post.kind() == Kind.BID) {
            fields.add(field("Ends", "<t:" + listing.expirationTime() / 1000 + ":R>", true));
        }

        String title = cfg.getString("discord.titles." + post.kind().name().toLowerCase(Locale.ROOT), post.kind().title);
        String head = cfg.getString("discord.head-url", "https://mc-heads.net/avatar/{uuid}/64");
        String icon = cfg.getString("discord.item-icon-url", "");
        StringBuilder json = new StringBuilder("{");
        appendString(json, "username", cfg.getString("discord.username", "Auction House")).append(',');
        String avatar = cfg.getString("discord.avatar-url", "");
        if (avatar != null && !avatar.isBlank()) appendString(json, "avatar_url", avatar).append(',');
        json.append("\"allowed_mentions\":{\"parse\":[]},\"embeds\":[{");
        appendString(json, "title", title).append(',');
        appendString(json, "description", description.toString()).append(',');
        json.append("\"color\":").append(post.kind().color).append(',');
        json.append("\"fields\":[").append(String.join(",", fields)).append("],");
        if (head != null && !head.isBlank()) {
            json.append("\"author\":{");
            appendString(json, "name", post.sellerName() == null ? "Server" : post.sellerName()).append(',');
            appendString(json, "icon_url", head.replace("{uuid}", listing.seller().toString()));
            json.append("},");
        }
        if (icon != null && !icon.isBlank()) {
            String key = item.getType().name().toLowerCase(Locale.ROOT);
            json.append("\"thumbnail\":{");
            appendString(json, "url", icon.replace("{material}", key));
            json.append("},");
        }
        json.append("\"footer\":{");
        appendString(json, "text", cfg.getString("discord.footer", "DonutAuctionHouse"));
        json.append("},");
        appendString(json, "timestamp", Instant.now().toString());
        json.append("}]}");
        return json.toString();
    }

    /** Lore and enchantments as plain lines, at most eight. */
    static List<String> details(ItemStack item) {
        List<String> lines = new ArrayList<>();
        try {
            if (!item.hasItemMeta()) return lines;
            ItemMeta meta = item.getItemMeta();
            Map<org.bukkit.enchantments.Enchantment, Integer> enchants = new java.util.LinkedHashMap<>(meta.getEnchants());
            if (meta instanceof EnchantmentStorageMeta stored) enchants.putAll(stored.getStoredEnchants());
            enchants.forEach((enchantment, level) -> lines.add(enchantmentName(enchantment) + levelSuffix(enchantment, level)));
            List<Component> lore = meta.hasLore() ? meta.lore() : null;
            if (lore != null) for (Component line : lore) lines.add(PLAIN.serialize(line));
        } catch (RuntimeException | LinkageError ignored) {
            // Metadata from another version: the name and amount are enough.
        }
        return lines.size() > 8 ? lines.subList(0, 8) : lines;
    }

    private static String enchantmentName(org.bukkit.enchantments.Enchantment enchantment) {
        try {
            return title(enchantment.getKey().getKey());
        } catch (RuntimeException | LinkageError unavailable) {
            return String.valueOf(enchantment);
        }
    }

    /** " V" as the game shows it; nothing for enchantments that only have one level, like Mending. */
    private static String levelSuffix(org.bukkit.enchantments.Enchantment enchantment, int level) {
        try {
            if (level == 1 && enchantment.getMaxLevel() <= 1) return "";
        } catch (RuntimeException | LinkageError unavailable) {
            // Unknown enchantment: show the level.
        }
        return " " + roman(level);
    }

    static String roman(int level) {
        if (level < 1 || level > 10) return String.valueOf(level);
        return new String[] {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"}[level - 1];
    }

    private static String title(String key) {
        StringBuilder out = new StringBuilder();
        for (String word : key.split("_")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private static String field(String name, String value, boolean inline) {
        StringBuilder json = new StringBuilder("{");
        appendString(json, "name", name).append(',');
        appendString(json, "value", value == null || value.isBlank() ? "-" : value).append(',');
        json.append("\"inline\":").append(inline).append('}');
        return json.toString();
    }

    private static String markdown(String text) {
        return text.replaceAll("([*_~`|>\\\\])", "\\\\$1");
    }

    private static StringBuilder appendString(StringBuilder json, String key, String value) {
        json.append('"').append(key).append("\":\"");
        String text = value == null ? "" : value;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) json.append(String.format("\\u%04x", (int) c));
                    else json.append(c);
                }
            }
        }
        return json.append('"');
    }

    public void shutdown() {
        sender.shutdown();
        try {
            sender.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
