package io.nightbeam.donutauction.util;

import io.nightbeam.donutauction.AuctionHousePlugin;
import io.nightbeam.donutauction.model.AuctionFilterCategory;
import io.nightbeam.donutauction.model.AuctionSortMode;
import io.nightbeam.donutauction.model.AuctionStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

public final class MessageUtil {

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.legacyAmpersand();

    private final AuctionHousePlugin plugin;
    private String prefix;

    public MessageUtil(AuctionHousePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        FileConfiguration messages = messages();
        String fromMessages = messages.getString("prefix");
        String fromConfig = plugin.getConfig().getString("messages.prefix");
        if (fromMessages != null && !fromMessages.isEmpty()) {
            this.prefix = fromMessages;
        } else if (fromConfig != null && !fromConfig.isEmpty()) {
            this.prefix = fromConfig;
        } else {
            this.prefix = "&6&lAuctionHouse &8» ";
        }
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

    public String format(String path, String def, Map<String, String> placeholders) {
        String template = raw(path, def);
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                template = template.replace("%" + entry.getKey() + "%", entry.getValue());
            }
        }
        return template;
    }

    public String format(String path, String def, String... keyValues) {
        return format(path, def, toMap(keyValues));
    }

    public Component component(String message) {
        return SERIALIZER.deserialize(message);
    }

    public Component component(String path, String def) {
        return component(raw(path, def));
    }

    public Component component(String path, String def, String... keyValues) {
        return component(format(path, def, keyValues));
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
            out.add(component(formatNamed(line, placeholders)));
        }
        return out;
    }

    public void send(CommandSender sender, String message) {
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
        return raw("filter.categories." + category.name().toLowerCase(), category.displayName());
    }

    public String sortMode(AuctionSortMode mode) {
        String key = mode.name().toLowerCase().replace('_', '-');
        return raw("sort." + key, mode.displayName());
    }

    public String statusLabel(AuctionStatus status) {
        return raw("status." + status.name().toLowerCase(), status.name());
    }

    public String durationLabel(int hours) {
        String key = switch (hours) {
            case 6 -> "duration.6-hours";
            case 12 -> "duration.12-hours";
            case 24 -> "duration.1-day";
            case 48 -> "duration.2-days";
            case 72 -> "duration.3-days";
            case 168 -> "duration.1-week";
            default -> null;
        };
        if (key == null) {
            return hours + " Hours";
        }
        return raw(key, hours + " Hours");
    }

    public String unknownSeller() {
        return raw("unknown-seller", "Unknown");
    }

    private FileConfiguration messages() {
        return plugin.getMessagesConfig();
    }

    private static String formatNamed(String template, Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        return result;
    }

    private static Map<String, String> toMap(String... keyValues) {
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
