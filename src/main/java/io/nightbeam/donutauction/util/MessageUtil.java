package io.nightbeam.donutauction.util;

import io.nightbeam.donutauction.AuctionHousePlugin;
import io.nightbeam.donutauction.model.AuctionFilterCategory;
import io.nightbeam.donutauction.model.AuctionSortMode;
import io.nightbeam.donutauction.model.AuctionStatus;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Messages accept MiniMessage tags ({@code <gold>}, {@code <#ffaa00>}, {@code <gradient:...>}), legacy
 * {@code &} codes and {@code &#RRGGBB} hex in the same string.
 *
 * <p>Templates come from messages.yml and are trusted. Placeholder values (item names, player names,
 * search terms) are escaped so they can never add formatting, click events or colors. Values produced by
 * the plugin itself, such as translatable item names, are wrapped with {@link #trusted(String)}.
 */
public final class MessageUtil {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Pattern PLACEHOLDER = Pattern.compile("%([A-Za-z0-9_.-]+)%");
    private static final Pattern LEGACY = Pattern.compile("(?i)[&§](?:#([0-9a-f]{6})|x((?:[&§][0-9a-f]){6})|([0-9a-fk-or&]))");
    /** Unforgeable marker for plugin-generated MiniMessage fragments; players cannot type it into names. */
    private static final String TRUSTED = "\uE000" + randomToken() + "\uE001";
    private static final Map<Character, String> CODES = Map.ofEntries(
            Map.entry('0', "black"), Map.entry('1', "dark_blue"), Map.entry('2', "dark_green"), Map.entry('3', "dark_aqua"),
            Map.entry('4', "dark_red"), Map.entry('5', "dark_purple"), Map.entry('6', "gold"), Map.entry('7', "gray"),
            Map.entry('8', "dark_gray"), Map.entry('9', "blue"), Map.entry('a', "green"), Map.entry('b', "aqua"),
            Map.entry('c', "red"), Map.entry('d', "light_purple"), Map.entry('e', "yellow"), Map.entry('f', "white"),
            Map.entry('k', "obfuscated"), Map.entry('l', "bold"), Map.entry('m', "strikethrough"), Map.entry('n', "underlined"),
            Map.entry('o', "italic"));

    private final AuctionHousePlugin plugin;
    private String prefix;

    public MessageUtil(AuctionHousePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        FileConfiguration messages = messages();
        String fromMessages = messages == null ? null : messages.getString("prefix");
        String fromConfig = plugin.getConfig() == null ? null : plugin.getConfig().getString("messages.prefix");
        if (fromMessages != null && !fromMessages.isEmpty()) {
            this.prefix = fromMessages;
        } else if (fromConfig != null && !fromConfig.isEmpty()) {
            this.prefix = fromConfig;
        } else {
            this.prefix = "&6&lAuctionHouse &8» ";
        }
    }

    /** Marks a MiniMessage fragment built by the plugin so placeholder escaping keeps its tags. */
    public static String trusted(String miniMessage) {
        return TRUSTED + miniMessage;
    }

    /** Escapes a value for use inside a template: no tags, no legacy codes. */
    public static String escape(String value) {
        if (value == null) return "";
        if (value.startsWith(TRUSTED)) return value.substring(TRUSTED.length());
        return MINI.escapeTags(value.replace(TRUSTED, "")).replace("&", "&&").replace("§", "&&");
    }

    public String raw(String path, String def) {
        FileConfiguration config = messages();
        if (config == null) {
            return def != null ? def : path;
        }
        String value = config.getString(path);
        if (value == null || value.isEmpty()) {
            return def != null ? def : path;
        }
        return value;
    }

    public List<String> rawList(String path, List<String> def) {
        FileConfiguration config = messages();
        if (config == null || !config.isList(path)) return def;
        List<String> lines = config.getStringList(path);
        return lines.isEmpty() ? def : lines;
    }

    public String format(String path, String def, Map<String, String> placeholders) {
        return substitute(raw(path, def), placeholders);
    }

    public String format(String path, String def, String... keyValues) {
        return format(path, def, toMap(keyValues));
    }

    /** Substitutes escaped values into a trusted template in one pass, so values are never re-expanded. */
    public static String substitute(String template, Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty() || template.indexOf('%') < 0) return template;
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = placeholders.get(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? matcher.group() : escape(value)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Parses a trusted template (MiniMessage and legacy codes). */
    public Component component(String message) {
        return parse(message);
    }

    public static Component parse(String message) {
        if (message == null || message.isEmpty()) return Component.empty();
        try {
            return MINI.deserialize(legacyToMiniMessage(message.replace(TRUSTED, "")));
        } catch (RuntimeException malformed) {
            return Component.text(message);
        }
    }

    public Component component(String path, String def) {
        return component(raw(path, def));
    }

    public Component component(String path, String def, String... keyValues) {
        return component(format(path, def, keyValues));
    }

    /** An item name or menu title: no default italics. */
    public Component name(String path, String def, String... keyValues) {
        return noItalic(component(path, def, keyValues));
    }

    /** A lore line: no default italics, gray unless the template sets a color. */
    public Component lore(String path, String def, String... keyValues) {
        return loreLine(component(path, def, keyValues));
    }

    public static Component noItalic(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static Component loreLine(Component component) {
        return noItalic(component).colorIfAbsent(NamedTextColor.GRAY);
    }

    public List<Component> loreComponents(String path, String... keyValues) {
        FileConfiguration config = messages();
        if (config == null) {
            return List.of();
        }
        List<String> lines = config.getStringList(path);
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        Map<String, String> placeholders = toMap(keyValues);
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(loreLine(component(substitute(line, placeholders))));
        }
        return out;
    }

    public String plain(Component component) {
        return PLAIN.serialize(component);
    }

    public void send(CommandSender sender, String message) {
        if (message == null || message.isBlank()) return;
        sender.sendMessage(component(prefix + message));
    }

    public void sendRaw(CommandSender sender, String path, String def) {
        send(sender, raw(path, def));
    }

    public void sendFormatted(CommandSender sender, String path, String def, String... keyValues) {
        send(sender, format(path, def, keyValues));
    }

    public void sendComponent(CommandSender sender, String path, String def, String... keyValues) {
        sender.sendMessage(component(path, def, keyValues));
    }

    public String filterCategory(AuctionFilterCategory category) {
        return raw("filter.categories." + category.name().toLowerCase(Locale.ROOT), category.displayName());
    }

    public String sortMode(AuctionSortMode mode) {
        String key = mode.name().toLowerCase(Locale.ROOT).replace('_', '-');
        return raw("sort." + key, mode.displayName());
    }

    public String statusLabel(AuctionStatus status) {
        return raw("status." + status.name().toLowerCase(Locale.ROOT), status.name());
    }

    public String durationLabel(int hours) {
        String key = switch (hours) {
            case 1 -> "duration.1-hour";
            case 6 -> "duration.6-hours";
            case 12 -> "duration.12-hours";
            case 24 -> "duration.1-day";
            case 48 -> "duration.2-days";
            case 72 -> "duration.3-days";
            case 168 -> "duration.1-week";
            default -> null;
        };
        String fallback = hours % 24 == 0 && hours >= 24 ? hours / 24 + " Days" : hours + " Hours";
        if (key == null) {
            return fallback;
        }
        return raw(key, fallback);
    }

    public String unknownSeller() {
        return raw("unknown-seller", "Unknown");
    }

    private FileConfiguration messages() {
        return plugin.getMessagesConfig();
    }

    /** Converts legacy {@code &}/{@code §} codes and hex to MiniMessage tags; {@code &&} is a literal ampersand. */
    static String legacyToMiniMessage(String input) {
        if (input.indexOf('&') < 0 && input.indexOf('§') < 0) return input;
        Matcher matcher = LEGACY.matcher(input);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replacement;
            if (matcher.group(1) != null) {
                replacement = "<reset><#" + matcher.group(1).toLowerCase(Locale.ROOT) + ">";
            } else if (matcher.group(2) != null) {
                String digits = matcher.group(2).replaceAll("[&§]", "");
                replacement = "<reset><#" + digits.toLowerCase(Locale.ROOT) + ">";
            } else {
                char code = Character.toLowerCase(matcher.group(3).charAt(0));
                if (code == '&') {
                    replacement = "&";
                } else if (code == 'r') {
                    replacement = "<reset>";
                } else if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')) {
                    // A legacy color also clears bold, italic and the rest.
                    replacement = "<reset><" + CODES.get(code) + ">";
                } else {
                    replacement = "<" + CODES.get(code) + ">";
                }
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String randomToken() {
        byte[] bytes = new byte[8];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static Map<String, String> toMap(String... keyValues) {
        Map<String, String> map = new HashMap<>();
        if (keyValues == null) {
            return map;
        }
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
